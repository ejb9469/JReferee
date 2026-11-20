"""Verified completed-game value positions; policy arrays are never value labels."""

import json
from types import MappingProxyType

from . import self_play_cache as reader
from .self_play_data import plain


COHORT_PREFIX = b"jreferee-self-play-value-cohort-v1\n"


def cohort(receipts, split_seed):
    reader.long_text(str(split_seed))
    reader.require(type(receipts) is list and 1 <= len(receipts) <= 1024, "Expected 1..1024 dataset receipts")
    previous = ""
    raw = bytearray(COHORT_PREFIX + f"{split_seed}\n".encode("ascii"))
    for receipt in receipts:
        reader.fields(receipt, "dataset_fingerprint cache_manifest_sha256", "dataset receipt")
        for name in ("dataset_fingerprint", "cache_manifest_sha256"):
            reader.checked_hash(receipt[name])
            raw.extend(bytes.fromhex(receipt[name]))
        reader.require(receipt["dataset_fingerprint"] > previous, "Unsorted or repeated value dataset")
        previous = receipt["dataset_fingerprint"]
    return reader.sha(raw)


class ValueData:
    def __init__(self, caches):
        self.caches = tuple(sorted(caches, key=lambda cache: cache.fingerprint))
        reader.validate_disjoint(self.caches)
        self.split_seed = self.caches[0].seed
        path = self.caches[0].root / "value/specification.json"
        reader.require(path.is_file() and not path.is_symlink(), "Expected a regular value specification")
        with path.open("rb") as stream:
            raw = stream.read(1024 * 1024 + 1)
        reader.require(reader.sha(raw) == reader.VALUE_HASH, "Unknown frozen value specification")
        self.spec_text = raw.decode("utf-8")
        self.spec = reader.parse(raw)
        examples = {split: [] for split in reader.SPLITS}
        admission = {split: {"source_games": 0, "eligible_games": 0, "positions": 0,
                             "cutoff_games": 0, "no_position_games": 0} for split in reader.SPLITS}
        producers = {}
        for cache in self.caches:
            reader.require(plain(cache.specification["movement_specification"]) == self.spec["board_specification"],
                           "Value/movement specifications differ")
            for game in cache.games:
                settings = plain(game["configuration"])
                seats = list(settings["seats"].values())
                reader.require(all(seat == seats[0] for seat in seats)
                               and seats[0]["method"] == "DIL_PIKL"
                               and seats[0]["root"] == "COHERENT_GRU_HYBRID",
                               "Value learning requires homogeneous seven-seat hybrid DiL self-play")
                del settings["game_seed"]
                identity = json.dumps(settings, sort_keys=True, separators=(",", ":"))
                producers[identity] = settings
                counts = admission[game["split"]]
                counts["source_games"] += 1
                if game["value_status"] == "ACCEPTED":
                    reader.require(game["termination"] == "SOLO" and game["value_positions"] > 0,
                                   "Only completed solos supply eventual-value targets")
                    counts["eligible_games"] += 1
                    counts["positions"] += game["value_positions"]
                elif game["value_status"] == "ARTIFICIAL_CUTOFF":
                    reader.require(game["value_positions"] == 0, "Cutoff acquired value positions")
                    counts["cutoff_games"] += 1
                else:
                    reader.require(game["value_status"] == "NO_POSITIONS" and game["value_positions"] == 0,
                                   "Unknown value exclusion")
                    counts["no_position_games"] += 1
            for split in reader.SPLITS:
                examples[split].extend(cache.values(split))
        self.examples = MappingProxyType({split: tuple(rows) for split, rows in examples.items()})
        for split, rows in self.examples.items():
            reader.require(len(rows) == admission[split]["positions"]
                           and len({row.metadata["game_key"] for row in rows}) == admission[split]["eligible_games"],
                           "Value position/game coverage differs")
        self.admission = admission
        self.producers = [producers[key] for key in sorted(producers)]
        self.fingerprint = cohort(self.receipts(), self.split_seed)

    def receipts(self):
        return [{"dataset_fingerprint": cache.fingerprint, "cache_manifest_sha256": cache.manifest_hash}
                for cache in self.caches]

    def preflight(self):
        reader.require(all(self.examples[split] for split in reader.SPLITS),
                       "Value fitting requires eligible positions in both TRAIN and VALIDATION")

    def splits(self):
        import numpy as np
        from .value_cache import Split

        result = {}
        for split, rows in self.examples.items():
            board = np.asarray([row.board for row in rows], dtype="u1").reshape(-1, 75, 4)
            context = np.asarray([row.context for row in rows], dtype="<i4").reshape(-1, 2)
            metadata, targets, offsets, ids = [], [], [0], []
            for index, row in enumerate(rows):
                key = row.metadata["game_key"]
                if not metadata or key != metadata[-1]["game_key"]:
                    if metadata:
                        metadata[-1]["end"] = index
                        offsets.append(index)
                    metadata.append({**plain(row.metadata), "start": index, "end": len(rows)})
                    targets.append(row.target)
                reader.require(row.target == targets[-1], "Value target changed within a game")
                ids.append(len(metadata) - 1)
            if metadata:
                offsets.append(len(rows))
            arrays = (board, context, np.asarray(targets, dtype="<f4").reshape(-1, 7),
                      np.asarray(offsets, dtype="<i8"), np.asarray(ids, dtype="<i8"))
            immutable = tuple(np.frombuffer(array.tobytes(), dtype=array.dtype).reshape(array.shape) for array in arrays)
            result[split] = Split(*immutable, tuple(metadata))
        return result