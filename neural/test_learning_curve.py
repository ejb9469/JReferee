"""Whole-game selection, fixed validation, persistence and failure checks."""

import argparse
from collections import Counter
from dataclasses import replace
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

from . import learning_curve as curve
from . import test_neural as fixtures
from .cache import Cache, SPEC_HASH, digest, parse_json
from .progress import Progress
from .train import Settings, evaluate, fit, load_policy


class LearningCurveTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.cache_path = self.root / "cache"
        self.progress = Progress(False, io.StringIO())
        self.data = {}
        for split, count in (("TRAIN", 12), ("VALIDATION", 6), ("TEST", 1)):
            self.data[split] = [
                replace(fixtures.example(i, split, fleet=i % 5 == 4),
                        context=np.array([3, 2, year], dtype=np.int32))
                for i in range(count) for year in (1902, 1903)
            ]
        fixtures.write_cache(self.cache_path, self.data)
        self.pin = digest(self.cache_path / "manifest.json")
        self.settings = Settings(epochs=2, hidden=16, batch_size=4, threads=1, validation_samples=1)

    def tearDown(self):
        self.temporary.cleanup()

    def test_whole_games_nested_strata_and_restoration(self):
        with Cache(self.cache_path) as cache:
            games = curve.catalog(cache, self.progress)
            points = curve.selections(games["TRAIN"], (25, 50, 100), 7)
            again = curve.selections(dict(reversed(list(games["TRAIN"].items()))), (25, 50, 100), 7)
            self.assertEqual([point for point, _ in points], [point for point, _ in again])
            original = cache.indices["TRAIN"]
            original_info = cache.split_info["TRAIN"]
            validation = cache.indices["VALIDATION"]
            previous = set()
            for (point, rows), (_, repeated_rows), count in zip(points, again, (3, 6, 12)):
                np.testing.assert_array_equal(rows, repeated_rows)
                self.assertEqual(point["training"]["games"], count)
                members = {(m["source"], m["game_key"]) for m in point["training"]["members"]}
                self.assertTrue(previous < members)
                self.assertFalse(members & games["VALIDATION"].keys())
                previous = members
                self.assertEqual(Counter(m["communication_type"] for m in point["training"]["members"]),
                                 {None: count // 3, "Full": count // 3, "None": count // 3})
                with curve.training_subset(cache, rows):
                    seen = Counter((e.metadata["source"], e.metadata["game_key"])
                                   for batch in cache.batches("TRAIN", seed=7) for e in batch)
                    self.assertEqual(seen, {key: 2 for key in members})
                    self.assertEqual(cache.split_info["TRAIN"]["examples"], count * 2)
                    self.assertIs(cache.indices["VALIDATION"], validation)
                self.assertIs(cache.indices["TRAIN"], original)
                self.assertIs(cache.split_info["TRAIN"], original_info)
            np.testing.assert_array_equal(points[-1][1], np.arange(len(original)))
            with self.assertRaisesRegex(RuntimeError, "fixture"):
                with curve.training_subset(cache, points[0][1]):
                    raise RuntimeError("fixture")
            self.assertIs(cache.indices["TRAIN"], original)
            self.assertIs(cache.split_info["TRAIN"], original_info)

    def test_real_fits_and_full_data_control(self):
        (self.cache_path / "test-00000/options.u8").write_bytes(b"TEST must remain unopened")
        before = {str(path.relative_to(self.cache_path)): digest(path)
                  for path in self.cache_path.rglob("*") if path.is_file()}
        output = self.root / "curve"
        result = curve.run(self.cache_path, output, self.pin, self.settings, progress=self.progress)
        self.assertTrue(result["complete"])
        self.assertEqual(parse_json((output / "curve.json").read_bytes()), result)
        self.assertEqual(result["plan"], curve.receipt(output / "plan.json"))
        plan = parse_json((output / "plan.json").read_bytes())
        self.assertEqual(plan["validation"]["games"], 6)
        self.assertEqual(plan["validation"]["examples"], 12)
        for point, expected_examples in zip(result["points"], (6, 12, 24)):
            directory = output / point["directory"]
            report = parse_json((directory / "training.json").read_bytes())
            self.assertEqual(point["files"]["policy.pt"]["sha256"], report["model_sha256"])
            for name, receipt in point["files"].items():
                self.assertEqual(receipt, curve.receipt(directory / name))
            self.assertEqual([epoch["examples"] for epoch in report["epochs"]], [expected_examples] * 2)
            self.assertTrue(all(epoch["validation"]["overall"]["examples"] == 12 for epoch in report["epochs"]))
            self.assertFalse(report["test_evaluated"])
        with Cache(self.cache_path) as cache:
            model, report = fit(cache, self.root / "direct", self.settings, self.progress)
        control, _ = load_policy(output / "p100/policy.pt", fixtures.SPEC)
        for name, value in model.state_dict().items():
            torch.testing.assert_close(value, control.state_dict()[name], rtol=0, atol=0)
        self.assertEqual(curve.metrics(result["points"][-1]["selected_validation"]),
                         curve.metrics(report["epochs"][report["best_epoch"] - 1]["validation"]))
        self.assertEqual(before, {str(path.relative_to(self.cache_path)): digest(path)
                                  for path in self.cache_path.rglob("*") if path.is_file()})
        with self.assertRaises(FileExistsError):
            curve.run(self.cache_path, output, self.pin, self.settings, progress=self.progress)

    def test_rejected_inputs_create_no_attempt(self):
        for percentages in ((25, 25, 100), (50, 25, 100), (25, 50), (1, 2, 100)):
            with self.subTest(percentages=percentages), self.assertRaises(ValueError):
                curve.run(self.cache_path, self.root / "bad", self.pin, self.settings,
                          percentages, progress=self.progress)
            self.assertFalse((self.root / "bad").exists())
        with self.assertRaisesRegex(ValueError, "Unexpected cache manifest"):
            curve.run(self.cache_path, self.root / "bad", "0" * 64, self.settings, progress=self.progress)
        with self.assertRaisesRegex(ValueError, "outside the cache"):
            curve.run(self.cache_path, self.cache_path / "bad", self.pin, self.settings, progress=self.progress)
        self.assertFalse((self.root / "bad").exists())
        self.assertFalse((self.cache_path / "bad").exists())

    def test_conflicting_game_labels(self):
        self.data["TRAIN"][1].metadata["communication_type"] = "Full"
        path = self.root / "inconsistent"
        fixtures.write_cache(path, self.data)
        with Cache(path) as cache, self.assertRaisesRegex(ValueError, "label changed within game"):
            curve.catalog(cache, self.progress)

    def test_failure_preserves_complete_plan_and_incomplete_result(self):
        output = self.root / "failed"

        def fail(*args, **kwargs):
            plan = parse_json((output / "plan.json").read_bytes())
            self.assertEqual([point["percent"] for point in plan["points"]], [25, 50, 100])
            raise RuntimeError("fixture failure")

        with patch.object(curve, "fit", side_effect=fail), self.assertRaisesRegex(RuntimeError, "fixture failure"):
            curve.run(self.cache_path, output, self.pin, self.settings, progress=self.progress)
        result = parse_json((output / "curve.json").read_bytes())
        self.assertFalse(result["complete"])
        self.assertEqual(result["points"], [])
        self.assertEqual(result["plan"], curve.receipt(output / "plan.json"))

    def test_changed_cache_prevents_completion(self):
        output = self.root / "changed"

        def change_manifest(*args, **kwargs):
            result = evaluate(*args, **kwargs)
            with (self.cache_path / "manifest.json").open("ab") as stream:
                stream.write(b"\n")
            return result

        with patch.object(curve, "evaluate", side_effect=change_manifest):
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                curve.run(self.cache_path, output, self.pin, replace(self.settings, epochs=1),
                          progress=self.progress)
        self.assertFalse(parse_json((output / "curve.json").read_bytes())["complete"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=Path("data/movement-neural-cache-v1/specification.json"))
    args = parser.parse_args(argv)
    if digest(args.spec) != SPEC_HASH:
        raise ValueError("Self-check needs the supported Java-generated specification")
    fixtures.SPEC_RAW = args.spec.read_bytes()
    fixtures.SPEC = parse_json(fixtures.SPEC_RAW)
    unittest.main(argv=["neural.test_learning_curve"], verbosity=2)


if __name__ == "__main__":
    main()