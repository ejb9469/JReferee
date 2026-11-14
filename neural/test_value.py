"""Synthetic value-cache corruption, regression and checkpoint checks."""

import argparse
from contextlib import redirect_stdout
from dataclasses import replace
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

from .value_cache import REWARD, SPEC_HASH, ValueCache, current_sos
from .value_model import ValueNetwork
from .value_train import Settings, load_model, metrics, predict, train, training_mean


SPEC_BYTES = None


def receipt(path):
    raw = path.read_bytes()
    return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def json_file(path, value):
    path.write_text(json.dumps(value, allow_nan=False) + "\n", encoding="utf-8")


def fixture(root):
    spec = json.loads(SPEC_BYTES)
    board_spec = spec["board_specification"]
    locations = board_spec["locations"]
    row = board_spec["location_board_rows"]
    root.mkdir()
    (root / "specification.json").write_bytes(SPEC_BYTES)
    manifest = {
        "schema": "jreferee-value-cache-v1", "complete": True,
        "encoding": "jreferee-neural-value-v1", "reward_contract": REWARD,
        "dataset_fingerprint": "1" * 64, "byte_order": "LITTLE_ENDIAN",
        "test_exported": False, "specification": receipt(root / "specification.json"),
        "splits": {},
    }
    for name, offsets in (("TRAIN", [0, 3, 4]), ("VALIDATION", [0, 1, 3])):
        directory = root / name.lower()
        directory.mkdir()
        n = offsets[-1]
        board = np.zeros((n, 75, 4), dtype="u1")
        for center in ("Par", "Bre", "Mar"):
            board[:, row[locations.index(center)], 3] = 3
        for center in ("Mos", "War", "Sev", "Stp"):
            board[:, row[locations.index(center)], 3] = 6
        board[:, row[locations.index("Spa")], :3] = [2, 3, locations.index("SpaSC")]
        context = np.empty((n, 2), dtype="<i4")
        for start, end in zip(offsets, offsets[1:]):
            for i in range(start, end):
                context[i] = [(i - start) % 3 + 1, 1903 + (i - start) // 3]
        targets = np.zeros((2, 7), dtype="<f4")
        targets[:, 2] = [0.36, 0.8] if name == "TRAIN" else [0.2, 0.64]
        targets[:, 5] = 1 - targets[:, 2]
        for filename, array in (("board.u8", board), ("context.i32", context),
                                ("targets.f32", targets),
                                ("game_offsets.i64", np.asarray(offsets, dtype="<i8"))):
            (directory / filename).write_bytes(array.tobytes())
        metadata = [
            {"source": "DIPLOBN" if g == 0 else "FUTURE_PROVIDER", "game_key": name + str(g),
             "payload_sha256": "2" * 64, "communication_type": "None" if g == 0 else "Full",
             "start": offsets[g], "end": offsets[g + 1]}
            for g in range(2)
        ]
        (directory / "games.jsonl").write_text(
            "".join(json.dumps(value) + "\n" for value in metadata), encoding="utf-8")
        manifest["splits"][name] = {
            "games": 2, "positions": n, "source_sha256": "3" * 64,
            "files": {p.name: receipt(p) for p in directory.iterdir()},
        }
    json_file(root / "manifest.json", manifest)
    (root / "test").mkdir()
    (root / "test" / "board.u8").write_bytes(b"deliberately invalid TEST payload")
    return manifest


class ValueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="jreferee-value-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "cache"
        self.manifest = fixture(self.root)

    def rehash(self, name):
        self.manifest["splits"]["TRAIN"]["files"][name] = receipt(self.root / "train" / name)
        json_file(self.root / "manifest.json", self.manifest)

    def test_valid_cache_and_baseline(self):
        original_open = Path.open

        def guarded(path, *args, **kwargs):
            if "test" in path.parts:
                raise AssertionError("TEST was opened")
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", guarded):
            cache = ValueCache(self.root)
        data = cache.splits["TRAIN"]
        self.assertEqual(data.game_ids.tolist(), [0, 0, 0, 1])
        self.assertFalse(data.board.flags.writeable)
        self.assertAlmostEqual(training_mean(data)[2], 0.47, places=6)
        self.assertTrue(np.allclose(current_sos(data.board)[:, [2, 5]], [0.36, 0.64]))
        changed = data.board.copy()
        changed[:] = 0
        centers = np.flatnonzero(cache.spec["board_specification"]["supply_centers"])
        changed[:, centers[:18], 3] = 3
        self.assertTrue(np.all(current_sos(changed)[:, 2] == 1))

    def test_checksum(self):
        path = self.root / "train" / "board.u8"
        raw = bytearray(path.read_bytes())
        raw[0] ^= 1
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "Checksum"):
            ValueCache(self.root)

    def test_semantic_corruption(self):
        cases = [
            ("targets.f32", "<f4", 0, float("nan"), "SoS targets"),
            ("game_offsets.i64", "<i8", 1, 0, "game offsets"),
            ("context.i32", "<i4", 0, 4, "value context"),
            ("board.u8", "u1", 0, 1, "Partial unit"),
        ]
        for name, dtype, index, value, message in cases:
            with self.subTest(name=name):
                path = self.root / "train" / name
                original = path.read_bytes()
                array = np.frombuffer(original, dtype=dtype).copy()
                array[index] = value
                path.write_bytes(array.tobytes())
                self.rehash(name)
                with self.assertRaisesRegex(ValueError, message):
                    ValueCache(self.root)
                path.write_bytes(original)
                self.rehash(name)

    def test_duplicate_game(self):
        path = self.root / "validation" / "games.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[0]["game_key"] = "TRAIN0"
        path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        self.manifest["splits"]["VALIDATION"]["files"]["games.jsonl"] = receipt(path)
        json_file(self.root / "manifest.json", self.manifest)
        with self.assertRaisesRegex(ValueError, "Duplicate game"):
            ValueCache(self.root)

    def test_exact_coast(self):
        cache = ValueCache(self.root)
        spec = cache.spec["board_specification"]
        locations = spec["locations"]
        row = spec["location_board_rows"][locations.index("Spa")]
        path = self.root / "train" / "board.u8"
        board = np.frombuffer(path.read_bytes(), dtype="u1").copy().reshape(4, 75, 4)
        board[0, row, 2] = locations.index("SpaNC")
        path.write_bytes(board.tobytes())
        self.rehash("board.u8")
        self.assertEqual(ValueCache(self.root).splits["TRAIN"].board[0, row, 2],
                         locations.index("SpaNC"))
        board[0, row, 2] = locations.index("Spa")
        path.write_bytes(board.tobytes())
        self.rehash("board.u8")
        with self.assertRaisesRegex(ValueError, "Fleet location"):
            ValueCache(self.root)

    def test_weighted_metrics(self):
        cache = ValueCache(self.root)
        data = cache.splits["TRAIN"]
        predictions = data.targets[data.game_ids].copy()
        predictions[3] = [0, 0, 0, 0, 0, 1, 0]
        result = metrics(data, predictions, cache.spec["output_powers"])
        self.assertAlmostEqual(result["overall"]["game_mean_mse"],
                               2 * result["overall"]["mse"])
        self.assertIn("LABEL:None", result["communication"])
        self.assertEqual(result["communication"]["LABEL:None"]["mse"], 0)

    def test_learning_and_checkpoints(self):
        torch.set_num_threads(1)
        torch.manual_seed(7)
        cache = ValueCache(self.root)
        data = cache.splits["TRAIN"]
        model = ValueNetwork(cache.spec, hidden=16)
        board = torch.tensor(data.board.astype(np.int64))
        context = torch.tensor(data.context.astype(np.int64))
        target = torch.zeros((len(data), 7))
        target[:, 2] = 1
        initial = (model(board, context) - target).square().mean().item()
        optimizer = torch.optim.Adam(model.parameters(), lr=0.01)
        for _ in range(30):
            optimizer.zero_grad()
            loss = (model(board, context) - target).square().mean()
            loss.backward()
            optimizer.step()
        self.assertLess((model(board, context) - target).square().mean().item(), initial / 2)
        settings = Settings(epochs=2, hidden=16, batch_size=3, threads=1)
        output = self.root.parent / "model"
        with redirect_stdout(io.StringIO()):
            report = train(cache, output, settings)
        best = min(report["epochs"], key=lambda row: row["validation"]["overall"]["mse"])
        self.assertEqual(report["best_epoch"], best["epoch"])
        loaded, payload = load_model(output / "best.pt", cache.spec)
        predicted = predict(loaded, cache.splits["VALIDATION"], 2)
        self.assertTrue(np.isfinite(predicted).all())
        self.assertTrue(np.allclose(predicted.sum(1), 1))
        self.assertFalse(report["test_opened"])
        self.assertTrue((output / "training.json").is_file())
        with self.assertRaises(FileExistsError):
            train(cache, output, replace(settings, epochs=1))
        payload["state_dict"]["adjacency"][0, 0] += 1
        bad = self.root.parent / "bad.pt"
        torch.save(payload, bad)
        with self.assertRaisesRegex(ValueError, "Checkpoint map differs"):
            load_model(bad, cache.spec)


def main():
    global SPEC_BYTES
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    args = parser.parse_args()
    SPEC_BYTES = Path(args.spec).read_bytes()
    if hashlib.sha256(SPEC_BYTES).hexdigest() != SPEC_HASH:
        raise ValueError("Expected the verified value specification")
    result = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(ValueTests))
    if not result.wasSuccessful():
        raise SystemExit(1)


if __name__ == "__main__":
    main()