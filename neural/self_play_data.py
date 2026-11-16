"""Type features and whole-action minibatches, independent of the ML runtime."""

from collections.abc import Mapping
from dataclasses import dataclass
import math
import random
import struct
from types import MappingProxyType

from .self_play_cache import POWERS, SPLITS, checked_hash, integer, require, validate_disjoint


TYPE_FIELDS = (
    "log10_playing_lambda",
    "log10_population_lambda_0", "log10_population_lambda_1",
    "log10_population_lambda_2", "log10_population_lambda_3",
    "population_weight_0", "population_weight_1", "population_weight_2", "population_weight_3",
    "output_FINAL", "output_AVERAGE",
)


def type_specification():
    return {
        "schema": "jreferee-search-type-input-v1",
        "fields": list(TYPE_FIELDS),
        "lambda_transform": "float32(log10(float64(lambda))); no clipping",
        "weight_transform": "float32(weight)",
        "output_encoding": "one_hot(FINAL,AVERAGE)",
        "profile_id": "validated metadata, not an additional model feature",
        "conditioning": "base_initial + tanh(type_projection(features))",
        "projection_bias": False,
        "initial_projection": "zeros for a supervised warm start",
    }


def plain(value):
    if isinstance(value, Mapping):
        return {key: plain(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [plain(item) for item in value]
    return value


def type_features(example):
    require(len(example.types) == 9 and len(example.type_ids) == 2, "Invalid type feature shape")
    for value in example.type_ids:
        integer(value, 1, 2, "type ID")
    lambdas, weights = example.types[:5], example.types[5:]
    require(all(type(x) in (int, float) and math.isfinite(x) and x > 0 for x in lambdas),
            "Type lambdas must be finite and positive")
    require(all(type(x) in (int, float) and math.isfinite(x) and 0 <= x <= 1 for x in weights)
            and abs(math.fsum(weights) - 1) <= 1e-10, "Invalid population weights")
    values = tuple(math.log10(x) for x in lambdas) + tuple(weights)
    values += (float(example.type_ids[1] == 1), float(example.type_ids[1] == 2))
    return tuple(struct.unpack("<f", struct.pack("<f", value))[0] for value in values)


def validate_targets(example):
    units = integer(len(example.locations), 1, 34, "acting units")
    require(len(example.counts) == units and len(example.actions) == len(example.probabilities)
            and len(example.actions) > 0, "Invalid complete-action target shape")
    for count in example.counts:
        integer(count, 1, 1_000_000, "option count")
    require(sum(example.counts) == len(example.options), "Packed option count mismatch")
    total = 0.0
    for action, probability in zip(example.actions, example.probabilities):
        require(len(action) == units, "Incomplete candidate action")
        for index, count in zip(action, example.counts):
            integer(index, 0, count - 1, "local action index")
        require(type(probability) in (int, float) and math.isfinite(probability)
                and 0 <= probability <= 1, "Invalid target probability")
        total += probability
    require(abs(total - 1) <= 1e-10, "Target probability mass")


@dataclass(frozen=True)
class CandidateChunk:
    references: tuple
    root_count: int


def candidate_chunks(roots, candidate_limit, max_options):
    integer(candidate_limit, 1, 4096, "candidate minibatch size")
    integer(max_options, 1, 1_000_000, "minibatch option limit")
    require(roots, "Cannot batch an empty root minibatch")
    for root in roots:
        validate_targets(root)
        require(len(root.options) <= max_options, "One candidate exceeds --max-options")
    current, options = [], 0
    for root_index, root in enumerate(roots):
        for candidate, probability in enumerate(root.probabilities):
            if probability == 0:
                continue
            size = len(root.options)
            if current and (len(current) == candidate_limit or options + size > max_options):
                yield CandidateChunk(tuple(current), len(roots))
                current, options = [], 0
            current.append((root_index, candidate))
            options += size
    if current:
        yield CandidateChunk(tuple(current), len(roots))


@dataclass(frozen=True)
class Record:
    cache: int
    index: int
    game: str
    units: int
    options: int
    positive_candidates: int


class PolicyData:
    """One frozen-anchor cohort, retaining the reader's existing game splits."""

    def __init__(self, caches, anchor_sha256):
        self.caches = tuple(sorted(caches, key=lambda cache: cache.fingerprint))
        validate_disjoint(self.caches)
        self.anchor_sha256 = checked_hash(anchor_sha256)
        self.split_seed = self.caches[0].seed
        self.spec = plain(self.caches[0].specification["movement_specification"])
        records = {split: [] for split in SPLITS}
        producers = set()
        for cache_index, cache in enumerate(self.caches):
            require(plain(cache.specification["movement_specification"]) == self.spec,
                    "Movement specifications differ across caches")
            for split in SPLITS:
                for index in range(cache.policy_counts[split]):
                    example = cache.get_policy(split, index)
                    validate_targets(example)
                    type_features(example)
                    game = cache.games[example.metadata["game_index"]]
                    seat = game["configuration"]["seats"][POWERS[example.context[0]]]
                    require(seat["method"] == "DIL_PIKL" and seat["root"] == "COHERENT_GRU_HYBRID",
                            "Training requires recorded COHERENT_GRU_HYBRID DiL targets")
                    require(seat["movement_models"]["anchor"] == self.anchor_sha256,
                            "Recorded human anchor differs from the frozen anchor")
                    producers.add(seat["movement_models"]["checkpoint"])
                    records[split].append(Record(
                        cache_index, index, game["game_id"], len(example.locations), len(example.options),
                        sum(probability > 0 for probability in example.probabilities)))
        require(any(records.values()), "No movement policy targets")
        self.records = MappingProxyType({split: tuple(rows) for split, rows in records.items()})
        self.producer_checkpoints = tuple(sorted(producers))

    def receipts(self):
        return [{"dataset_fingerprint": cache.fingerprint, "cache_manifest_sha256": cache.manifest_hash}
                for cache in self.caches]

    def preflight(self, max_options, training=False):
        integer(max_options, 1, 1_000_000, "minibatch option limit")
        if training:
            require(all(self.records[split] for split in SPLITS),
                    "Training requires nonempty TRAIN and VALIDATION policy examples")
        require(all(record.options <= max_options for rows in self.records.values() for record in rows),
                "One candidate exceeds --max-options; raise the bound without changing masks")

    def batches(self, split, batch_size, seed=None):
        require(split in SPLITS, "Only TRAIN and VALIDATION are supported")
        integer(batch_size, 1, 4096, "root minibatch size")
        records = list(self.records[split])
        if seed is not None:
            random.Random(seed).shuffle(records)
        for start in range(0, len(records), batch_size):
            selected = tuple(records[start:start + batch_size])
            yield selected, [self.caches[row.cache].get_policy(split, row.index) for row in selected]