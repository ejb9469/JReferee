"""Self-play value admission, warm starts, selection and export regression checks."""

import argparse
from contextlib import ExitStack, redirect_stdout
from dataclasses import replace
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from uuid import UUID

from . import self_play_cache as reader
from . import self_play_value_data as data_api
from . import test_self_play_cache as fixtures


SPEC_BYTES = None
REAL_SPEC = False


def prepare_spec(path=None):
    global SPEC_BYTES, REAL_SPEC
    REAL_SPEC = path is not None
    if REAL_SPEC:
        SPEC_BYTES = Path(path).read_bytes()
        reader.require(reader.sha(SPEC_BYTES) == reader.VALUE_HASH, "Expected the frozen value specification")
    else:
        spec = json.loads(fixtures.synthetic_spec())
        board = spec["board_specification"]
        board.update(board_locations=[i for i in range(1, 82) if board["canonical_locations"][i] == i],
                     home_owners=[0] * 75)
        spec.update(year_origin=1901, year_scale=20, output_powers=list(reader.POWERS[1:]))
        SPEC_BYTES = (json.dumps(spec) + "\n").encode()


class FixtureCase(unittest.TestCase):
    def setUp(self):
        if SPEC_BYTES is None:
            prepare_spec()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(reader, "VALUE_HASH", reader.sha(SPEC_BYTES)))
        self.stack.enter_context(patch.object(fixtures, "SPEC_BYTES", SPEC_BYTES))
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory(prefix="jreferee-value-learn-")))
        self.path = self.make_cache("cache")
        self.data = data_api.ValueData([reader.SelfPlayCache(self.path)])

    def make_cache(self, name, serial=0, completed_train=True, only_train=False, split_seed=7,
                   train_positions=3, winner=2):
        root = fixtures.fixture(self.directory / name, only_train=only_train)
        games = [json.loads(line) for line in (root / "games.jsonl").read_text().splitlines()]
        manifest = json.loads((root / "manifest.json").read_bytes())
        value = json.loads((root / "value/manifest.json").read_bytes())
        board_spec = json.loads(SPEC_BYTES)["board_specification"]
        spa_row = board_spec["location_board_rows"][board_spec["locations"].index("Spa")]
        logical = serial * 100
        for index, game in enumerate(games):
            split = game["split"]
            while reader.split_for(f"{logical:064x}", split_seed) != split:
                logical += 1
            game["logical_key"] = f"{logical:064x}"
            logical += 1
            game["game_id"] = str(UUID(int=serial * 10 + index + 1))
            game["archive_sha256"] = reader.sha(f"value-stage-{serial}-{index}".encode())
            seat = game["configuration"]["seats"]["FRANCE"]
            seat["root"] = "COHERENT_GRU_HYBRID"
            seat["movement_models"] = {key: reader.sha(key.encode()) for key in ("anchor", "gru", "checkpoint", "dataset")}
            seat["value_model"] = {key: reader.sha(("value-" + key).encode()) for key in ("model", "checkpoint", "dataset")}
            game["configuration"]["seats"] = {power: dict(seat) for power in reader.POWERS[1:]}
            game["skipped_non_dil"], game["skipped_zero_units"] = 0, 12
            policy = root / "policy" / f"{split.lower()}-00000"
            board = bytearray((policy / "board.u8").read_bytes()[:300])
            board[spa_row * 4:spa_row * 4 + 3] = b"\0\0\0"
            (policy / "board.u8").write_bytes(board * 2)
            accepted = index == 1 or completed_train
            count = (train_positions if index == 0 else 2) if accepted else 0
            game.update(termination="SOLO" if accepted else "ARTIFICIAL_CUTOFF",
                        value_status="ACCEPTED" if accepted else "ARTIFICIAL_CUTOFF", value_positions=count)
            directory = root / "value" / split.lower()
            (directory / "board.u8").write_bytes(board * count)
            contexts = [1, 1901, 2, 1901, 3, 1901][:2 * count]
            fixtures.pack(directory / "context.i32", "i", contexts)
            fixtures.pack(directory / "targets.f32", "f", [int(i == winner) for i in range(7)] if count else [])
            fixtures.pack(directory / "game_offsets.i64", "q", [0, count] if count else [0])
            fixtures.write_lines(directory / "games.jsonl", [{
                "source": "JREFEREE_SELF_PLAY", "game_key": game["game_id"],
                "payload_sha256": game["archive_sha256"], "communication_type": "NO_PRESS",
                "start": 0, "end": count,
            }] if count else [])
            value["splits"][split].update(games=int(accepted), positions=count)
        manifest.update(split_seed=str(split_seed), value_positions=sum(game["value_positions"] for game in games))
        fixtures.write_lines(root / "games.jsonl", games)
        fixtures.write_json(root / "manifest.json", manifest)
        fixtures.write_json(root / "value/manifest.json", value)
        fixtures.reseal(root)
        return root


