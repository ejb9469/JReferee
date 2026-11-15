"""Synthetic corruption checks; optionally use the real frozen value specification."""

import argparse
from contextlib import redirect_stdout
from dataclasses import FrozenInstanceError
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from uuid import UUID

from . import self_play_cache as reader


SPEC_BYTES = None


def write_json(path, value):
    path.write_text(json.dumps(value, allow_nan=False) + "\n", encoding="utf-8")


def write_lines(path, rows):
    path.write_text("".join(json.dumps(row, allow_nan=False) + "\n" for row in rows), encoding="utf-8")


def receipt(path):
    raw = path.read_bytes()
    return {"bytes": len(raw), "sha256": reader.sha(raw)}


def synthetic_spec():
    names = sorted(["Bre", "Bur", "Mar", "Par", "Bul", "BulEC", "BulSC",
                    "Spa", "SpaNC", "SpaSC", "Stp", "StpNC", "StpSC"]
                   + [f"X{i:02}" for i in range(68)])
    locations = ["NONE"] + names
    children = {"BulEC": "Bul", "BulSC": "Bul", "SpaNC": "Spa", "SpaSC": "Spa",
                "StpNC": "Stp", "StpSC": "Stp"}
    parents = [name for name in names if name not in children]
    canonical = [0] + [locations.index(children.get(name, name)) for name in names]
    rows = [-1] + [parents.index(children.get(name, name)) for name in names]
    spec = {
        "locations": locations, "canonical_locations": canonical, "location_board_rows": rows,
        "terrain": [1] * 75, "supply_centers": [int(i < 34) for i in range(75)],
        "army_direct": [[locations.index(p), locations.index("Bur")] for p in ("Mar", "Par")],
        "fleet_direct": [], "potential_convoy": [], "army_support": [], "fleet_support": [],
    }
    return (json.dumps({"board_specification": spec}) + "\n").encode()


def pack(path, code, values):
    values = tuple(values)
    path.write_bytes(struct.pack("<" + str(len(values)) + code, *values))


