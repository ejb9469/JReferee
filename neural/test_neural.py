"""Self-checks using a generated specification and small synthetic caches."""

import argparse
import copy
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

from .cache import Cache, Example, FILES, SPEC_HASH, SPLITS, digest, parse_json, shapes
from .model import Batch, MovementGRU, coordination, sampled_actions, segment_argmax, segment_log_softmax
from .train import Settings, evaluate, fit, load_policy, save_policy


SPEC_RAW = b""
SPEC = {}


def example(number, split="TRAIN", fleet=False):
    locations = {name: i for i, name in enumerate(SPEC["locations"])}
    board = np.zeros((75, 4), dtype=np.uint8)
    slots, choices = [], []
    formations = [("MAO", 2, [("HOLD", "NONE", "NONE"), ("MOVE", "SpaNC", "NONE"), ("MOVE", "SpaSC", "NONE")])] if fleet else [
        ("Gas", 1, [("HOLD", "NONE", "NONE"), ("MOVE", "Bur", "NONE"), ("SUPPORT", "Par", "Bur")]),
        ("Par", 1, [("HOLD", "NONE", "NONE"), ("MOVE", "Bur", "NONE"), ("SUPPORT", "Gas", "Bur")])]
    for origin, kind, orders in formations:
        location = locations[origin]
        row = SPEC["location_board_rows"][location]
        board[row, :3] = kind, 3, location
        slots.append(location)
        for order, target, auxiliary in orders:
            choices.append([kind, location, SPEC["order_types"].index(order), locations[target], locations[auxiliary]])
    board[SPEC["location_board_rows"][locations["Par"]], 3] = 3
    found = -1
    for value in range(100000):
        key = f"fixture-{value}"
        bucket = int(hashlib.sha256(f"7\nDIPLOBN\n{key}".encode()).hexdigest()[:16], 16) % 100
        actual = "TRAIN" if bucket < 80 else "VALIDATION" if bucket < 90 else "TEST"
        if actual == split:
            found += 1
            if found == number:
                break
    else:
        raise AssertionError("Could not allocate fixture identity")
    return Example(board, np.array([3, 2, 1902], dtype=np.int32), np.array(slots, dtype=np.uint8),
                   np.full(len(slots), 3, dtype=np.int64), np.asarray(choices, dtype=np.uint8),
                   np.array([number % 3] if fleet else ([1, 2] if number % 2 == 0 else [2, 1]), dtype=np.int64),
                   {"source": "DIPLOBN", "game_key": key, "communication_type": [None, "Full", "None"][number % 3]})


def write_cache(root, data):
    root.mkdir()
    (root / "specification.json").write_bytes(SPEC_RAW)
    manifest = {
        "schema": "jreferee-neural-cache-v1", "complete": True, "encoding": "jreferee-neural-movement-v1",
        "dataset_index_sha256": "d" * 64, "byte_order": "LITTLE_ENDIAN", "test_evaluated": False,
        "filter": {"split_seed": 7, "communication_types": None, "include_unknown_communication": True},
        "settings": {"shard_examples": 2, "shard_options": 1000000},
        "specification": {"name": "specification.json", "bytes": len(SPEC_RAW), "sha256": SPEC_HASH}, "splits": [],
    }
    for split in SPLITS:
        examples = data[split]
        summary = dict(split=split, examples=len(examples), slots=sum(len(e.locations) for e in examples),
                       options=sum(len(e.options) for e in examples),
                       max_units=max((len(e.locations) for e in examples), default=0),
                       max_options_per_slot=max((int(e.counts.max()) for e in examples), default=0),
                       source_sha256="a" * 64, read_nanos=0, elapsed_nanos=0, bytes=0, shards=[])
        for offset in range(0, len(examples), 2):
            rows = examples[offset:offset + 2]
            name = f"{split.lower()}-{offset // 2:05d}"
            directory = root / name
            directory.mkdir()
            arrays = {
                "board.u8": np.stack([e.board for e in rows]), "context.i32": np.stack([e.context for e in rows]),
                "example_offsets.u64": np.concatenate(([0], np.cumsum([len(e.locations) for e in rows]))),
                "slot_locations.u8": np.concatenate([e.locations for e in rows]),
                "option_offsets.u64": np.concatenate(([0], np.cumsum(np.concatenate([e.counts for e in rows])))),
                "options.u8": np.concatenate([e.options for e in rows]), "targets.i32": np.concatenate([e.targets for e in rows]),
            }
            for filename, array in arrays.items():
                np.asarray(array, dtype=FILES[filename]).tofile(directory / filename)
            (directory / "metadata.jsonl").write_text("".join(json.dumps(e.metadata) + "\n" for e in rows), encoding="utf-8")
            files = [{"name": filename, "bytes": (directory / filename).stat().st_size,
                      "sha256": digest(directory / filename)} for filename in list(FILES) + ["metadata.jsonl"]]
            summary["bytes"] += sum(f["bytes"] for f in files)
            summary["shards"].append(dict(directory=name, examples=len(rows), slots=sum(len(e.locations) for e in rows),
                                          options=sum(len(e.options) for e in rows), files=files))
        manifest["splits"].append(summary)
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


class NeuralTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.manual_seed(7)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.path = self.root / "cache"
        self.data = {split: [example(i, split, fleet=i == 5) for i in range(6)] for split in SPLITS}
        write_cache(self.path, self.data)

    def tearDown(self):
        self.temporary.cleanup()

    def test_reader_and_batches(self):
        with Cache(self.path) as cache:
            for shard_id, (split, shard) in enumerate(cache.shards):
                first = int(shard["directory"].split("-")[-1]) * 2
                for row in range(shard["examples"]):
                    got, wanted = cache.get(shard_id, row), self.data[split][first + row]
                    for field in ("board", "context", "locations", "counts", "options", "targets"):
                        np.testing.assert_array_equal(getattr(got, field), getattr(wanted, field))
            kept = cache.get(0, 0)
            for shard in range(len(cache.shards)):
                cache.get(shard, 0)
            self.assertLessEqual(len(cache.maps), 2)
            np.testing.assert_array_equal(kept.options, self.data["TRAIN"][0].options)
            orders = []
            for seed in (7, 7, 8):
                groups = list(cache.batches("TRAIN", 2, 9, seed, 4))
                self.assertTrue(all(len(group) <= 2 and sum(len(e.options) for e in group) <= 9 for group in groups))
                keys = [e.metadata["game_key"] for group in groups for e in group]
                self.assertEqual(set(keys), {e.metadata["game_key"] for e in self.data["TRAIN"]})
                self.assertEqual(len(keys), 6)
                orders.append(keys)
            self.assertEqual(orders[0], orders[1])
            self.assertNotEqual(orders[0], orders[2])
            with self.assertRaises(ValueError):
                list(cache.batches("TRAIN", max_options=1))
            with self.assertRaises(ValueError):
                list(cache.batches("TEST"))
            cache.get(0, 0)
            mappings = [array._mmap for mapped in cache.maps.values() for array in mapped.arrays.values()]
            self.assertTrue(mappings)
            # Windows requires mappings to be closed before truncating a file.
            cache.close()
            self.assertTrue(all(mapping.closed for mapping in mappings))
            self.assertFalse(cache.maps)
            file = self.path / "train-00000" / "targets.i32"
            file.write_bytes(file.read_bytes() + b"\0")
            with self.assertRaisesRegex(ValueError, "changed"):
                cache.get(0, 0)

    def test_corruptions(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            parse_json('{"a":1,"a":2}')
        with self.assertRaises(ValueError):
            parse_json('{"a":NaN}')
        mutations = [
            ("targets.i32", lambda a: a.__setitem__(0, 3)),
            ("example_offsets.u64", lambda a: a.__setitem__(1, 0)),
            ("option_offsets.u64", lambda a: a.__setitem__(1, 0)),
            ("options.u8", lambda a: a.__setitem__((0, 2), 9)),
            ("options.u8", lambda a: a.__setitem__((2, 3), 0)),
            ("slot_locations.u8", lambda a: a.__setitem__(0, 0)),
            ("context.i32", lambda a: a.__setitem__((0, 1), 3)),
            ("board.u8", lambda a: a.__setitem__((0, 0, 3), 3)),
        ]
        for index, (name, mutate) in enumerate(mutations):
            with self.subTest(file=name, case=index):
                path = self.root / f"bad-{index}"
                write_cache(path, self.data)
                manifest = json.loads((path / "manifest.json").read_text())
                shard = manifest["splits"][0]["shards"][0]
                file = path / shard["directory"] / name
                values = np.fromfile(file, dtype=FILES[name]).reshape(shapes(shard)[name])
                mutate(values)
                values.tofile(file)
                next(f for f in shard["files"] if f["name"] == name)["sha256"] = digest(file)
                (path / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    Cache(path)
        file = self.path / "train-00000" / "options.u8"
        file.write_bytes(b"broken")
        with self.assertRaisesRegex(ValueError, "checksum"):
            Cache(self.path)

    def test_identity_filter_and_specification(self):
        bad = copy.deepcopy(self.data)
        bad["TRAIN"][1].metadata["game_key"] = bad["TRAIN"][0].metadata["game_key"]
        write_cache(self.root / "duplicate", bad)
        with self.assertRaisesRegex(ValueError, "Duplicate decision"):
            Cache(self.root / "duplicate")
        bad = {**self.data, "TRAIN": self.data["VALIDATION"]}
        write_cache(self.root / "wrong-split", bad)
        with self.assertRaisesRegex(ValueError, "wrong split"):
            Cache(self.root / "wrong-split")
        manifest_path = self.path / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["filter"]["include_unknown_communication"] = False
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "filter"):
            Cache(self.path)
        (self.path / "specification.json").write_bytes(SPEC_RAW + b" ")
        with self.assertRaisesRegex(ValueError, "Specification"):
            Cache(self.path)

    def test_empty_split(self):
        path = self.root / "empty"
        write_cache(path, {**self.data, "VALIDATION": []})
        with Cache(path) as cache:
            self.assertEqual(list(cache.batches("VALIDATION")), [])
            with self.assertRaises(ValueError):
                fit(cache, self.root / "empty-model", Settings(hidden=16, threads=1))
        self.assertFalse((self.root / "empty-model").exists())

    def test_probability_and_causality(self):
        examples = [example(0), example(1), example(5, fleet=True)]
        batch = Batch.make(examples)
        model = MovementGRU(SPEC, 16)
        output = model(batch)
        teacher_actions, _ = sampled_actions(batch, [
            (logp, segment_argmax(logp, step.counts)) for step, (logp, _) in zip(batch.steps, output)])
        greedy_actions, _ = sampled_actions(batch, model.decode(batch, model.prepare(batch), "greedy"))
        self.assertEqual([a == e.targets.tolist() for a, e in zip(teacher_actions, examples)],
                         [a == e.targets.tolist() for a, e in zip(greedy_actions, examples)])
        for step, (logp, _) in zip(batch.steps, output):
            mass = torch.segment_reduce(logp.exp(), "sum", lengths=step.counts)
            torch.testing.assert_close(mass, torch.ones_like(mass))
        changed = replace(examples[0], targets=np.array([1, 1]))
        other = model(Batch.make([changed, *examples[1:]]))
        for (a, _), (b, _) in zip(output, other):
            torch.testing.assert_close(a, b, rtol=0, atol=0)
        changed = replace(examples[0], targets=np.array([2, 2]))
        other = model(Batch.make([changed, *examples[1:]]))
        torch.testing.assert_close(output[0][0], other[0][0], rtol=0, atol=0)
        self.assertFalse(torch.equal(output[1][0], other[1][0]))
        loss = -sum(logp[step.targets].sum() for step, (logp, _) in zip(batch.steps, output))
        loss.backward()
        self.assertTrue(all(torch.isfinite(p.grad).all() for p in model.parameters() if p.grad is not None))
        scores = torch.tensor([1000., -1000., 1., 2.], requires_grad=True)
        logp = segment_log_softmax(scores, torch.tensor([2, 2]))
        self.assertTrue(torch.isfinite(logp).all())
        logp.sum().backward()
        self.assertTrue(torch.isfinite(scores.grad).all())
        self.assertEqual(segment_argmax(torch.zeros(4), torch.tensor([2, 2])).tolist(), [0, 2])
        empty = replace(examples[0], board=np.zeros((75, 4), dtype=np.uint8),
                        locations=np.empty(0, dtype=np.uint8), counts=np.empty(0, dtype=np.int64),
                        options=np.empty((0, 5), dtype=np.uint8), targets=np.empty(0, dtype=np.int64))
        self.assertEqual(model(Batch.make([empty])), [])

    def test_joint_learning_and_persistence(self):
        examples = [example(0), example(1)]
        batch = Batch.make(examples)
        model = MovementGRU(SPEC, 32)
        optimizer = torch.optim.AdamW(model.parameters(), lr=0.01, weight_decay=0)
        for _ in range(180):
            optimizer.zero_grad()
            output = model(batch)
            loss = -sum(logp[step.targets].sum() for step, (logp, _) in zip(batch.steps, output)) / 2
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1, error_if_nonfinite=True)
            optimizer.step()
        self.assertLess(loss.item(), 0.85)
        with torch.no_grad():
            prepared = model.prepare(batch)
            generator = torch.Generator().manual_seed(17)
            bad = 0
            for _ in range(50):
                actions, fields = sampled_actions(batch, model.decode(batch, prepared, "sample", generator))
                bad += sum(action not in ([1, 2], [2, 1]) for action in actions)
                self.assertTrue(all(len(order) == 2 for order in fields))
            self.assertLess(bad, 5)
        with Cache(self.path) as cache:
            save_policy(self.root / "policy.pt", model, cache, 1)
            loaded, _ = load_policy(self.root / "policy.pt", SPEC)
            for (a, _), (b, _) in zip(model(batch), loaded(batch)):
                torch.testing.assert_close(a, b, rtol=0, atol=0)
            payload = torch.load(self.root / "policy.pt", weights_only=True)
            payload["state_dict"]["rows"][1] = 75
            torch.save(payload, self.root / "bad-policy.pt")
            with self.assertRaisesRegex(ValueError, "Checkpoint map"):
                load_policy(self.root / "bad-policy.pt", SPEC)
        loc = {name: i for i, name in enumerate(SPEC["locations"])}
        fields = [[2, loc["NTH"], 4, loc["Yor"], loc["Bel"]], [1, loc["Yor"], 1, 0, 0]]
        self.assertEqual(coordination(fields, SPEC["canonical_locations"]), (0, 0, 1, 1))
        self.assertEqual(coordination([[1, loc["Gas"], 3, loc["Par"], loc["Bur"]],
                                       [1, loc["Par"], 2, loc["Bur"], 0]], SPEC["canonical_locations"]), (1, 0, 0, 0))

    def test_training_leaves_test_unread(self):
        (self.path / "test-00000" / "options.u8").write_bytes(b"unreadable test")
        with Cache(self.path) as cache, redirect_stdout(io.StringIO()):
            settings = Settings(epochs=2, hidden=16, batch_size=2, threads=1, validation_samples=2)
            model, report = fit(cache, self.root / "trained", settings)
            self.assertTrue(report["complete"])
            self.assertFalse(report["test_evaluated"])
            best = min(report["epochs"], key=lambda row: row["validation"]["overall"]["order_nll"])
            self.assertEqual(best["epoch"], report["best_epoch"])
            observed = evaluate(model, cache, settings)["overall"]
            self.assertEqual(observed, best["validation"]["overall"])
            self.assertEqual(observed["sampled_actions"], 12)
            self.assertEqual(digest(self.root / "trained" / "policy.pt"), report["model_sha256"])
            repeated, second = fit(cache, self.root / "repeated", settings)
            self.assertEqual(second["epochs"][-1]["validation"]["overall"], report["epochs"][-1]["validation"]["overall"])
            for name, value in model.state_dict().items():
                torch.testing.assert_close(value, repeated.state_dict()[name], rtol=0, atol=0)
            with self.assertRaises(FileExistsError):
                fit(cache, self.root / "trained", settings)
        with self.assertRaises(ValueError):
            Cache(self.path, splits=("TEST",))

    def test_earlier_checkpoint_selection(self):
        with Cache(self.path) as cache, redirect_stdout(io.StringIO()):
            settings = Settings(epochs=2, hidden=16, threads=1)
            metric = evaluate(MovementGRU(SPEC, 16), cache, settings)
            sequence = [copy.deepcopy(metric) for _ in range(3)]
            for result, loss in zip(sequence, (3.0, 1.0, 2.0)):
                result["overall"]["order_nll"] = loss
            with patch("neural.train.evaluate", side_effect=sequence):
                _, report = fit(cache, self.root / "selected", settings)
            self.assertEqual(report["best_epoch"], 1)
            _, payload = load_policy(self.root / "selected" / "policy.pt", SPEC)
            self.assertEqual(payload["epoch"], 1)


def main():
    global SPEC_RAW, SPEC
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=Path("data/movement-neural-cache-v1/specification.json"))
    args = parser.parse_args()
    SPEC_RAW = args.spec.read_bytes()
    if hashlib.sha256(SPEC_RAW).hexdigest() != SPEC_HASH:
        raise ValueError("Self-check needs the supported Java-generated specification")
    SPEC = parse_json(SPEC_RAW)
    unittest.main(argv=["neural.test_neural"], verbosity=2)


if __name__ == "__main__":
    main()