class DataTests(FixtureCase):
    def test_completed_game_targets_and_cutoff_exclusion(self):
        self.data.preflight()
        self.assertEqual(self.data.admission["TRAIN"]["positions"], 3)
        self.assertEqual(self.data.admission["VALIDATION"]["eligible_games"], 1)
        rows = self.data.examples["TRAIN"]
        self.assertEqual([row.context[0] for row in rows], [1, 2, 3])
        self.assertEqual({row.metadata["game_key"] for row in rows}, {self.data.caches[0].games[0]["game_id"]})
        cutoff = data_api.ValueData([reader.SelfPlayCache(self.make_cache("cutoff", completed_train=False))])
        self.assertEqual(cutoff.admission["TRAIN"]["cutoff_games"], 1)
        self.assertEqual(cutoff.examples["TRAIN"], ())
        with self.assertRaisesRegex(ValueError, "both TRAIN and VALIDATION"):
            cutoff.preflight()
        empty = data_api.ValueData([reader.SelfPlayCache(self.make_cache("no-validation", only_train=True))])
        with self.assertRaisesRegex(ValueError, "both TRAIN and VALIDATION"):
            empty.preflight()

    def test_multicache_identity_disjointness_and_whole_game_splits(self):
        first, second = self.data.caches[0], reader.SelfPlayCache(self.make_cache("second", serial=10))
        forward, reverse = data_api.ValueData([first, second]), data_api.ValueData([second, first])
        self.assertEqual(forward.fingerprint, reverse.fingerprint)
        self.assertEqual(forward.receipts(), reverse.receipts())
        keys = [{row.metadata["game_key"] for row in forward.examples[split]} for split in reader.SPLITS]
        self.assertEqual([len(group) for group in keys], [2, 2])
        self.assertFalse(keys[0] & keys[1])
        with self.assertRaisesRegex(ValueError, "Duplicate game across caches"):
            data_api.ValueData([first, first])
        different_seed = reader.SelfPlayCache(self.make_cache("different-seed", serial=20, split_seed=8))
        with self.assertRaisesRegex(ValueError, "Mixed split seeds"):
            data_api.ValueData([first, different_seed])

    def test_heterogeneous_match_seats_rejected(self):
        path = self.path / "games.jsonl"
        games = [json.loads(line) for line in path.read_text().splitlines()]
        games[0]["configuration"]["seats"]["AUSTRIA"]["movement_models"]["gru"] = "f" * 64
        fixtures.write_lines(path, games)
        fixtures.reseal(self.path)
        with self.assertRaisesRegex(ValueError, "homogeneous seven-seat"):
            data_api.ValueData([reader.SelfPlayCache(self.path)])

    def test_receipts_and_frozen_specification_cannot_drift(self):
        receipts = self.data.receipts()
        receipts[0]["cache_manifest_sha256"] = "f" * 64
        self.assertNotEqual(data_api.cohort(receipts, 7), self.data.fingerprint)
        with self.assertRaisesRegex(ValueError, "Unsorted or repeated"):
            data_api.cohort(receipts * 2, 7)
        (self.path / "value/specification.json").write_bytes(SPEC_BYTES + b" ")
        with self.assertRaisesRegex(ValueError, "frozen value specification"):
            data_api.ValueData(self.data.caches)

    def test_value_snapshot_does_not_reopen_inputs_or_test(self):
        (self.path / "value/test").mkdir()
        (self.path / "value/test/board.u8").write_bytes(b"TEST canary: never a training input")
        admitted = data_api.ValueData([reader.SelfPlayCache(self.path)])
        before = admitted.examples["VALIDATION"]
        (self.path / "value/validation/board.u8").write_bytes(b"changed after admission")
        with patch.object(Path, "open", side_effect=AssertionError("Unexpected input reread")):
            self.assertEqual(admitted.examples["VALIDATION"], before)
            admitted.preflight()
        with self.assertRaises(TypeError):
            before[0].metadata["game_key"] = "changed"


