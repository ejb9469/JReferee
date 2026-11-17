"""Typed export bytes, provenance guards and selected-checkpoint compatibility."""

import argparse
from contextlib import redirect_stdout
from copy import deepcopy
from dataclasses import replace
import io
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import patch

from . import self_play_cache as reader
from . import test_self_play_train as fixtures


class ExportTests(fixtures.FixtureCase):
    def setUp(self):
        super().setUp()
        import torch
        from . import self_play_export as exporter
        from . import self_play_model as model_api
        from . import self_play_train as trainer
        self.torch, self.exporter, self.model_api, self.trainer = torch, exporter, model_api, trainer
        for module in (exporter, model_api, trainer):
            context = patch.object(module, "SPEC_HASH", reader.sha(fixtures.MOVEMENT_RAW))
            context.start()
            self.addCleanup(context.stop)
        torch.set_num_threads(1)
        torch.manual_seed(7)
        model = model_api.TypeConditionedGRU(self.spec, 16)
        with torch.no_grad():
            values = torch.arange(model.type_projection.weight.numel(), dtype=torch.float32)
            model.type_projection.weight.copy_((values * 0.37).sin().reshape_as(model.type_projection.weight) * 0.2)
        self.initial = self.directory / "initial.pt"
        model_api.save_typed_policy(
            self.initial, model, self.data, fixtures.MOVEMENT_RAW.decode(),
            {"sha256": reader.sha(b"synthetic supervised ancestor"),
             "schema": model_api.BASE_SCHEMA, "epoch": 1}, 0, 1.0)
        self.fitted = self.directory / "fit"
        self.settings = trainer.Settings(
            epochs=1, batch_size=2, candidate_batch_size=2, max_options=8, threads=1)
        with redirect_stdout(io.StringIO()):
            trainer.fit(self.data, self.initial, self.fitted, self.settings)
        self.checkpoint = self.fitted / "policy.pt"
        self.training_path = self.fitted / "training.json"

    def export(self, name):
        return self.exporter.export(
            self.checkpoint, self.training_path, [self.path], self.anchor_path, self.directory / name, 2)

    def test_tensor_bytes_and_repeatability(self):
        import numpy as np
        first, second = self.export("first"), self.export("second")
        self.assertEqual(first, second)
        self.assertEqual((first["synthetic_cases"], first["validation_cases"]), (40, 2))
        self.assertFalse(first["test_evaluated"])
        self.assertFalse(first["java_parity_verified"])
        for name in ("model.bin", "parity.bin"):
            raw = (self.directory / "first" / name).read_bytes()
            self.assertEqual(raw, (self.directory / "second" / name).read_bytes())
            self.assertEqual(bytes.fromhex(reader.sha(raw[:-32])), raw[-32:])
        stream = io.BytesIO((self.directory / "first/model.bin").read_bytes()[:-32])

        def integer():
            return struct.unpack(">i", stream.read(4))[0]

        def text():
            return stream.read(integer()).decode()

        model, payload, checkpoint_hash = self.model_api.load_typed_policy(self.checkpoint, self.spec)
        self.assertEqual(stream.read(8), b"JREFTGR1")
        self.assertEqual(text(), self.exporter.EXPORT_SCHEMA)
        self.assertEqual(text(), self.model_api.MODEL_SCHEMA)
        self.assertEqual(text(), fixtures.MOVEMENT_RAW.decode())
        self.assertEqual(json.loads(text()), fixtures.data_api.type_specification())
        for value in (reader.sha(fixtures.MOVEMENT_RAW), checkpoint_hash, first["dataset_cohort_sha256"],
                      reader.sha(self.training_path.read_bytes()), self.anchor_hash):
            self.assertEqual(stream.read(32).hex(), value)
        self.assertEqual(struct.unpack(">q", stream.read(8))[0], self.data.split_seed)
        self.assertEqual(integer(), len(self.data.receipts()))
        receipt_bytes = stream.read(64 * len(self.data.receipts()))
        self.assertEqual(reader.sha(self.exporter.COHORT_PREFIX + receipt_bytes), first["dataset_cohort_sha256"])
        self.assertEqual((integer(), integer()), (payload["epoch"], model.hidden))
        self.assertEqual(integer(), len(list(model.named_parameters())))
        for name, parameter in model.named_parameters():
            self.assertEqual(text(), name)
            self.assertEqual(tuple(integer() for _ in range(integer())), tuple(parameter.shape))
            actual = np.frombuffer(stream.read(parameter.numel() * 4), dtype=">f4").reshape(parameter.shape)
            np.testing.assert_array_equal(actual, parameter.detach().numpy())
        self.assertEqual(stream.read(), b"")
        self.assertGreater(self.torch.count_nonzero(model.type_projection.weight).item(), 0)
        with self.assertRaises(FileExistsError):
            self.export("first")

    def test_receipt_mismatches_fail_before_output(self):
        original = json.loads(self.training_path.read_text())
        changes = (
            ("complete", False), ("checkpoint_sha256", "0" * 64),
            ("best_epoch", 9999), ("anchor_sha256", "0" * 64),
            ("self_play_split_seed", "8"), ("test_evaluated", True),
            ("best_validation_cross_entropy", 9999.0),
        )
        for key, value in changes:
            with self.subTest(key=key):
                changed = deepcopy(original)
                changed[key] = value
                self.training_path.write_text(json.dumps(changed), encoding="utf-8")
                with self.assertRaises(ValueError):
                    self.export("rejected")
                self.assertFalse((self.directory / "rejected").exists())
        changed = deepcopy(original)
        changed["datasets"][0]["cache_manifest_sha256"] = "0" * 64
        self.training_path.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.export("rejected")
        self.assertFalse((self.directory / "rejected").exists())

    def test_anchor_cache_and_tensors_fail_before_output(self):
        from .export import export as legacy_export
        with self.assertRaises(ValueError):
            legacy_export(self.checkpoint, self.directory / "legacy")
        self.assertFalse((self.directory / "legacy").exists())
        with self.assertRaises(ValueError):
            self.exporter.export(self.checkpoint, self.training_path, [self.path, self.path],
                                 self.anchor_path, self.directory / "duplicate", 2)
        self.assertFalse((self.directory / "duplicate").exists())
        self.anchor_path.write_bytes(b"a different frozen anchor")
        with self.assertRaises(ValueError):
            self.export("wrong-anchor")
        self.assertFalse((self.directory / "wrong-anchor").exists())
        self.anchor_path.write_bytes(b"synthetic immutable anchor identity")
        payload = self.torch.load(self.checkpoint, weights_only=True)
        payload["state_dict"]["type_projection.weight"][0, 0] = float("nan")
        self.torch.save(payload, self.checkpoint)
        with self.assertRaisesRegex(ValueError, "tensor"):
            self.export("invalid-tensor")
        self.assertFalse((self.directory / "invalid-tensor").exists())

    def test_epoch_zero_remains_exportable(self):
        tied = self.directory / "tied"
        with redirect_stdout(io.StringIO()):
            _, report = self.trainer.fit(
                self.data, self.initial, tied, replace(self.settings, learning_rate=1e-30, weight_decay=0))
        self.assertEqual(report["best_epoch"], 0)
        exported = self.exporter.export(tied / "policy.pt", tied / "training.json",
                                        [self.path], self.anchor_path, self.directory / "epoch-zero", 1)
        self.assertEqual(exported["epoch"], 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True, help="Frozen Java value specification from the self-play cache")
    args = parser.parse_args()
    fixtures.prepare_spec(args.spec)
    unittest.main(argv=["neural.test_self_play_export"], verbosity=2)


if __name__ == "__main__":
    main()