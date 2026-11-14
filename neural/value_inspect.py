"""Locate over-34-unit value positions without changing data or opening TEST."""

import argparse
from bisect import bisect_right
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_file(path, receipt):
    raw = path.read_bytes()
    require(len(raw) == receipt["bytes"]
            and hashlib.sha256(raw).hexdigest() == receipt["sha256"],
            f"Size or checksum mismatch: {path}")
    return raw


def inspect(root):
    root = Path(root)
    manifest = json.loads((root / "manifest.json").read_bytes())
    require(manifest["schema"] == "jreferee-value-cache-v1"
            and manifest["complete"] is True and manifest["test_exported"] is False,
            "Expected a completed development value cache")
    spec = json.loads(read_file(root / "specification.json", manifest["specification"]))
    powers = spec["output_powers"]
    require(len(powers) == 7, "Expected seven powers")
    phases = spec["phases"]
    report = {"diagnostic_only": True, "test_opened": False, "splits": {}}

    for split in ("TRAIN", "VALIDATION"):
        info = manifest["splits"][split]
        directory = root / split.lower()
        files = {
            name: read_file(directory / name, info["files"][name])
            for name in ("board.u8", "context.i32", "game_offsets.i64", "games.jsonl")
        }
        n, games = info["positions"], info["games"]
        require(len(files["board.u8"]) == n * 300
                and len(files["context.i32"]) == n * 8
                and len(files["game_offsets.i64"]) == (games + 1) * 8,
                "Array dimensions differ from the manifest")
        offsets = [value[0] for value in struct.iter_unpack("<q", files["game_offsets.i64"])]
        require(offsets[0] == 0 and offsets[-1] == n
                and all(a < b for a, b in zip(offsets, offsets[1:])), "Invalid game offsets")
        metadata = [json.loads(line) for line in files["games.jsonl"].splitlines()]
        require(len(metadata) == games, "Metadata count mismatch")
        for g, row in enumerate(metadata):
            require(row["start"] == offsets[g] and row["end"] == offsets[g + 1],
                    "Metadata and game offsets disagree")
        board = memoryview(files["board.u8"])

        def describe(index):
            phase, year = struct.unpack_from("<ii", files["context.i32"], index * 8)
            require(1 <= phase < len(phases), "Unknown value phase")
            units = [0] * 7
            centers = [0] * 7
            for start in range(index * 300, (index + 1) * 300, 4):
                unit, owner, location, control = board[start:start + 4]
                require(unit <= 2 and owner <= 7 and location <= 81 and control <= 7,
                        "Invalid board category")
                require((unit != 0) == (owner != 0) == (location != 0),
                        "Partial unit encoding")
                if unit:
                    units[owner - 1] += 1
                if control:
                    centers[control - 1] += 1
            return {
                "row": index, "year": year, "phase": phases[phase],
                "unit_count": sum(units), "center_count": sum(centers),
                "units_by_power": dict(zip(powers, units)),
                "centers_by_power": dict(zip(powers, centers)),
            }

        affected = set()
        counts = Counter()
        examples = []
        for index in range(n):
            unit_count = sum(board[start] != 0
                             for start in range(index * 300, (index + 1) * 300, 4))
            if unit_count <= 34:
                continue
            g = bisect_right(offsets, index) - 1
            affected.add(g)
            current = describe(index)
            counts[current["phase"]] += 1
            if len(examples) < 5:
                row = metadata[g]
                examples.append({
                    "source": row["source"], "game_key": row["game_key"],
                    "payload_sha256": row["payload_sha256"],
                    "position_in_game": index - offsets[g],
                    "position": current,
                    "neighbors": [
                        describe(other) for other in (index - 1, index + 1)
                        if offsets[g] <= other < offsets[g + 1]
                    ],
                })
        report["splits"][split] = {
            "positions_over_34": sum(counts.values()),
            "affected_games": len(affected), "by_phase": dict(counts),
            "examples": examples,
        }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", required=True)
    args = parser.parse_args()
    print(json.dumps(inspect(args.cache), indent=2))


if __name__ == "__main__":
    main()