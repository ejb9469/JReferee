"""Verified, provider-neutral value arrays. TEST is never opened."""

from dataclasses import dataclass
import hashlib
from pathlib import Path
import re

import numpy as np

from .cache import integer, parse_json, require


SPEC_HASH = "c3f18d64047fee435c0b7acca31c47f38e6f5dcdb393400fe18f4a6da2c6a4df"
REWARD = "jreferee-eventual-sos-v3"
SPLITS = ("TRAIN", "VALIDATION")
MAX_BYTES = 512 * 1024 * 1024


def checked_hash(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value),
            "Invalid SHA-256 identity")
    return value


@dataclass(frozen=True)
class Split:
    board: np.ndarray
    context: np.ndarray
    targets: np.ndarray
    offsets: np.ndarray
    game_ids: np.ndarray
    metadata: tuple

    def __len__(self):
        return len(self.board)


class ValueCache:
    """Own immutable byte snapshots, bounded to 512 MiB total input."""

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.remaining = MAX_BYTES
        raw = self.read("manifest.json", limit=1024 * 1024)
        self.manifest_hash = hashlib.sha256(raw).hexdigest()
        self.manifest = m = parse_json(raw)
        require(m["schema"] == "jreferee-value-cache-v1" and m["complete"] is True
                and m["encoding"] == "jreferee-neural-value-v1"
                and m["reward_contract"] == REWARD
                and m["byte_order"] == "LITTLE_ENDIAN"
                and m["test_exported"] is False
                and set(m["splits"]) == set(SPLITS), "Incompatible value cache")
        self.fingerprint = checked_hash(m["dataset_fingerprint"])
        spec = self.read("specification.json", m["specification"], 1024 * 1024)
        require(hashlib.sha256(spec).hexdigest() == SPEC_HASH,
                "Unknown value specification; do not edit its fingerprint")
        self.spec_text = spec.decode("utf-8")
        self.spec = parse_json(spec)
        self.splits = {}
        identities = set()
        for name in SPLITS:
            self.splits[name] = self.load_split(name, identities)

    def read(self, name, receipt=None, limit=MAX_BYTES):
        path = self.root / name
        require(not path.is_symlink() and path.is_file(), f"Not a regular file: {path}")
        size = path.stat().st_size
        require(size <= min(limit, self.remaining), f"Value-cache size limit: {path}")
        if receipt is not None:
            require(integer(receipt["bytes"], 0, MAX_BYTES, "file bytes") == size,
                    f"Byte count mismatch: {path}")
            checked_hash(receipt["sha256"])
        with path.open("rb") as stream:
            raw = stream.read(size + 1)
        require(len(raw) == size, f"File changed while reading: {path}")
        self.remaining -= size
        if receipt is not None:
            require(hashlib.sha256(raw).hexdigest() == receipt["sha256"],
                    f"Checksum mismatch: {path}")
        return raw

    def load_split(self, name, identities):
        info = self.manifest["splits"][name]
        games = integer(info["games"], 0, 10_000_000, "games")
        n = integer(info["positions"], 0, 100_000_000, "positions")
        checked_hash(info["source_sha256"])
        files = info["files"]
        layout = {
            "board.u8": ("u1", (n, 75, 4)),
            "context.i32": ("<i4", (n, 2)),
            "targets.f32": ("<f4", (games, 7)),
            "game_offsets.i64": ("<i8", (games + 1,)),
        }
        require(set(files) == set(layout) | {"games.jsonl"}, "Unexpected cache files")
        directory = self.root / name.lower()
        require(not directory.is_symlink() and directory.is_dir(), "Invalid split directory")
        arrays = {}
        for filename, (dtype, shape) in layout.items():
            size = int(np.prod(shape)) * np.dtype(dtype).itemsize
            require(files[filename]["bytes"] == size, f"Array shape mismatch: {filename}")
            raw = self.read(f"{name.lower()}/{filename}", files[filename])
            arrays[filename] = np.frombuffer(raw, dtype=dtype).reshape(shape)
        board, context, targets, offsets = (arrays[key] for key in layout)
        require(offsets[0] == 0 and offsets[-1] == n
                and np.all(np.diff(offsets) > 0), "Invalid game offsets")
        require(np.isfinite(targets).all() and np.all((targets >= 0) & (targets <= 1))
                and np.allclose(targets.sum(1), 1, atol=1e-6, rtol=0), "Invalid SoS targets")
        require(np.all((context[:, 0] >= 1) & (context[:, 0] <= 3))
                and np.all(context[:, 1] >= 1901), "Invalid value context")
        self.check_boards(board)
        lines = self.read(f"{name.lower()}/games.jsonl", files["games.jsonl"]).splitlines()
        require(len(lines) == games, "Game metadata count mismatch")
        metadata = []
        for g, line in enumerate(lines):
            require(len(line) <= 1024 * 1024, "Metadata line exceeds 1 MiB")
            row = parse_json(line)
            source, key = row["source"], row["game_key"]
            require(all(isinstance(s, str) and s.strip() for s in (source, key)),
                    "Blank source/game identity")
            identity = (source, key)
            require(identity not in identities, "Duplicate game across cache splits")
            identities.add(identity)
            checked_hash(row["payload_sha256"])
            label = row["communication_type"]
            require(label is None or isinstance(label, str) and label.strip(),
                    "Invalid communication label")
            require(integer(row["start"], 0, n, "start") == offsets[g]
                    and integer(row["end"], 0, n, "end") == offsets[g + 1],
                    "Metadata offsets disagree")
            moments = context[offsets[g]:offsets[g + 1]].astype(np.int64)
            chronology = moments[:, 1] * 3 + moments[:, 0] - 1
            require(np.all(np.diff(chronology) > 0), "Nonchronological game positions")
            metadata.append(row)
        ids = np.repeat(np.arange(games, dtype=np.int64), np.diff(offsets))
        ids.setflags(write=False)
        return Split(board, context, targets, offsets, ids, tuple(metadata))

    def check_boards(self, boards):
        spec = self.spec["board_specification"]
        rows = np.asarray(spec["location_board_rows"])
        canonical = np.asarray(spec["canonical_locations"])
        terrain = np.asarray(spec["terrain"])
        centers = np.asarray(spec["supply_centers"], dtype=bool)
        split_parents = {canonical[i] for i in range(1, 82) if canonical[i] != i}
        for start in range(0, len(boards), 4096):
            board = boards[start:start + 4096]
            unit, owner, location, control = (board[:, :, i] for i in range(4))
            require(np.all(unit <= 2) and np.all(owner <= 7)
                    and np.all(location <= 81) and np.all(control <= 7), "Invalid board ID")
            occupied = unit != 0
            require(np.array_equal(occupied, owner != 0)
                    and np.array_equal(occupied, location != 0), "Partial unit encoding")
            require(np.all(~occupied | (rows[location] == np.arange(75))),
                    "Unit is encoded in the wrong board row")
            require(np.all(control[:, ~centers] == 0), "Owned non-center")
            require(np.all((unit != 1) | ((terrain != 2) & (canonical[location] == location))),
                    "Invalid Army location")
            require(np.all((unit != 2) | ((terrain != 0)
                    & ~np.isin(location, list(split_parents)))), "Invalid Fleet location")
            require(np.all(occupied.sum(1) <= 34), "Too many standing units")


def current_sos(board):
    counts = np.stack([(board[:, :, 3] == power).sum(1) for power in range(1, 8)], axis=1)
    squared = counts.astype(np.float64) ** 2
    total = squared.sum(1, keepdims=True)
    require(np.all(total > 0), "Current-board SoS is undefined without owned centers")
    solos = counts >= 18
    require(np.all(solos.sum(1) <= 1), "Multiple current-board solos")
    return np.where(solos.any(1, keepdims=True), solos, squared / total)