class NumericalTests(FixtureCase):
    def setUp(self):
        super().setUp()
        import numpy as np
        import torch
        from . import value_cache, value_train, value_model, value_export
        from . import self_play_value_model, self_play_value_train, self_play_value_export
        from .progress import Progress

        self.np, self.torch = np, torch
        self.base, self.model_api, self.train_api, self.export_api = (
            value_train, self_play_value_model, self_play_value_train, self_play_value_export)
        self.legacy_export, self.cache_api = value_export, value_cache
        for module in (value_cache, value_train, value_export, self_play_value_model,
                       self_play_value_train, self_play_value_export):
            self.stack.enter_context(patch.object(module, "SPEC_HASH", reader.sha(SPEC_BYTES)))
        if not REAL_SPEC:
            for module in (value_export, self_play_value_export):
                self.stack.enter_context(patch.object(module, "synthetic",
                    lambda spec: [(self.data.splits()["TRAIN"].board[0], self.data.splits()["TRAIN"].context[0])]))
        torch.set_num_threads(1)
        torch.manual_seed(7)
        self.model = value_model.ValueNetwork(self.data.spec, hidden=16)
        self.initial = self.directory / "initial.pt"
        self.save_initial()
        self.settings = self_play_value_train.Settings(epochs=2, batch_size=2, learning_rate=0.01, threads=1)
        self.progress = Progress(False)

    def save_initial(self, zero=False):
        if zero:
            with self.torch.no_grad():
                for parameter in self.model.parameters():
                    parameter.zero_()
        self.base.save_model(self.initial, self.model, self.cache_api.ValueCache(self.path / "value"), 1)

    def fit(self, name, data=None, settings=None):
        output = self.directory / name
        with redirect_stdout(io.StringIO()):
            report = self.train_api.fit(self.data if data is None else data, self.initial, output,
                                        self.settings if settings is None else settings, self.progress)
        return output, report

    def test_game_offsets_position_weighting_and_immutable_arrays(self):
        second = reader.SelfPlayCache(self.make_cache("shorter", serial=10, train_positions=2, winner=3))
        data = data_api.ValueData([*self.data.caches, second])
        split = data.splits()["TRAIN"]
        self.assertEqual(sorted(self.np.diff(split.offsets).tolist()), [2, 3])
        self.np.testing.assert_allclose(self.base.training_mean(split), [0, 0, 0.6, 0.4, 0, 0, 0])
        predicted = self.np.zeros((len(split), 7))
        predicted[:, 2] = 1
        result = self.base.metrics(split, predicted, data.spec["output_powers"])["overall"]
        self.assertAlmostEqual(result["mse"], 4 / 35)
        self.assertAlmostEqual(result["game_mean_mse"], 1 / 7)
        with self.assertRaises(ValueError):
            split.board.setflags(write=True)
        for game, row in enumerate(split.metadata):
            self.assertEqual(row["start"], split.offsets[game])
            self.assertEqual(row["end"], split.offsets[game + 1])

    def test_warm_start_and_verify_preserve_predictions_without_outputs(self):
        split = self.data.splits()["VALIDATION"]
        original = self.base.predict(self.model, split, 2)
        loaded, initial = self.model_api.load_start(self.initial, self.data)
        self.np.testing.assert_array_equal(original, self.base.predict(loaded, split, 2))
        self.assertEqual(initial["sha256"], reader.sha(self.initial.read_bytes()))
        before = sorted(path.name for path in self.directory.iterdir())
        with redirect_stdout(io.StringIO()):
            result = self.train_api.verify(self.data, self.initial, self.settings, self.progress)
        self.assertEqual(result["initial_value"], initial)
        self.assertEqual(before, sorted(path.name for path in self.directory.iterdir()))

    def test_improvement_selection_reloads_checkpoint_and_refuses_overwrite(self):
        self.save_initial(zero=True)
        output, report = self.fit("improved")
        self.assertTrue(report["complete"])
        self.assertGreater(report["best_epoch"], 0)
        self.assertLess(report["best_validation_mse"], report["initial_validation"]["overall"]["mse"])
        loaded, payload, digest = self.model_api.load_model(output / "best.pt", self.data.spec)
        self.assertEqual(digest, report["checkpoint_sha256"])
        self.assertEqual(payload["epoch"], report["best_epoch"])
        self.assertEqual(report["selected_validation"], self.train_api.evaluate(
            loaded, self.data.splits()["VALIDATION"], self.data, self.settings))
        self.assertEqual(sum(row["optimizer_steps"] for row in report["epochs"]), 4)
        for key in ("test_opened", "optimizer_resumed", "policy_updated", "anchor_updated", "promotion_performed"):
            self.assertFalse(report[key])
        with self.assertRaises(FileExistsError):
            self.fit("improved")

    def test_ties_and_regression_keep_epoch_zero_and_allow_value_warm_start(self):
        self.save_initial(zero=True)
        for name, learning_rate, wrong_target in (("tie", 1e-20, False), ("worse", 0.01, True)):
            with self.subTest(name=name):
                if wrong_target:
                    fixtures.pack(self.path / "value/validation/targets.f32", "f", [0, 0, 0, 1, 0, 0, 0])
                    fixtures.reseal(self.path)
                data = data_api.ValueData([reader.SelfPlayCache(self.path)])
                output, report = self.fit(name, data, replace(self.settings, learning_rate=learning_rate))
                self.assertEqual(report["best_epoch"], 0)
                self.assertFalse(report["improved_over_initial"])
                loaded, initial = self.model_api.load_start(output / "best.pt", data)
                self.assertEqual(initial["epoch"], 0)
                for key, value in self.model.state_dict().items():
                    self.assertTrue(self.torch.equal(value, loaded.state_dict()[key]))
                if wrong_target:
                    self.assertGreater(report["epochs"][-1]["validation"]["overall"]["mse"],
                                       report["initial_validation"]["overall"]["mse"])
                mismatch = data_api.ValueData([reader.SelfPlayCache(
                    self.make_cache(name + "-seed", serial=20, split_seed=8))])
                with self.assertRaisesRegex(ValueError, "split seed mismatch"):
                    self.model_api.load_start(output / "best.pt", mismatch)

    def test_checkpoint_map_tensor_cohort_and_legacy_epoch_guards(self):
        output, _ = self.fit("guards")
        _, original, _ = self.model_api.load_model(output / "best.pt", self.data.spec)
        for mutation, message in (
            (lambda p: p["state_dict"]["adjacency"].fill_(0), "Checkpoint map differs"),
            (lambda p: p["state_dict"]["head.2.bias"].fill_(float("nan")), "Invalid checkpoint tensor"),
            (lambda p: p.update(dataset_cohort_sha256="f" * 64), "Value cohort mismatch"),
        ):
            payload = {**original, "state_dict": {key: value.clone() for key, value in original["state_dict"].items()}}
            mutation(payload)
            path = self.directory / "bad.pt"
            self.torch.save(payload, path)
            with self.assertRaisesRegex(ValueError, message):
                self.model_api.load_model(path, self.data.spec)
        legacy = self.torch.load(self.initial, weights_only=True)
        legacy["epoch"] = 0
        path = self.directory / "legacy-zero.pt"
        self.torch.save(legacy, path)
        with self.assertRaisesRegex(ValueError, "checkpoint epoch"):
            self.model_api.load_start(path, self.data)

    def test_multicache_export_header_epoch_zero_and_receipt_binding(self):
        self.save_initial(zero=True)
        other = reader.SelfPlayCache(self.make_cache("export-cache", serial=10))
        data = data_api.ValueData([*self.data.caches, other])
        output, _ = self.fit("export-fit", data, replace(self.settings, learning_rate=1e-20))
        exported = self.directory / "java"
        with redirect_stdout(io.StringIO()):
            report = self.export_api.export(data, output / "best.pt", output / "training.json", exported, 4, 1)
        self.assertEqual(report["epoch"], 0)
        self.assertFalse(report["java_parity_verified"])
        self.assertEqual({row["dataset_fingerprint"] for row in report["validation_references"]},
                         {cache.fingerprint for cache in data.caches})
        raw = (exported / "model.bin").read_bytes()
        self.assertEqual(raw[-32:], bytes.fromhex(reader.sha(raw[:-32])))
        stream = io.BytesIO(raw[:-32])
        self.assertEqual(stream.read(8), b"JREFSVL1")
        for expected in (self.export_api.EXPORT_SCHEMA, self.model_api.MODEL_SCHEMA, reader.REWARD, data.spec_text):
            length, = struct.unpack(">i", stream.read(4))
            self.assertEqual(stream.read(length).decode(), expected)
        for expected in (reader.sha(SPEC_BYTES), report["checkpoint_sha256"], data.fingerprint,
                         reader.sha((output / "training.json").read_bytes()), report["initial_value"]["sha256"]):
            self.assertEqual(stream.read(32).hex(), expected)
        self.assertEqual(struct.unpack(">qi", stream.read(12)), (7, 2))
        for receipt in data.receipts():
            self.assertEqual(stream.read(32).hex(), receipt["dataset_fingerprint"])
            self.assertEqual(stream.read(32).hex(), receipt["cache_manifest_sha256"])
        self.assertEqual(struct.unpack(">ii", stream.read(8)), (0, 16))
        with self.assertRaises(FileExistsError):
            self.export_api.export(data, output / "best.pt", output / "training.json", exported)
        changed = json.loads((output / "training.json").read_bytes())
        changed["checkpoint_sha256"] = "f" * 64
        fixtures.write_json(output / "training.json", changed)
        with self.assertRaisesRegex(ValueError, "checkpoint/report mismatch"):
            self.export_api.export(data, output / "best.pt", output / "training.json", self.directory / "bad-export")
        self.assertFalse((self.directory / "bad-export").exists())

    def test_supervised_export_retains_its_original_header(self):
        cache = self.cache_api.ValueCache(self.path / "value")
        trained, exported = self.directory / "legacy-fit", self.directory / "legacy-java"
        with redirect_stdout(io.StringIO()):
            report = self.base.train(cache, trained, self.base.Settings(epochs=1, hidden=16, batch_size=2, threads=1))
            receipt = self.legacy_export.export(trained / "best.pt", trained / "training.json", cache.root, exported, 2)
        self.assertEqual(report["best_epoch"], 1)
        self.assertEqual(receipt["schema"], "jreferee-value-java-v1")
        stream = io.BytesIO((exported / "model.bin").read_bytes())
        self.assertEqual(stream.read(8), b"JREFVAL1")
        for _ in range(4):
            length, = struct.unpack(">i", stream.read(4))
            stream.read(length)
        for expected in (reader.sha(SPEC_BYTES), receipt["checkpoint_sha256"], cache.fingerprint, cache.manifest_hash):
            self.assertEqual(stream.read(32).hex(), expected)
        self.assertEqual(struct.unpack(">ii", stream.read(8)), (1, 16))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path)
    parser.add_argument("--data-only", action="store_true")
    args = parser.parse_args()
    prepare_spec(args.spec)
    classes = [DataTests] if args.data_only else [DataTests, NumericalTests]
    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(case) for case in classes)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
    if not REAL_SPEC:
        print("Synthetic fixture with test-only hashes; production specification checks are unchanged.")


if __name__ == "__main__":
    main()