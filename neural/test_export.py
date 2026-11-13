"""Export identity, tensor bytes, deterministic fixtures and split isolation."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

import numpy as np
import torch

from . import test_neural as fixtures
from .cache import Cache, SPEC_HASH, digest
from .export import EXPORT_SCHEMA, export
from .model import MODEL_SCHEMA, MovementGRU
from .train import save_policy


class ExportTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.manual_seed(7)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.cache = self.root / "cache"
        fixtures.write_cache(self.cache, {
            split: [fixtures.example(i, split) for i in range(6)] for split in fixtures.SPLITS})
        self.model = MovementGRU(fixtures.SPEC, 16)
        self.checkpoint = self.root / "policy.pt"
        with Cache(self.cache) as cache:
            save_policy(self.checkpoint, self.model, cache, 1)

    def tearDown(self):
        self.temporary.cleanup()

    def test_bytes_and_repeatability(self):
        report = export(self.checkpoint, self.root / "first", self.cache, 4)
        export(self.checkpoint, self.root / "second", self.cache, 4)
        self.assertTrue(report["complete"])
        self.assertEqual(report["validation_cases"], 4)
        self.assertFalse(report["test_evaluated"])
        self.assertEqual(report["checkpoint_sha256"], digest(self.checkpoint))
        for name in ("model.bin", "parity.bin"):
            raw = (self.root / "first" / name).read_bytes()
            self.assertEqual(raw, (self.root / "second" / name).read_bytes())
            self.assertEqual(hashlib.sha256(raw[:-32]).digest(), raw[-32:])
            self.assertEqual(report["files"][name]["sha256"], digest(self.root / "first" / name))
        stream = io.BytesIO((self.root / "first" / "model.bin").read_bytes()[:-32])

        def integer():
            return struct.unpack(">i", stream.read(4))[0]

        def text():
            return stream.read(integer()).decode()

        self.assertEqual(stream.read(8), b"JREFGRU1")
        self.assertEqual(text(), EXPORT_SCHEMA)
        self.assertEqual(text(), MODEL_SCHEMA)
        self.assertEqual(text(), fixtures.SPEC_RAW.decode())
        self.assertEqual(stream.read(32).hex(), SPEC_HASH)
        self.assertEqual(stream.read(32).hex(), digest(self.checkpoint))
        stream.read(64)
        self.assertEqual((integer(), integer()), (1, 16))
        parameters = list(self.model.named_parameters())
        self.assertEqual(integer(), len(parameters))
        for name, value in parameters:
            self.assertEqual(text(), name)
            self.assertEqual(tuple(integer() for _ in range(integer())), tuple(value.shape))
            actual = np.frombuffer(stream.read(value.numel() * 4), dtype=">f4").reshape(value.shape)
            np.testing.assert_array_equal(actual, value.detach().numpy())
        self.assertEqual(stream.read(), b"")
        with self.assertRaises(FileExistsError):
            export(self.checkpoint, self.root / "first")

    def test_only_validation_payloads(self):
        for split in ("train", "test"):
            (self.cache / f"{split}-00000" / "options.u8").write_bytes(b"must remain unopened")
        report = export(self.checkpoint, self.root / "selected", self.cache)
        self.assertEqual(report["validation_cases"], 6)
        (self.cache / "validation-00000" / "options.u8").write_bytes(b"invalid")
        with self.assertRaises(ValueError):
            export(self.checkpoint, self.root / "bad", self.cache)
        self.assertFalse((self.root / "bad").exists())

    def test_incompatible_inputs(self):
        manifest_path = self.cache / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["dataset_index_sha256"] = "e" * 64
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "differ"):
            export(self.checkpoint, self.root / "mismatch", self.cache)
        self.assertFalse((self.root / "mismatch").exists())
        payload = torch.load(self.checkpoint, weights_only=True)
        payload["state_dict"]["gru.weight_hh"][0, 0] = float("nan")
        torch.save(payload, self.root / "invalid.pt")
        with self.assertRaises(ValueError):
            export(self.root / "invalid.pt", self.root / "invalid")
        self.assertFalse((self.root / "invalid").exists())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=Path("data/movement-neural-cache-v1/specification.json"))
    args = parser.parse_args()
    fixtures.SPEC_RAW = args.spec.read_bytes()
    if hashlib.sha256(fixtures.SPEC_RAW).hexdigest() != SPEC_HASH:
        raise ValueError("Self-check needs the supported Java-generated specification")
    fixtures.SPEC = fixtures.parse_json(fixtures.SPEC_RAW)
    unittest.main(argv=["neural.test_export"], verbosity=2)


if __name__ == "__main__":
    main()