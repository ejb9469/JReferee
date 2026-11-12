"""Verified movement-cache reading with bounded memory maps."""

from collections import OrderedDict
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import time

import numpy as np

from .progress import Progress


SPEC_HASH = "94994ea85911c4f7f8e5b904558e9ed345d639dc4f4d9fb93edb76189fa91ba7"
SPLITS = ("TRAIN", "VALIDATION", "TEST")
FILES = {
    "board.u8": "u1",
    "context.i32": "<i4",
    "example_offsets.u64": "<u8",
    "slot_locations.u8": "u1",
    "option_offsets.u64": "<u8",
    "options.u8": "u1",
    "targets.i32": "<i4",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low, high, name):
    require(type(value) is int and low <= value <= high, f"Invalid {name}: {value}")
    return value


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def parse_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def constant(value):
        raise ValueError(f"Invalid JSON number: {value}")

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


def signature(path):
    require(not path.is_symlink() and path.is_file(), f"Expected a regular file: {path}")
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns


def shapes(shard):
    e, s, c = (shard[key] for key in ("examples", "slots", "options"))
    return {
        "board.u8": (e, 75, 4), "context.i32": (e, 3),
        "example_offsets.u64": (e + 1,), "slot_locations.u8": (s,),
        "option_offsets.u64": (s + 1,), "options.u8": (c, 5),
        "targets.i32": (s,),
    }


@dataclass(frozen=True)
class Example:
    board: np.ndarray
    context: np.ndarray
    locations: np.ndarray
    counts: np.ndarray
    options: np.ndarray
    targets: np.ndarray
    metadata: dict


class _Mapped:
    def __init__(self, directory, shard):
        self.arrays = {}
        try:
            for name, shape in shapes(shard).items():
                self.arrays[name] = np.memmap(directory / name, mode="r", dtype=FILES[name], shape=shape)
            with (directory / "metadata.jsonl").open(encoding="utf-8") as stream:
                self.metadata = [parse_json(line) for line in stream]
            require(len(self.metadata) == shard["examples"], f"Metadata count: {directory}")
        except Exception:
            self.close()
            raise

    def close(self):
        for array in self.arrays.values():
            array._mmap.close()
        self.arrays.clear()