def fixture(root, eligible=True, only_train=False):
    root.mkdir()
    (root / "policy").mkdir()
    (root / "value").mkdir()
    (root / "value/specification.json").write_bytes(SPEC_BYTES)
    spec = json.loads(SPEC_BYTES)["board_specification"]
    loc = spec["locations"].index
    board_row = lambda name: spec["location_board_rows"][loc(name)]
    board = bytearray(300)
    centers = [i for i, value in enumerate(spec["supply_centers"]) if value and i != board_row("Mar")]
    for index in centers[:17]:
        board[index * 4 + 3] = 3
    board[board_row("Mar") * 4 + 3] = 4
    for name in ("Mar", "Par"):
        index = board_row(name) * 4
        board[index:index + 3] = bytes((1, 3, loc(name)))
    index = board_row("Spa") * 4
    board[index:index + 3] = bytes((2, 6, loc("SpaSC")))
    write_json(root / "policy-specification.json", {
        "schema": reader.SCHEMA, "movement_specification": spec,
        "movement_specification_sha256": reader.MOVEMENT_HASH, "type_fields": list(reader.TYPE_FIELDS),
        "profile_ids": ["NONE", "OPENING", "STANDARD"], "output_ids": ["NONE", "FINAL", "AVERAGE"],
        "target": "COMPLETE_ACTION_DISTRIBUTION",
        "loss": "NEGATIVE_EXPECTED_SUM_OF_CONDITIONAL_LOG_PROBABILITIES_WITH_FULL_LEGAL_MASKS",
        "type_transform": "RAW_FLOAT64", "temperature_schedule": "ADAPTIVE",
        "max_shard_examples": 256, "max_packed_options_or_action_indices": 1_000_000,
    })
    m = {"schema": reader.SCHEMA, "complete": True, "byte_order": "LITTLE_ENDIAN",
         "split_seed": "7", "split_rule": reader.SPLIT_RULE, "test_opened": False,
         "policy_splits": {split: [] for split in reader.SPLITS}}
    v = {"schema": "jreferee-value-cache-v1", "complete": True, "encoding": "jreferee-neural-value-v1",
         "reward_contract": reader.REWARD, "byte_order": "LITTLE_ENDIAN", "test_exported": False, "splits": {}}
    games = []
    for index, split in enumerate(reader.SPLITS):
        directory = root / "value" / split.lower()
        directory.mkdir()
        included = not (only_train and index == 1)
        accepted = included and eligible and index == 1
        count = 2 if accepted else 0
        (directory / "board.u8").write_bytes(bytes(board) * count)
        pack(directory / "context.i32", "i", [1, 1901, 2, 1901] if accepted else [])
        pack(directory / "targets.f32", "f", [0, 0, 1, 0, 0, 0, 0] if accepted else [])
        pack(directory / "game_offsets.i64", "q", [0, 2] if accepted else [0])
        game_id = str(UUID(int=index + 1))
        archive_hash = reader.sha(f"archive-{index}".encode())
        write_lines(directory / "games.jsonl", [{
            "source": "JREFEREE_SELF_PLAY", "game_key": game_id, "payload_sha256": archive_hash,
            "communication_type": "NO_PRESS", "start": 0, "end": 2,
        }] if accepted else [])
        v["splits"][split] = {"games": int(accepted), "positions": count,
                              "source_sha256": reader.sha(f"value-{index}".encode())}
        if not included:
            continue
        seats = {power: {
            "method": "DIL_PIKL" if power == "FRANCE" else "PRIOR",
            "candidates": 3, "iterations": 2, "rollouts": 1, "rollout_limit": 1,
            "output": "AVERAGE", "root": "UNIFORM", "continuation": "UNIFORM",
            "movement_models": {}, "value_model": None,
        } for power in reader.POWERS[1:]}
        games.append({
            "game_index": index, "game_id": game_id, "logical_key": f"{index * 6:064x}",
            "archive_sha256": archive_hash, "split": split,
            "configuration": {"last_year": 1902, "game_seed": str(7 + index),
                              "adjudication_trials": 1, "adjudication_seed": "0", "seats": seats},
            "termination": "SOLO" if accepted else "ARTIFICIAL_CUTOFF", "policy_examples": 2,
            "skipped_non_movement": 0, "skipped_non_dil": 2, "skipped_zero_units": 0,
            "value_positions": count, "value_status": "ACCEPTED" if accepted else "ARTIFICIAL_CUTOFF",
        })
        directory = root / "policy" / f"{split.lower()}-00000"
        directory.mkdir()
        (directory / "board.u8").write_bytes(bytes(board) * 2)
        pack(directory / "context.i32", "i", [3, 1, 1901, 3, 2, 1901])
        pack(directory / "types.f64", "d",
             [0.01, 0.01, 10 ** -1.5, 0.1, 10 ** -0.5, 0.25, 0.25, 0.25, 0.25]
             + [0.0001, 0.0001, 0.001, 0.01, 0.1, 0.25, 0.25, 0.25, 0.25])
        pack(directory / "type_ids.i32", "i", [1, 2, 2, 2])
        pack(directory / "example_offsets.i64", "q", [0, 2, 4])
        issuing = sorted((loc("Mar"), loc("Par")))
        pack(directory / "slot_locations.u8", "B", issuing * 2)
        pack(directory / "option_offsets.i64", "q", [0, 2, 4, 6, 8])
        options = [field for location in issuing
                   for option in ((1, location, 1, 0, 0), (1, location, 2, loc("Bur"), 0)) for field in option]
        pack(directory / "options.u8", "B", options * 2)
        pack(directory / "candidate_offsets.i64", "q", [0, 3, 6])
        pack(directory / "action_offsets.i64", "q", range(0, 13, 2))
        pack(directory / "actions.i32", "i", [0, 0, 1, 1, 0, 1] * 2)
        probabilities = (0.75, 0.25, 0.0)
        pack(directory / "policy.f64", "d", probabilities * 2)
        pack(directory / "log_prior.f64", "d", [-1.386, -1.386, -float("inf")] * 2)
        pack(directory / "mean_values.f64", "d", [0.4, 0.6, 0.2] * 2)
        metadata, selected = [], []
        for phase in (1, 2):
            seeds = [reader.decision_seed(7 + index, 1901, phase, "FRANCE", stream) for stream in range(3)]
            selected.append(reader.selected_index(probabilities, seeds[2]))
            metadata.append({
                "game_index": index, "phase_index": phase - 1, "power": "FRANCE",
                "search_seed": str(seeds[0]), "rollout_seed": str(seeds[1]), "selection_seed": str(seeds[2]),
                "search_samples": 2, "candidate_sources": ["HEURISTIC", "POLICY_SAMPLE", "EXPLORATION"],
            })
        pack(directory / "selected.i32", "i", selected)
        write_lines(directory / "metadata.jsonl", metadata)
        m["policy_splits"][split].append({
            "directory": f"policy/{split.lower()}-00000", "game_index": index,
            "examples": 2, "slots": 4, "options": 8, "candidates": 6, "action_indices": 12,
        })
    m.update(games=len(games), policy_examples=len(games) * 2, value_positions=2 if eligible and not only_train else 0)
    write_lines(root / "games.jsonl", games)
    write_json(root / "manifest.json", m)
    write_json(root / "value/manifest.json", v)
    reseal(root)
    return root


