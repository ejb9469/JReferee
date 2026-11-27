"""Checks timing boundaries, bounded updates and profiling artifact isolation."""

import argparse
from contextlib import redirect_stdout
import copy
from dataclasses import replace
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import torch

from . import model as base_api
from . import self_play_model as model_api
from . import self_play_profile as profile_api
from . import self_play_train as train_api
from . import test_self_play_train as fixtures
from . import train as legacy
from .self_play_cache import sha


class ProfileTests(fixtures.FixtureCase):
    def setUp(self):
        super().setUp()
        for module in (legacy, model_api, train_api):
            context = patch.object(module, "SPEC_HASH", sha(fixtures.MOVEMENT_RAW))
            context.start()
            self.addCleanup(context.stop)
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        torch.manual_seed(7)
        self.initial = self.directory / "initial.pt"
        cache_info = SimpleNamespace(
            spec_text=fixtures.MOVEMENT_RAW.decode(),
            manifest={"dataset_index_sha256": sha(b"historical-index")},
            manifest_hash=sha(b"historical-cache"),
        )
        legacy.save_policy(self.initial, base_api.MovementGRU(self.spec, 16), cache_info, 1)
        self.settings = train_api.Settings(epochs=1, batch_size=1, candidate_batch_size=1,
                                          max_options=4, threads=1)

    def test_timed_and_plain_updates_match_original_steps(self):
        reference, _, _ = model_api.load_start(self.initial, self.spec, self.anchor_hash, self.data.split_seed)
        initial = copy.deepcopy(reference)
        roots = self.roots()

        def optimizer(model):
            return torch.optim.AdamW(model.parameters(), lr=self.settings.learning_rate,
                                     weight_decay=self.settings.weight_decay)

        baseline = optimizer(reference)
        baseline.zero_grad(set_to_none=True)
        losses = train_api._cross_entropies(reference, roots, self.settings, backward=True)
        torch.nn.utils.clip_grad_norm_(reference.parameters(), self.settings.gradient_clip,
                                      error_if_nonfinite=True)
        baseline.step()
        for timings in (None, {}):
            model = copy.deepcopy(initial)
            with patch.object(train_api, "_timed", wraps=train_api._timed) as measured:
                actual = train_api.train_batch(model, optimizer(model), roots, self.settings, timings)
            self.assertEqual(actual, losses)
            for name, value in reference.state_dict().items():
                torch.testing.assert_close(model.state_dict()[name], value, rtol=0, atol=0)
            if timings is not None:
                stages = [call.args[1] for call in measured.call_args_list]
                self.assertEqual({stage: stages.count(stage) for stage in set(stages)}, {
                    "candidate_planning": 5, "tensor_assembly": 4,
                    "forward_loss": 8, "backward": 4, "optimizer": 2,
                })
                self.assertEqual(set(timings), set(stages))
                self.assertTrue(all(value > 0 for value in timings.values()))

    def test_cli_excludes_warmup_and_preserves_inputs(self):
        output = self.directory / "profile"
        files = [self.initial, self.anchor_path] + [p for p in self.path.rglob("*") if p.is_file()]
        before = {path: sha(path.read_bytes()) for path in files}
        original_batches = profile_api.PolicyData.batches
        splits = []

        def batches(data, split, size, seed=None):
            splits.append(split)
            return original_batches(data, split, size, seed)

        with patch.object(profile_api.PolicyData, "batches", batches), \
                patch.object(profile_api, "train_batch", wraps=profile_api.train_batch) as updates, \
                patch.object(train_api, "evaluate", side_effect=AssertionError("Unexpected validation")), \
                redirect_stdout(io.StringIO()):
            profile_api.main([
                "--cache", str(self.path), "--initial-policy", str(self.initial),
                "--anchor", str(self.anchor_path), "--output", str(output),
                "--batches", "1", "--warmup-batches", "1", "--batch-size", "1",
                "--candidate-batch-size", "1", "--max-options", "4", "--threads", "1",
            ])
        report = json.loads((output / "profile.json").read_text())
        self.assertTrue(report["complete"])
        self.assertEqual(splits, ["TRAIN"])
        self.assertEqual(updates.call_count, 2)
        self.assertEqual(report["optimizer_steps"], 2)
        self.assertGreater(torch.count_nonzero(updates.call_args.args[0].type_projection.weight).item(), 0)
        self.assertEqual([row["measured"] for row in report["batches"]], [False, True])
        self.assertEqual(report["measured"]["batches"], 1)
        self.assertEqual(report["measured"]["roots"], 1)
        self.assertEqual(report["measured"]["positive_candidates"], 2)
        self.assertEqual(report["measured"]["elapsed_seconds"], report["batches"][1]["elapsed_seconds"])
        self.assertEqual(report["warmup"]["elapsed_seconds"], report["batches"][0]["elapsed_seconds"])
        for row in report["batches"]:
            self.assertAlmostEqual(sum(row["seconds"].values()), row["elapsed_seconds"], places=9)
        self.assertAlmostEqual(sum(report["measured"][name + "_percent"]
                                   for name in ("preparation", "computation", "other")), 100, places=7)
        self.assertFalse(report["checkpoint_written"] or report["validation_evaluated"]
                         or report["value_trained"] or report["test_evaluated"])
        self.assertEqual(before, {path: sha(path.read_bytes()) for path in files})
        self.assertEqual({p.name for p in output.iterdir()}, {"profile.json"})
        with self.assertRaises(FileExistsError):
            profile_api.profile([self.path], self.initial, self.anchor_path, output, self.settings, 1, 0)
        self.assertEqual(json.loads((output / "profile.json").read_text()), report)

    def test_known_stage_durations_exclude_startup_and_warmup(self):
        clock = [0.0]
        original_timed = train_api._timed
        original_batches = profile_api.PolicyData.batches
        durations = {"candidate_planning": 2, "tensor_assembly": 3,
                     "forward_loss": 4, "backward": 5, "optimizer": 6}

        def delayed(operation, seconds):
            def run(*args, **kwargs):
                result = operation(*args, **kwargs)
                clock[0] += seconds
                return result
            return run

        def measured(timings, stage, operation, *args, **kwargs):
            return original_timed(timings, stage, delayed(operation, durations[stage]), *args, **kwargs)

        def batches(data, split, size, seed=None):
            for index, batch in enumerate(original_batches(data, split, size, seed)):
                clock[0] += 101 if index == 0 else 1
                yield batch

        with patch.object(train_api.time, "perf_counter", side_effect=lambda: clock[0]), \
                patch.object(train_api, "_timed", measured), \
                patch.object(profile_api.PolicyData, "batches", batches), \
                patch.object(profile_api, "SelfPlayCache", delayed(profile_api.SelfPlayCache, 500)), \
                patch.object(profile_api, "load_start", delayed(profile_api.load_start, 700)), \
                redirect_stdout(io.StringIO()):
            report = profile_api.profile([self.path], self.initial, self.anchor_path,
                                         self.directory / "known-times", self.settings, 1, 1)
        self.assertEqual(report["startup_seconds"], {
            "cache_verification_and_indexing": 500, "model_and_optimizer_setup": 700,
        })
        self.assertEqual(report["warmup"]["elapsed_seconds"], 151)
        result = report["measured"]
        self.assertEqual(result["elapsed_seconds"], 51)
        self.assertEqual(result["stage_seconds"], {
            "root_decoding": 1, "candidate_planning": 6, "tensor_assembly": 6,
            "forward_loss": 16, "backward": 10, "optimizer": 12, "other": 0,
        })
        self.assertEqual(result["preparation_seconds"], 13)
        self.assertEqual(result["computation_seconds"], 38)
        self.assertAlmostEqual(result["preparation_percent"], 100 * 13 / 51)

    def test_preflight_and_short_train_only_batch(self):
        output = self.directory / "guarded"
        for settings, count, warmup, message in (
                (self.settings, 0, 0, "measured batches"),
                (self.settings, 1, -1, "warm-up batches"),
                (self.settings, 2, 1, "TRAIN supplies"),
                (replace(self.settings, device="cuda"), 1, 0, "CPU execution"),
                (replace(self.settings, max_options=3), 1, 0, "max-options")):
            with self.assertRaisesRegex(ValueError, message), redirect_stdout(io.StringIO()):
                profile_api.profile([self.path], self.initial, self.anchor_path,
                                    output, settings, count, warmup)
            self.assertFalse(output.exists())
        with self.assertRaises(ValueError), redirect_stdout(io.StringIO()):
            profile_api.profile([self.path, self.path], self.initial, self.anchor_path,
                                output, self.settings, 1, 0)
        self.assertFalse(output.exists())
        only_train = self.make_cache("only-train", True)
        with redirect_stdout(io.StringIO()):
            report = profile_api.profile([only_train], self.initial, self.anchor_path, output,
                                         replace(self.settings, batch_size=3), 1, 0)
        self.assertEqual(report["policy_roots"]["VALIDATION"], 0)
        self.assertEqual(report["measured"]["roots"], 2)
        self.assertIsNone(report["warmup"])

    def test_failure_leaves_incomplete_report(self):
        output = self.directory / "failed"
        before = self.initial.read_bytes()
        with patch.object(profile_api, "train_batch", side_effect=RuntimeError("Fixture update failed")), \
                self.assertRaisesRegex(RuntimeError, "Fixture update failed"), redirect_stdout(io.StringIO()):
            profile_api.profile([self.path], self.initial, self.anchor_path, output, self.settings, 1, 0)
        report = json.loads((output / "profile.json").read_text())
        self.assertFalse(report["complete"])
        self.assertEqual({p.name for p in output.iterdir()}, {"profile.json"})
        self.assertEqual(self.initial.read_bytes(), before)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, help="value/specification.json from a verified Java cache")
    args = parser.parse_args()
    fixtures.prepare_spec(args.spec)
    if args.spec is None:
        print("Synthetic map fixture with test-only hashes; production specification checks are unchanged.")
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ProfileTests))
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()