class Cache:
    """Verify selected splits once; copy examples out of at most two open shards."""

    def __init__(self, root, splits=("TRAIN", "VALIDATION"), progress=None):
        start = time.perf_counter()
        progress = Progress() if progress is None else progress
        progress.message("CACHE", "Reading the manifest and frozen input specification.")
        self.root = Path(root).resolve()
        self.maps = OrderedDict()
        self.signatures = {}
        self.indices = {}
        self.selected = tuple(splits)
        require(self.selected and len(set(self.selected)) == len(self.selected)
                and set(self.selected) <= set(SPLITS), "Invalid selected splits")
        manifest_path = self.root / "manifest.json"
        signature(manifest_path)
        require(manifest_path.stat().st_size <= 16 * 1024 * 1024, "Manifest exceeds 16 MiB")
        raw = manifest_path.read_bytes()
        self.manifest_hash = hashlib.sha256(raw).hexdigest()
        self.manifest = parse_json(raw)
        m = self.manifest
        require(m["schema"] == "jreferee-neural-cache-v1" and m["complete"] is True
                and m["encoding"] == "jreferee-neural-movement-v1"
                and m["byte_order"] == "LITTLE_ENDIAN" and m["test_evaluated"] is False,
                "Unsupported or incomplete cache")
        require(re.fullmatch("[0-9a-f]{64}", m["dataset_index_sha256"]) is not None, "Invalid dataset fingerprint")
        spec_info = m["specification"]
        require(spec_info["name"] == "specification.json" and spec_info["sha256"] == SPEC_HASH,
                "Unsupported input specification")
        spec_path = self.root / "specification.json"
        signature(spec_path)
        spec_raw = spec_path.read_bytes()
        require(len(spec_raw) == spec_info["bytes"] and hashlib.sha256(spec_raw).hexdigest() == SPEC_HASH,
                "Specification bytes/checksum differ")
        self.spec = parse_json(spec_raw)
        self.spec_text = spec_raw.decode("utf-8")
        self.filter = m["filter"]
        integer(self.filter["split_seed"], -(2**63), 2**63 - 1, "split seed")
        labels = self.filter["communication_types"]
        require(labels is None or (isinstance(labels, list) and all(isinstance(x, str) and x.strip() for x in labels)
                and len(set(labels)) == len(labels)), "Invalid communication filter")
        require(type(self.filter["include_unknown_communication"]) is bool, "Invalid unknown-label filter")
        settings = m["settings"]
        integer(settings["shard_examples"], 1, 1_000_000, "shard examples")
        integer(settings["shard_options"], 1, 100_000_000, "shard options")
        require([s["split"] for s in m["splits"]] == list(SPLITS), "Missing, reordered or duplicate splits")
        self.shards = []
        self.split_info = {s["split"]: s for s in m["splits"]}
        for split in m["splits"]:
            totals = {key: 0 for key in ("examples", "slots", "options", "bytes")}
            require(re.fullmatch("[0-9a-f]{64}", split["source_sha256"]) is not None, "Invalid source digest")
            integer(split["max_units"], 0, 75, "maximum units")
            integer(split["max_options_per_slot"], 0, 100_000_000, "maximum slot options")
            for index, shard in enumerate(split["shards"]):
                require(shard["directory"] == f'{split["split"].lower()}-{index:05d}', "Invalid shard directory")
                e = integer(shard["examples"], 1, settings["shard_examples"], "examples")
                s = integer(shard["slots"], e, e * 75, "slots")
                integer(shard["options"], s, settings["shard_options"], "options")
                require([f["name"] for f in shard["files"]] == list(FILES) + ["metadata.jsonl"], "Unexpected shard files")
                for file in shard["files"]:
                    integer(file["bytes"], 1, 2**63 - 1, "file bytes")
                    require(re.fullmatch("[0-9a-f]{64}", file["sha256"]) is not None, "Invalid file digest")
                    if file["name"] in FILES:
                        size = int(np.prod(shapes(shard)[file["name"]])) * np.dtype(FILES[file["name"]]).itemsize
                        require(file["bytes"] == size, f'Incorrect shape: {shard["directory"]}/{file["name"]}')
                    totals["bytes"] += file["bytes"]
                for key in ("examples", "slots", "options"):
                    totals[key] += shard[key]
                if split["split"] in self.selected:
                    self.shards.append((split["split"], shard))
            for key, value in totals.items():
                require(type(split[key]) is int and split[key] == value, f"Incorrect split {key}")
        self.canonical = np.asarray(self.spec["canonical_locations"], dtype=np.int64)
        self.rows = np.asarray(self.spec["location_board_rows"], dtype=np.int64)
        self._geometry()
        identities = set()
        for split in self.selected:
            records = []
            verified_shards = 0
            max_units = max_options = 0
            progress.start("CACHE", f"Verify {split}", self.split_info[split]["examples"])
            for shard_id, (name, shard) in enumerate(self.shards):
                if name != split:
                    continue
                directory = self.root / shard["directory"]
                require(directory.is_dir() and not directory.is_symlink(), f"Invalid shard directory: {directory}")
                for file in shard["files"]:
                    path = directory / file["name"]
                    before = signature(path)
                    require(before[2] == file["bytes"] and digest(path) == file["sha256"], f"File checksum/size: {path}")
                    require(signature(path) == before, f"File changed during verification: {path}")
                    self.signatures[path] = before
                mapped = _Mapped(directory, shard)
                try:
                    a = mapped.arrays
                    ex, op = a["example_offsets.u64"], a["option_offsets.u64"]
                    require(ex[0] == op[0] == 0 and ex[-1] == shard["slots"] and op[-1] == shard["options"],
                            f"Terminal offsets: {directory}")
                    require(np.all(ex[1:] > ex[:-1]) and np.all(op[1:] > op[:-1]), f"Nonmonotone offsets: {directory}")
                    for row in range(shard["examples"]):
                        example = self._example(mapped, row)
                        self._validate(example, split, identities)
                        units, options = len(example.locations), len(example.options)
                        records.append((shard_id, row, units, options))
                        max_units = max(max_units, units)
                        max_options = max(max_options, int(example.counts.max()))
                        progress.update(len(records), shard["directory"])
                finally:
                    mapped.close()
                verified_shards += 1
            require(max_units == self.split_info[split]["max_units"]
                    and max_options == self.split_info[split]["max_options_per_slot"], f"Incorrect maxima: {split}")
            self.indices[split] = np.asarray(records, dtype=np.int64).reshape(-1, 4)
            progress.finish(f'{verified_shards} shards; {self.split_info[split]["options"]:,} legal options')
        self.verification_seconds = time.perf_counter() - start

    def _geometry(self):
        self.move = np.zeros((3, 82, 82), dtype=bool)
        self.support = np.zeros_like(self.move)
        self.convoy = np.zeros((82, 82), dtype=bool)
        for name, table in (("army_direct", self.move[1]), ("fleet_direct", self.move[2]),
                            ("potential_convoy", self.convoy), ("army_support", self.support[1]),
                            ("fleet_support", self.support[2])):
            edges = np.asarray(self.spec[name], dtype=np.int64)
            table[edges[:, 0], edges[:, 1]] = True
        self.move[1] |= self.convoy
        self.valid_unit = np.zeros((3, 82), dtype=bool)
        for loc in range(1, 82):
            terrain = self.spec["terrain"][self.rows[loc]]
            split_parent = any(c == loc and i != loc for i, c in enumerate(self.canonical))
            self.valid_unit[1, loc] = terrain != 2 and self.canonical[loc] == loc
            self.valid_unit[2, loc] = terrain != 0 and not split_parent
        self.move_canonical = np.zeros_like(self.move)
        for destination in range(1, 82):
            self.move_canonical[:, :, self.canonical[destination]] |= self.move[:, :, destination]

    def _validate(self, example, split, identities):
        board, context, locations, counts, options, targets = (
            example.board, example.context, example.locations, example.counts, example.options, example.targets)
        power, phase, year = map(int, context)
        require(1 <= power <= 7 and phase in (1, 2) and year > 0, "Invalid context")
        require(np.all(board < np.array([3, 8, 82, 8])), "Board category outside vocabulary")
        occupied = board[:, 0] != 0
        require(np.array_equal(occupied, board[:, 1] != 0) and np.array_equal(occupied, board[:, 2] != 0), "Incomplete occupancy")
        require(np.all(self.rows[board[occupied, 2]] == np.flatnonzero(occupied))
                and np.all(self.valid_unit[board[occupied, 0], board[occupied, 2]]), "Invalid unit/coast location")
        require(np.all(board[np.asarray(self.spec["supply_centers"]) == 0, 3] == 0), "Owner on a non-center")
        require(np.array_equal(locations, np.sort(board[board[:, 1] == power, 2])), "Incorrect issuing slots/order")
        require(np.all(counts > 0) and np.all(targets >= 0) and np.all(targets < counts), "Invalid local target")
        require(np.all(options < np.array([3, 82, 5, 82, 82]))
                and np.all(options[:, 0] > 0) and np.all(options[:, 2] > 0), "Invalid option category")
        offset = 0
        order_rank = np.array([0, 1, 2, 3, 0])
        for location, count in zip(locations, counts):
            choices = options[offset:offset + count].astype(np.int64)
            offset += count
            kind, origin, order, target, auxiliary = choices.T
            issuer = board[self.rows[location]]
            require(np.all(kind == issuer[0]) and np.all(origin == location), "Option issuer differs from slot")
            keys = (order_rank[order] * 82 + target) * 82 + auxiliary
            require(np.all(keys[1:] > keys[:-1]), "Duplicate or unsorted legal options")
            hold, move, support, convoy = (order == value for value in (1, 2, 3, 4))
            require(hold.sum() == 1 and np.all(target[hold] == 0) and np.all(auxiliary[hold] == 0), "Missing or malformed HOLD")
            require(np.all(auxiliary[move] == 0) and np.all(self.move[kind[move], origin[move], target[move]]), "Invalid MOVE")
            for mask, is_support in ((support, True), (convoy, False)):
                t, dest = target[mask], auxiliary[mask]
                if not len(t):
                    continue
                require(np.all(t > 0) and np.all(t == self.canonical[t])
                        and np.all(t != self.canonical[location]), "Invalid referenced province")
                referenced = board[self.rows[t]]
                require(np.all(referenced[:, 0] > 0), "Absent referenced unit")
                if is_support:
                    final = np.where(dest == 0, t, dest)
                    require(np.all(dest == self.canonical[dest]) and np.all(t != dest)
                            and np.all(self.support[kind[mask], location, final]), "Invalid SUPPORT")
                    moving = dest != 0
                    require(np.all(self.move_canonical[referenced[moving, 0], referenced[moving, 2], dest[moving]]),
                            "Referenced unit cannot make supported move")
                else:
                    require(issuer[0] == 2 and self.spec["terrain"][self.rows[location]] == 2
                            and np.all(referenced[:, 0] == 1) and np.all(self.convoy[t, dest]), "Invalid CONVOY")
        meta = example.metadata
        require(set(meta) == {"source", "game_key", "communication_type"}, "Unexpected metadata")
        source, key, communication = (meta[k] for k in ("source", "game_key", "communication_type"))
        require(isinstance(source, str) and source.strip() and isinstance(key, str) and key.strip()
                and (communication is None or isinstance(communication, str)), "Invalid source metadata")
        labels = self.filter["communication_types"]
        require(self.filter["include_unknown_communication"] if communication is None else labels is None or communication in labels,
                "Example does not match filter")
        bucket = int(hashlib.sha256(f'{self.filter["split_seed"]}\n{source}\n{key}'.encode()).hexdigest()[:16], 16) % 100
        expected = "TRAIN" if bucket < 80 else "VALIDATION" if bucket < 90 else "TEST"
        require(split == expected, "Game in wrong split")
        identity = source, key, year, phase, power
        require(identity not in identities, "Duplicate decision")
        identities.add(identity)

    @staticmethod
    def _example(mapped, row):
        a = mapped.arrays
        start, end = map(int, a["example_offsets.u64"][row:row + 2])
        offsets = a["option_offsets.u64"][start:end + 1].astype(np.int64)
        return Example(a["board.u8"][row].copy(), a["context.i32"][row].copy(),
                       a["slot_locations.u8"][start:end].copy(), np.diff(offsets),
                       a["options.u8"][offsets[0]:offsets[-1]].copy(),
                       a["targets.i32"][start:end].astype(np.int64), dict(mapped.metadata[row]))

    def _check_files(self, shard_id):
        _, shard = self.shards[int(shard_id)]
        directory = self.root / shard["directory"]
        for file in shard["files"]:
            path = directory / file["name"]
            require(signature(path) == self.signatures[path], f"Verified cache file changed: {path}")

    def get(self, shard_id, row):
        self._check_files(shard_id)
        return self._read(shard_id, row)

    def _read(self, shard_id, row):
        _, shard = self.shards[int(shard_id)]
        directory = self.root / shard["directory"]
        if shard_id not in self.maps:
            if len(self.maps) == 2:
                self.maps.popitem(last=False)[1].close()
            self.maps[shard_id] = _Mapped(directory, shard)
        self.maps.move_to_end(shard_id)
        return self._example(self.maps[shard_id], int(row))

    def batches(self, split, batch_size=32, max_options=16384, seed=None, window=256):
        require(split in self.indices, f"Split was not opened: {split}")
        integer(batch_size, 1, 4096, "batch size")
        integer(max_options, 1, 1_000_000, "batch option limit")
        integer(window, batch_size, 65536, "bucket window")
        records = self.indices[split]
        require(not len(records) or records[:, 3].max() <= max_options, "One example exceeds --max-options; raise the bound")
        random = np.random.default_rng(seed)
        shards = np.unique(records[:, 0])
        if seed is not None:
            random.shuffle(shards)
        for shard_id in shards:
            local = records[records[:, 0] == shard_id].copy()
            if seed is not None:
                random.shuffle(local)
            for start in range(0, len(local), window):
                bucket = sorted(local[start:start + window], key=lambda row: (row[2], row[3]))
                groups, current, options = [], [], 0
                for record in bucket:
                    if current and (len(current) == batch_size or options + record[3] > max_options):
                        groups.append(current)
                        current, options = [], 0
                    current.append(record)
                    options += record[3]
                if current:
                    groups.append(current)
                if seed is not None:
                    random.shuffle(groups)
                for group in groups:
                    self._check_files(shard_id)
                    yield [self._read(record[0], record[1]) for record in group]

    def close(self):
        for mapped in self.maps.values():
            mapped.close()
        self.maps.clear()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()