def reseal(root):
    m = json.loads((root / "manifest.json").read_bytes())
    v = json.loads((root / "value/manifest.json").read_bytes())
    fingerprint = reader.sha(f'{reader.SCHEMA}\n{m["split_seed"]}\n{reader.sha((root / "games.jsonl").read_bytes())}'.encode())
    m["dataset_fingerprint"] = v["dataset_fingerprint"] = fingerprint
    for reports in m["policy_splits"].values():
        for report in reports:
            directory = root / report["directory"]
            report["files"] = {path.name: receipt(path) for path in directory.iterdir()}
    for split, report in v["splits"].items():
        directory = root / "value" / split.lower()
        report["files"] = {path.name: receipt(path) for path in directory.iterdir()}
    v["specification"] = receipt(root / "value/specification.json")
    write_json(root / "value/manifest.json", v)
    m["value_manifest"] = receipt(root / "value/manifest.json")
    m["policy_specification"] = receipt(root / "policy-specification.json")
    m["game_index"] = receipt(root / "games.jsonl")
    write_json(root / "manifest.json", m)


class SelfPlayTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="jreferee-python-self-play-")
        self.addCleanup(temporary.cleanup)
        self.parent = Path(temporary.name)
        self.root = fixture(self.parent / "cache")

    def test_complete_action_alignment_and_value_links(self):
        cache = reader.SelfPlayCache(self.root)
        self.assertEqual(dict(cache.policy_counts), {"TRAIN": 2, "VALIDATION": 2})
        examples = list(cache.policies("TRAIN"))
        self.assertEqual(examples[0].actions, ((0, 0), (1, 1), (0, 1)))
        self.assertEqual(examples[0].probabilities, (0.75, 0.25, 0.0))
        self.assertEqual(examples[0].log_priors[-1], -float("inf"))
        self.assertEqual(examples[0].types[0], 0.01)
        self.assertEqual(examples[1].types[0], 0.0001)
        self.assertEqual(list(cache.values("TRAIN")), [])
        values = list(cache.values("VALIDATION"))
        self.assertEqual(len(values), 2)
        self.assertEqual(values[0].target, (0, 0, 1, 0, 0, 0, 0))
        self.assertEqual(values[0].board, cache.get_policy("VALIDATION", 0).board)

    def test_empty_validation_and_value_splits(self):
        cache = reader.SelfPlayCache(fixture(self.parent / "smoke", eligible=False, only_train=True))
        self.assertEqual(dict(cache.policy_counts), {"TRAIN": 2, "VALIDATION": 0})
        self.assertEqual(dict(cache.value_counts), {"TRAIN": 0, "VALIDATION": 0})
        self.assertEqual(list(cache.values("VALIDATION")), [])

    def test_immutable_verified_snapshot(self):
        cache = reader.SelfPlayCache(self.root)
        example = cache.get_policy("TRAIN", 0)
        (self.root / "policy/train-00000/policy.f64").write_bytes(b"changed after verification")
        with patch.object(Path, "open", side_effect=AssertionError("Snapshot reopened a file")):
            self.assertEqual(cache.get_policy("TRAIN", 0), example)
            self.assertEqual(len(list(cache.values("VALIDATION"))), 2)
        with self.assertRaises(TypeError):
            example.metadata["power"] = "GERMANY"
        with self.assertRaises(FrozenInstanceError):
            example.actions = ()
        with self.assertRaises(ValueError):
            reader.SelfPlayCache(self.root)

    def test_resealed_numeric_corruptions(self):
        cases = [
            ("actions.i32", "i", 0, 2, "Local action index"),
            ("action_offsets.i64", "q", 1, 1, "action length"),
            ("example_offsets.i64", "q", 1, 0, "Nonmonotone"),
            ("policy.f64", "d", 0, 0.9, "probability mass"),
            ("policy.f64", "d", 2, 0.1, "zero anchor"),
            ("log_prior.f64", "d", 0, float("nan"), "anchor log prior"),
            ("mean_values.f64", "d", 0, 2.0, "historical mean"),
            ("selected.i32", "i", 0, 2, "zero-probability"),
            ("types.f64", "d", 0, 0.1, "Type features"),
            ("type_ids.i32", "i", 0, 2, "Type IDs"),
            ("context.i32", "i", 0, 4, "power mismatch"),
        ]
        for name, code, index, value, message in cases:
            with self.subTest(name=name, index=index):
                path = self.root / "policy/train-00000" / name
                original = path.read_bytes()
                raw = bytearray(original)
                struct.pack_into("<" + code, raw, index * struct.calcsize("<" + code), value)
                path.write_bytes(raw)
                reseal(self.root)
                with self.assertRaisesRegex(ValueError, message):
                    reader.SelfPlayCache(self.root)
                path.write_bytes(original)
                reseal(self.root)

    def test_coast_and_geometry(self):
        spec = json.loads(SPEC_BYTES)["board_specification"]
        locations = spec["locations"]
        path = self.root / "policy/train-00000/board.u8"
        raw = bytearray(path.read_bytes())
        raw[spec["location_board_rows"][locations.index("Spa")] * 4 + 2] = locations.index("Spa")
        path.write_bytes(raw)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "unit coast"):
            reader.SelfPlayCache(self.root)

    def test_checksum_and_specification_identity(self):
        path = self.root / "policy/train-00000/actions.i32"
        original = path.read_bytes()
        path.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
        with self.assertRaisesRegex(ValueError, "Checksum"):
            reader.SelfPlayCache(self.root)
        path.write_bytes(original)
        (self.root / "value/specification.json").write_bytes(SPEC_BYTES + b" ")
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "frozen value specification"):
            reader.SelfPlayCache(self.root)

    def test_decision_seed(self):
        path = self.root / "policy/train-00000/metadata.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[0]["search_seed"] = "0"
        write_lines(path, rows)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "seed mismatch"):
            reader.SelfPlayCache(self.root)

    def test_duplicate_decision(self):
        directory = self.root / "policy/train-00000"
        for name, width in (("context.i32", 12), ("types.f64", 72), ("type_ids.i32", 8), ("selected.i32", 4)):
            path = directory / name
            raw = path.read_bytes()
            path.write_bytes(raw[:width] * 2)
        path = directory / "metadata.jsonl"
        first = json.loads(path.read_text().splitlines()[0])
        write_lines(path, [first, first])
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "Duplicate policy decision"):
            reader.SelfPlayCache(self.root)

    def test_missing_acting_power(self):
        path = self.root / "games.jsonl"
        games = [json.loads(line) for line in path.read_text().splitlines()]
        games[0]["configuration"]["seats"]["RUSSIA"]["method"] = "DIL_PIKL"
        write_lines(path, games)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "Missing acting power"):
            reader.SelfPlayCache(self.root)

    def test_missing_accepted_value_game(self):
        directory = self.root / "value/validation"
        for name in ("board.u8", "context.i32", "targets.f32", "games.jsonl"):
            (directory / name).write_bytes(b"")
        pack(directory / "game_offsets.i64", "q", [0])
        path = self.root / "value/manifest.json"
        manifest = json.loads(path.read_bytes())
        manifest["splits"]["VALIDATION"].update(games=0, positions=0)
        write_json(path, manifest)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "Missing accepted value game"):
            reader.SelfPlayCache(self.root)

    def test_solo_target(self):
        pack(self.root / "value/validation/targets.f32", "f", [0, 0, 0.5, 0.5, 0, 0, 0])
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "solo value target"):
            reader.SelfPlayCache(self.root)

    def test_cutoff_cannot_acquire_value_rows(self):
        path = self.root / "games.jsonl"
        games = [json.loads(line) for line in path.read_text().splitlines()]
        games[1]["termination"] = "ARTIFICIAL_CUTOFF"
        games[1]["value_status"] = "ARTIFICIAL_CUTOFF"
        games[1]["value_positions"] = 0
        write_lines(path, games)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "Value admission/game link"):
            reader.SelfPlayCache(self.root)

    def test_value_policy_board_disagreement(self):
        path = self.root / "value/validation/board.u8"
        raw = bytearray(path.read_bytes())
        for index in range(3, 300, 4):
            if raw[index] == 3:
                raw[index] = 2
                break
        path.write_bytes(raw)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "Policy/value board mismatch"):
            reader.SelfPlayCache(self.root)

    def test_split_leakage_and_duplicate_batches(self):
        cache = reader.SelfPlayCache(self.root)
        with self.assertRaisesRegex(ValueError, "Duplicate game across caches"):
            reader.validate_disjoint([cache, cache])
        path = self.root / "games.jsonl"
        games = [json.loads(line) for line in path.read_text().splitlines()]
        games[1]["split"] = "TRAIN"
        write_lines(path, games)
        reseal(self.root)
        with self.assertRaisesRegex(ValueError, "ordering/split"):
            reader.SelfPlayCache(self.root)

    def test_no_test_access_and_unsafe_paths(self):
        (self.root / "test").mkdir()
        (self.root / "test/payload").write_bytes(b"must not be read")
        original_open = Path.open

        def guarded(path, *args, **kwargs):
            if "test" in path.parts:
                raise AssertionError("TEST payload opened")
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", guarded):
            cache = reader.SelfPlayCache(self.root)
            with self.assertRaisesRegex(ValueError, "Only TRAIN"):
                list(cache.policies("TEST"))
            with self.assertRaisesRegex(ValueError, "Only TRAIN"):
                list(cache.values("TEST"))
            path = self.root / "manifest.json"
            m = json.loads(path.read_bytes())
            m["policy_splits"]["TRAIN"][0]["directory"] = "test"
            write_json(path, m)
            with self.assertRaisesRegex(ValueError, "Unsafe shard"):
                reader.SelfPlayCache(self.root)

    def test_input_budget_and_json_numbers(self):
        with patch.object(reader, "MAX_BYTES", 64):
            with self.assertRaisesRegex(ValueError, "input-size limit"):
                reader.SelfPlayCache(self.root)
        for raw in (b'{"x":1,"x":2}', b'{"x":1e999}', b'{"x":NaN}'):
            with self.assertRaises(ValueError):
                reader.parse(raw)

    def test_unsigned_split_and_java_random_goldens(self):
        self.assertEqual(reader.split_for("0" * 64, 7), "TRAIN")
        self.assertEqual(reader.split_for("0" * 63 + "6", 7), "VALIDATION")
        self.assertEqual([reader.decision_seed(7, 1901, 1, "FRANCE", stream) for stream in range(3)],
                         [-5567542011880569836, -4533062313560485482, 2858565176605115296])
        self.assertEqual(reader.selected_index((0.730699042060042, 0.269300957939958), 7), 1)
        self.assertEqual(reader.selected_index((0.730699042060043, 0.269300957939957), 7), 0)

    def test_cli_summary(self):
        output = io.StringIO()
        with patch("sys.argv", ["reader", "--cache", str(self.root)]), redirect_stdout(output):
            reader.main()
        self.assertIn("Games=2 policy_examples=4 value_positions=2", output.getvalue())
        self.assertIn("END SELF-PLAY CACHE READBACK", output.getvalue())


def main():
    global SPEC_BYTES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, help="Real value/specification.json; no game payloads are read")
    args = parser.parse_args()
    if args.spec is not None:
        SPEC_BYTES = args.spec.read_bytes()
        reader.require(reader.sha(SPEC_BYTES) == reader.VALUE_HASH, "Wrong frozen value specification")
        unittest.main(argv=[__name__], verbosity=2)
    else:
        SPEC_BYTES = synthetic_spec()
        print("Synthetic unit fixture: test-only specification hash override; production hashes are unchanged.")
        with patch.object(reader, "VALUE_HASH", reader.sha(SPEC_BYTES)):
            unittest.main(argv=[__name__], verbosity=2)


if __name__ == "__main__":
    main()