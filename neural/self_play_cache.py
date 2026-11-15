"""Verified self-play cache snapshots; no ML dependencies or TEST access."""

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import struct
from types import MappingProxyType
from uuid import UUID


SCHEMA = "jreferee-self-play-cache-v1"
MOVEMENT_HASH = "94994ea85911c4f7f8e5b904558e9ed345d639dc4f4d9fb93edb76189fa91ba7"
VALUE_HASH = "c3f18d64047fee435c0b7acca31c47f38e6f5dcdb393400fe18f4a6da2c6a4df"
REWARD = "jreferee-eventual-sos-v3"
SPLITS = ("TRAIN", "VALIDATION")
POWERS = ("NONE", "AUSTRIA", "ENGLAND", "FRANCE", "GERMANY", "ITALY", "RUSSIA", "TURKEY")
SEED_POWERS = ("ENGLAND", "FRANCE", "GERMANY", "ITALY", "AUSTRIA", "RUSSIA", "TURKEY")
SOURCES = {"POLICY", "POLICY_SAMPLE", "BEAM_SEARCH", "HEURISTIC", "EXPLORATION"}
SPLIT_RULE = "SHA256(seed+LF+logical_key):first16hex_unsigned_mod10:0=VALIDATION,else=TRAIN"
TYPE_FIELDS = ("playing_lambda", "population_lambda_0", "population_lambda_1",
               "population_lambda_2", "population_lambda_3", "population_weight_0",
               "population_weight_1", "population_weight_2", "population_weight_3")
MAX_BYTES = 512 * 1024 * 1024
MASK = (1 << 64) - 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low, high, name):
    require(type(value) is int and low <= value <= high, f"Invalid {name}")
    return value


def fields(value, names, name):
    require(type(value) is dict and set(value) == set(names.split()), f"Unexpected {name} fields")
    return value


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def checked_hash(value):
    require(type(value) is str and re.fullmatch("[0-9a-f]{64}", value), "Invalid SHA-256")
    return value


def long_text(value):
    require(type(value) is str and re.fullmatch(r"-?(0|[1-9][0-9]*)", value), "Invalid long string")
    number = integer(int(value), -(1 << 63), (1 << 63) - 1, "long")
    require(str(number) == value, "Noncanonical long string")
    return number


def parse(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def real(text):
        value = float(text)
        require(math.isfinite(value), "Nonfinite JSON number")
        return value

    def constant(text):
        raise ValueError(f"Nonfinite JSON number: {text}")

    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return json.loads(raw, object_pairs_hook=pairs, parse_float=real, parse_constant=constant)


def freeze(value):
    if isinstance(value, dict):
        return MappingProxyType({key: freeze(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(map(freeze, value))
    return value


def split_for(logical_key, seed):
    checked_hash(logical_key)
    integer(seed, -(1 << 63), (1 << 63) - 1, "split seed")
    bucket = int(sha(f"{seed}\n{logical_key}".encode())[:16], 16) % 10
    return "VALIDATION" if bucket == 0 else "TRAIN"


def decision_seed(seed, year, phase, power, stream):
    phase_key = year * 5 + (0 if phase == 1 else 2)
    key = (phase_key * 7 + SEED_POWERS.index(power)) * 3 + stream
    value = (seed + 0x9E3779B97F4A7C15 * (key + 1)) & MASK
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & MASK
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & MASK
    value ^= value >> 31
    return value if value < (1 << 63) else value - (1 << 64)


def selected_index(probabilities, seed):
    state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
    state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
    high = state >> 22
    state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
    draw = ((high << 27) + (state >> 21)) / float(1 << 53)
    total, last = 0.0, 0
    for index, probability in enumerate(probabilities):
        total += probability
        if probability > 0:
            last = index
    ticket = draw * total
    for index in range(last):
        ticket -= probabilities[index]
        if ticket < 0:
            return index
    return last


@dataclass(frozen=True)
class PolicyExample:
    board: tuple
    context: tuple
    locations: tuple
    counts: tuple
    options: tuple
    actions: tuple
    probabilities: tuple
    types: tuple
    type_ids: tuple
    selected: int
    log_priors: tuple
    historical_means: tuple
    metadata: object


@dataclass(frozen=True)
class ValueExample:
    board: tuple
    context: tuple
    target: tuple
    metadata: object


class _Array:
    def __init__(self, raw, code, count, width=1):
        self.raw, self.count = raw, count
        self.record = struct.Struct("<" + str(width) + code)
        require(len(raw) == count * self.record.size, "Numeric array shape mismatch")

    def row(self, index):
        integer(index, 0, self.count - 1, "array index")
        return self.record.unpack_from(self.raw, index * self.record.size)

    def scalar(self, index):
        return self.row(index)[0]

    def rows(self, start, end):
        require(0 <= start <= end <= self.count, "Array slice bounds")
        return tuple(self.row(index) for index in range(start, end))

    def scalars(self, start, end):
        return tuple(row[0] for row in self.rows(start, end))


def board_rows(flat):
    return tuple(tuple(flat[start:start + 4]) for start in range(0, 300, 4))


class SelfPlayCache:
    """Own checked bytes. Returned examples contain immutable tuples, not file views."""

    def __init__(self, root):
        self.root = Path(root).absolute()
        require(self.root.is_dir() and not self.root.is_symlink(), "Invalid cache directory")
        self.remaining = MAX_BYTES
        raw = self._read("manifest.json", limit=1024 * 1024)
        self.manifest_hash = sha(raw)
        m = fields(parse(raw), "schema complete byte_order dataset_fingerprint split_seed split_rule "
                   "test_opened games policy_examples value_positions policy_specification game_index "
                   "policy_splits value_manifest", "manifest")
        require(m["schema"] == SCHEMA and m["complete"] is True
                and m["byte_order"] == "LITTLE_ENDIAN" and m["test_opened"] is False
                and m["split_rule"] == SPLIT_RULE, "Unsupported self-play cache")
        self.seed = long_text(m["split_seed"])
        self.fingerprint = checked_hash(m["dataset_fingerprint"])
        fields(m["policy_splits"], "TRAIN VALIDATION", "policy splits")
        value_raw = self._read("value/manifest.json", m["value_manifest"], 1024 * 1024)
        v = fields(parse(value_raw), "schema complete encoding reward_contract dataset_fingerprint "
                   "byte_order test_exported specification splits", "value manifest")
        require(v["schema"] == "jreferee-value-cache-v1" and v["complete"] is True
                and v["encoding"] == "jreferee-neural-value-v1" and v["reward_contract"] == REWARD
                and v["dataset_fingerprint"] == self.fingerprint
                and v["byte_order"] == "LITTLE_ENDIAN" and v["test_exported"] is False,
                "Incompatible child value cache")
        fields(v["splits"], "TRAIN VALIDATION", "value splits")
        spec_raw = self._read("value/specification.json", v["specification"], 1024 * 1024)
        require(sha(spec_raw) == VALUE_HASH, "Unknown frozen value specification")
        value_spec = parse(spec_raw)
        self._spec = value_spec["board_specification"]
        policy_spec = parse(self._read("policy-specification.json", m["policy_specification"], 1024 * 1024))
        expected_spec = {
            "schema": SCHEMA, "movement_specification": self._spec,
            "movement_specification_sha256": MOVEMENT_HASH, "type_fields": list(TYPE_FIELDS),
            "profile_ids": ["NONE", "OPENING", "STANDARD"],
            "output_ids": ["NONE", "FINAL", "AVERAGE"], "target": "COMPLETE_ACTION_DISTRIBUTION",
            "loss": "NEGATIVE_EXPECTED_SUM_OF_CONDITIONAL_LOG_PROBABILITIES_WITH_FULL_LEGAL_MASKS",
            "type_transform": "RAW_FLOAT64", "temperature_schedule": "ADAPTIVE",
            "max_shard_examples": 256, "max_packed_options_or_action_indices": 1_000_000,
        }
        require(json.dumps(policy_spec, sort_keys=True) == json.dumps(expected_spec, sort_keys=True),
                "Unknown policy specification")
        self.specification = freeze(policy_spec)
        self._geometry()
        index_raw = self._read("games.jsonl", m["game_index"])
        require(self.fingerprint == sha(f"{SCHEMA}\n{self.seed}\n{sha(index_raw)}".encode()),
                "Dataset fingerprint mismatch")
        games = self._lines(index_raw)
        require(len(games) == integer(m["games"], 1, 1_000_000, "game count"), "Game count mismatch")
        self._games = games
        self._game_index(games)
        self._policy = {split: [] for split in SPLITS}
        self._values = {}
        self._phases = {}
        self._phase_indices = {}
        actual_policy = [0] * len(games)
        for split in SPLITS:
            reports = m["policy_splits"][split]
            require(type(reports) is list, "Invalid shard list")
            previous_game = -1
            for index, report in enumerate(reports):
                require(type(report) is dict, "Invalid shard report")
                require(report.get("directory") == f"policy/{split.lower()}-{index:05d}", "Unsafe shard path")
                game_index = integer(report.get("game_index"), 0, len(games) - 1, "shard game")
                require(game_index >= previous_game and games[game_index]["split"] == split,
                        "Shard game ordering/split mismatch")
                previous_game = game_index
                arrays, metadata = self._policy_shard(report)
                for row in range(report["examples"]):
                    example = self._example(arrays, metadata[row], row)
                    self._check_policy(example, game_index)
                    self._policy[split].append((arrays, freeze(metadata[row]), row))
                    actual_policy[game_index] += 1
        require(actual_policy == [g["policy_examples"] for g in games], "Per-game policy count mismatch")
        require(sum(actual_policy) == integer(m["policy_examples"], 0, 100_000_000, "policy count"),
                "Total policy count mismatch")
        self._check_phases()
        self._load_values(v)
        require(sum(g["value_positions"] for g in games)
                == integer(m["value_positions"], 0, 100_000_000, "value count"), "Total value count mismatch")
        self.manifest = freeze(m)
        self.games = freeze(games)
        self.policy_counts = MappingProxyType({split: len(self._policy[split]) for split in SPLITS})
        self.value_counts = MappingProxyType({split: self._values[split][0]["board.u8"].count for split in SPLITS})
        self._phases.clear()
        self._phase_indices.clear()

    def _read(self, name, receipt=None, limit=MAX_BYTES):
        relative = Path(name)
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe cache path")
        path = self.root
        for part in relative.parts[:-1]:
            path = path / part
            require(path.is_dir() and not path.is_symlink(), "Invalid cache subdirectory")
        path = self.root / relative
        require(path.is_file() and not path.is_symlink(), f"Not a regular file: {path}")
        size = path.stat().st_size
        require(size <= min(limit, self.remaining), f"Cache input-size limit: {path}")
        if receipt is not None:
            fields(receipt, "bytes sha256", "receipt")
            require(integer(receipt["bytes"], 0, MAX_BYTES, "receipt bytes") == size, "Receipt size mismatch")
            checked_hash(receipt["sha256"])
        with path.open("rb") as stream:
            raw = stream.read(size + 1)
        require(len(raw) == size, "File changed while being read")
        self.remaining -= size
        if receipt is not None:
            require(sha(raw) == receipt["sha256"], f"Checksum mismatch: {path}")
        return raw

    @staticmethod
    def _lines(raw):
        require(not raw or raw.endswith(b"\n"), "Unterminated JSONL")
        lines = raw.splitlines()
        require(all(lines), "Blank JSONL record")
        return [parse(line) for line in lines]

    def _game_index(self, games):
        ids, keys, hashes = set(), set(), set()
        previous = ""
        for index, g in enumerate(games):
            fields(g, "game_index game_id logical_key archive_sha256 split configuration termination "
                   "policy_examples skipped_non_movement skipped_non_dil skipped_zero_units "
                   "value_positions value_status", "game")
            require(integer(g["game_index"], 0, len(games) - 1, "game index") == index, "Game index order")
            require(type(g["game_id"]) is str and str(UUID(g["game_id"])) == g["game_id"], "Invalid game UUID")
            key, fingerprint = checked_hash(g["logical_key"]), checked_hash(g["archive_sha256"])
            require(g["game_id"] not in ids and key not in keys and fingerprint not in hashes, "Duplicate game")
            require(key > previous and g["split"] == split_for(key, self.seed), "Game ordering/split mismatch")
            ids.add(g["game_id"]); keys.add(key); hashes.add(fingerprint)
            previous = key
            for name in ("policy_examples", "skipped_non_movement", "skipped_non_dil",
                         "skipped_zero_units", "value_positions"):
                integer(g[name], 0, 100_000_000, name)
            expected = ("ARTIFICIAL_CUTOFF" if g["termination"] == "ARTIFICIAL_CUTOFF"
                        else "ACCEPTED" if g["value_positions"] else "NO_POSITIONS")
            require(g["termination"] in ("SOLO", "ARTIFICIAL_CUTOFF") and g["value_status"] == expected
                    and (g["termination"] != "ARTIFICIAL_CUTOFF" or g["value_positions"] == 0),
                    "Invalid value admission")
            config = fields(g["configuration"], "last_year game_seed adjudication_trials adjudication_seed seats",
                            "configuration")
            integer(config["last_year"], 1901, (1 << 31) - 1, "last year")
            long_text(config["game_seed"]); long_text(config["adjudication_seed"])
            integer(config["adjudication_trials"], 1, (1 << 31) - 1, "adjudication trials")
            fields(config["seats"], " ".join(POWERS[1:]), "seats")
            for seat in config["seats"].values():
                fields(seat, "method candidates iterations rollouts rollout_limit output root continuation "
                       "movement_models value_model", "seat")
                require(seat["method"] in ("PRIOR", "BEST_RESPONSE", "DIL_PIKL")
                        and seat["output"] in ("FINAL", "AVERAGE")
                        and seat["root"] in ("UNIFORM", "PLAUSIBLE", "COHERENT_GRU_HYBRID")
                        and seat["continuation"] in ("UNIFORM", "PLAUSIBLE"), "Unsupported seat")
                for name in ("candidates", "iterations", "rollouts", "rollout_limit"):
                    integer(seat[name], 1, (1 << 31) - 1, name)
                models = seat["movement_models"]
                require(type(models) is dict and set(models) in (
                    set(), {"anchor"}, {"anchor", "gru", "checkpoint", "dataset"}), "Invalid movement models")
                for value in models.values():
                    checked_hash(value)
                require(seat["root"] != "COHERENT_GRU_HYBRID" or len(models) == 4, "Missing hybrid models")
                if seat["value_model"] is not None:
                    fields(seat["value_model"], "model checkpoint dataset", "value model")
                    require(seat["method"] != "PRIOR", "PRIOR has a value model")
                    for value in seat["value_model"].values():
                        checked_hash(value)

    def _arrays(self, directory, receipts, layout, metadata_name):
        fields(receipts, " ".join(layout) + " " + metadata_name, "numeric files")
        result = {}
        for name, (code, count, width) in layout.items():
            expected = struct.calcsize("<" + code) * count * width
            receipt = fields(receipts[name], "bytes sha256", "receipt")
            require(type(receipt["bytes"]) is int and receipt["bytes"] == expected,
                    f"Array shape mismatch: {name}")
            result[name] = _Array(self._read(f"{directory}/{name}", receipts[name]), code, count, width)
        return result, self._lines(self._read(f"{directory}/{metadata_name}", receipts[metadata_name]))

    @staticmethod
    def _offsets(array, terminal, strict):
        require(array.scalar(0) == 0 and array.scalar(array.count - 1) == terminal, "Terminal offset mismatch")
        previous = 0
        for index in range(1, array.count):
            current = array.scalar(index)
            require(previous < current <= terminal if strict else previous <= current <= terminal,
                    "Nonmonotone offsets")
            previous = current

    def _policy_shard(self, report):
        fields(report, "directory game_index examples slots options candidates action_indices files", "shard")
        e = integer(report["examples"], 1, 256, "shard examples")
        s = integer(report["slots"], e, e * 34, "slots")
        o = integer(report["options"], s, 1_000_000, "options")
        c = integer(report["candidates"], e, 1_000_000, "candidates")
        a = integer(report["action_indices"], c, 1_000_000, "action indices")
        layout = {
            "board.u8": ("B", e, 300), "context.i32": ("i", e, 3),
            "types.f64": ("d", e, 9), "type_ids.i32": ("i", e, 2),
            "example_offsets.i64": ("q", e + 1, 1), "slot_locations.u8": ("B", s, 1),
            "option_offsets.i64": ("q", s + 1, 1), "options.u8": ("B", o, 5),
            "candidate_offsets.i64": ("q", e + 1, 1), "action_offsets.i64": ("q", c + 1, 1),
            "actions.i32": ("i", a, 1), "policy.f64": ("d", c, 1),
            "log_prior.f64": ("d", c, 1), "mean_values.f64": ("d", c, 1), "selected.i32": ("i", e, 1),
        }
        arrays, metadata = self._arrays(report["directory"], report["files"], layout, "metadata.jsonl")
        require(len(metadata) == e, "Policy metadata count mismatch")
        for name, end in (("example_offsets.i64", s), ("option_offsets.i64", o),
                          ("candidate_offsets.i64", c), ("action_offsets.i64", a)):
            self._offsets(arrays[name], end, True)
        return arrays, metadata

    @staticmethod
    def _example(a, metadata, row):
        start, end = a["example_offsets.i64"].scalars(row, row + 2)
        offsets = a["option_offsets.i64"].scalars(start, end + 1)
        first, last = a["candidate_offsets.i64"].scalars(row, row + 2)
        actions = []
        for candidate in range(first, last):
            lo, hi = a["action_offsets.i64"].scalars(candidate, candidate + 2)
            require(hi - lo == end - start, "Candidate action length mismatch")
            actions.append(a["actions.i32"].scalars(lo, hi))
        return PolicyExample(
            board_rows(a["board.u8"].row(row)), a["context.i32"].row(row),
            a["slot_locations.u8"].scalars(start, end),
            tuple(right - left for left, right in zip(offsets, offsets[1:])),
            a["options.u8"].rows(offsets[0], offsets[-1]), tuple(actions),
            a["policy.f64"].scalars(first, last), a["types.f64"].row(row), a["type_ids.i32"].row(row),
            a["selected.i32"].scalar(row), a["log_prior.f64"].scalars(first, last),
            a["mean_values.f64"].scalars(first, last), freeze(metadata))

    def _check_policy(self, ex, game_index):
        g = self._games[game_index]
        meta = ex.metadata
        require(set(meta) == {"game_index", "phase_index", "power", "search_seed", "rollout_seed",
                             "selection_seed", "search_samples", "candidate_sources"}, "Policy metadata fields")
        require(type(meta["game_index"]) is int and meta["game_index"] == game_index, "Policy game link mismatch")
        phase_index = integer(meta["phase_index"], 0, (1 << 31) - 1, "phase index")
        power, phase, year = ex.context
        require(1 <= power <= 7 and phase in (1, 2) and 1901 <= year <= g["configuration"]["last_year"],
                "Invalid movement context")
        require(meta["power"] == POWERS[power], "Policy power mismatch")
        seat = g["configuration"]["seats"][POWERS[power]]
        require(seat["method"] == "DIL_PIKL" and len(ex.actions) <= seat["candidates"], "Invalid policy seat/pool")
        require(integer(meta["search_samples"], 1, (1 << 31) - 1, "search samples") == seat["iterations"],
                "Search sample count mismatch")
        require(type(meta["candidate_sources"]) is tuple and len(meta["candidate_sources"]) == len(ex.actions)
                and all(type(source) is str and source in SOURCES for source in meta["candidate_sources"]),
                "Invalid candidate sources")
        opening = year == 1901 and phase == 1
        population = (0.01, 10 ** -1.5, 0.1, 10 ** -0.5) if opening else (0.0001, 0.001, 0.01, 0.1)
        expected = ((0.01 if opening else 0.0001),) + population + (0.25,) * 4
        require(all(math.isfinite(x) and math.isclose(x, y, rel_tol=1e-14, abs_tol=0)
                    for x, y in zip(ex.types, expected)), "Type features disagree with root profile")
        require(ex.type_ids == (1 if opening else 2, 1 if seat["output"] == "FINAL" else 2), "Type IDs mismatch")
        self._check_board(ex.board)
        expected_locations = sorted(row[2] for row in ex.board if row[1] == power)
        require(tuple(expected_locations) == ex.locations, "Issuing slots mismatch")
        self._check_options(ex)
        require(len(set(ex.actions)) == len(ex.actions), "Duplicate complete action")
        for action in ex.actions:
            require(all(0 <= label < count for label, count in zip(action, ex.counts)), "Local action index bounds")
        total = 0.0
        for probability, prior, mean in zip(ex.probabilities, ex.log_priors, ex.historical_means):
            require(math.isfinite(probability) and 0 <= probability <= 1, "Invalid policy probability")
            require(math.isfinite(prior) and prior <= 0 or prior == -math.inf, "Invalid anchor log prior")
            require(prior != -math.inf or probability == 0, "Policy mass on zero anchor prior")
            require(math.isfinite(mean) and -1e-12 <= mean <= 1 + 1e-12, "Invalid historical mean")
            total += probability
        require(abs(total - 1) <= 1e-10, "Policy probability mass")
        integer(ex.selected, 0, len(ex.actions) - 1, "selected candidate")
        require(ex.probabilities[ex.selected] > 0, "Selected zero-probability candidate")
        for stream, name in enumerate(("search_seed", "rollout_seed", "selection_seed")):
            require(long_text(meta[name]) == decision_seed(long_text(g["configuration"]["game_seed"]),
                                                          year, phase, POWERS[power], stream),
                    "Decision seed mismatch")
        require(selected_index(ex.probabilities, long_text(meta["selection_seed"])) == ex.selected,
                "Seeded selection mismatch")
        key = (game_index, year, phase)
        entry = self._phases.setdefault(key, [ex.board, set(), phase_index])
        require(entry[0] == ex.board and entry[2] == phase_index, "Inconsistent phase board/index")
        require(power not in entry[1], "Duplicate policy decision")
        entry[1].add(power)
        old = self._phase_indices.setdefault((game_index, phase_index), (year, phase))
        require(old == (year, phase), "Phase index reused for another moment")

    def _geometry(self):
        s = self._spec
        self._canonical, self._rows = s["canonical_locations"], s["location_board_rows"]
        self._parents = {self._canonical[i] for i in range(1, 82) if self._canonical[i] != i}
        self._convoy = set(map(tuple, s["potential_convoy"]))
        self._move = {1: set(map(tuple, s["army_direct"])) | self._convoy,
                      2: set(map(tuple, s["fleet_direct"]))}
        self._support = {1: set(map(tuple, s["army_support"])), 2: set(map(tuple, s["fleet_support"]))}
        self._canonical_move = {kind: {(origin, self._canonical[target]) for origin, target in edges}
                                for kind, edges in self._move.items()}

    def _check_board(self, board):
        units, centers = 0, [0] * 8
        for index, (kind, owner, location, control) in enumerate(board):
            require(kind in (0, 1, 2) and 0 <= owner <= 7 and 0 <= location <= 81 and 0 <= control <= 7,
                    "Board vocabulary ID")
            require(bool(kind) == bool(owner) == bool(location), "Partial board occupancy")
            require(not control or self._spec["supply_centers"][index], "Owner on non-center")
            if control:
                centers[control] += 1
            if kind:
                units += 1
                require(self._rows[location] == index, "Unit in wrong board row")
                terrain = self._spec["terrain"][index]
                require((kind == 1 and terrain != 2 and self._canonical[location] == location)
                        or (kind == 2 and terrain != 0 and location not in self._parents), "Invalid unit coast")
        require(units <= 34 and 0 < sum(centers) <= 34 and max(centers) < 18, "Invalid nonterminal board")

    def _check_options(self, ex):
        offset = 0
        for location, count in zip(ex.locations, ex.counts):
            issuer = ex.board[self._rows[location]]
            choices = ex.options[offset:offset + count]
            offset += count
            previous, holds = None, 0
            for kind, origin, order, target, auxiliary in choices:
                require(kind == issuer[0] and origin == location and order in (1, 2, 3, 4)
                        and 0 <= target <= 81 and 0 <= auxiliary <= 81, "Option vocabulary/issuer mismatch")
                key = ((0, 1, 2, 3, 0)[order], target, auxiliary)
                require(previous is None or previous < key, "Duplicate or unsorted options")
                previous = key
                if order == 1:
                    require(target == auxiliary == 0, "Malformed HOLD")
                    holds += 1
                elif order == 2:
                    require(auxiliary == 0 and (origin, target) in self._move[kind], "Illegal MOVE geometry")
                else:
                    require(target > 0 and target == self._canonical[target]
                            and target != self._canonical[origin], "Invalid referenced province")
                    reference = ex.board[self._rows[target]]
                    require(reference[0] != 0, "Absent referenced unit")
                    if order == 3:
                        require(auxiliary == self._canonical[auxiliary] and target != auxiliary
                                and (origin, auxiliary or target) in self._support[kind], "Illegal SUPPORT")
                        require(not auxiliary or (reference[2], auxiliary) in self._canonical_move[reference[0]],
                                "Impossible supported move")
                    else:
                        require(kind == 2 and self._spec["terrain"][self._rows[origin]] == 2
                                and reference[0] == 1 and (target, auxiliary) in self._convoy, "Illegal CONVOY")
            require(holds == 1, "Missing HOLD")

    def _check_phases(self):
        for (game_index, year, phase), (board, powers, _) in self._phases.items():
            seats = self._games[game_index]["configuration"]["seats"]
            expected = {row[1] for row in board if row[0] and seats[POWERS[row[1]]]["method"] == "DIL_PIKL"}
            require(powers == expected, "Missing acting power in movement phase")
        for game_index in range(len(self._games)):
            moments = [moment for (g, index), moment in sorted(self._phase_indices.items()) if g == game_index]
            require(all(left < right for left, right in zip(moments, moments[1:])), "Policy chronology")

    def _load_values(self, manifest):
        by_id = {g["game_id"]: index for index, g in enumerate(self._games)}
        admitted, supported = set(), set()
        for split in SPLITS:
            report = fields(manifest["splits"][split], "games positions source_sha256 files", "value split")
            count = integer(report["games"], 0, len(self._games), "value games")
            n = integer(report["positions"], 0, 100_000_000, "value positions")
            checked_hash(report["source_sha256"])
            layout = {"board.u8": ("B", n, 300), "context.i32": ("i", n, 2),
                      "targets.f32": ("f", count, 7), "game_offsets.i64": ("q", count + 1, 1)}
            arrays, metadata = self._arrays(f"value/{split.lower()}", report["files"], layout, "games.jsonl")
            require(len(metadata) == count, "Value metadata count mismatch")
            self._offsets(arrays["game_offsets.i64"], n, True)
            for index, row in enumerate(metadata):
                fields(row, "source game_key payload_sha256 communication_type start end", "value game")
                require(type(row["game_key"]) is str, "Invalid value game key")
                game_index = by_id.get(row["game_key"])
                require(game_index is not None and game_index not in admitted, "Unknown/duplicate value game")
                g = self._games[game_index]
                require(g["split"] == split and g["value_status"] == "ACCEPTED"
                        and row["source"] == "JREFEREE_SELF_PLAY" and row["communication_type"] == "NO_PRESS"
                        and row["payload_sha256"] == g["archive_sha256"], "Value admission/game link mismatch")
                start, end = arrays["game_offsets.i64"].scalars(index, index + 2)
                require(integer(row["start"], 0, n, "value start") == start
                        and integer(row["end"], 0, n, "value end") == end
                        and end - start == g["value_positions"], "Value game offsets/count mismatch")
                target = arrays["targets.f32"].row(index)
                require(all(x in (0.0, 1.0) for x in target) and sum(target) == 1.0, "Invalid solo value target")
                previous = None
                for position in range(start, end):
                    phase, year = arrays["context.i32"].row(position)
                    require(phase in (1, 2, 3) and 1901 <= year <= g["configuration"]["last_year"]
                            and (previous is None or previous < (year, phase)), "Value chronology/context")
                    previous = (year, phase)
                    board = board_rows(arrays["board.u8"].row(position))
                    self._check_board(board)
                    key = (game_index, year, phase)
                    require(key not in self._phases or board == self._phases[key][0], "Policy/value board mismatch")
                    supported.add(key)
                admitted.add(game_index)
            self._values[split] = (arrays, freeze(metadata))
        require(admitted == {i for i, g in enumerate(self._games) if g["value_status"] == "ACCEPTED"},
                "Missing accepted value game")
        require(all(key[0] not in admitted or key in supported for key in self._phases),
                "Missing value position for a policy root")

    def get_policy(self, split, index):
        require(split in SPLITS, "Only TRAIN and VALIDATION may be opened")
        integer(index, 0, len(self._policy[split]) - 1, "policy index")
        arrays, metadata, row = self._policy[split][index]
        return self._example(arrays, metadata, row)

    def policies(self, split):
        require(split in SPLITS, "Only TRAIN and VALIDATION may be opened")
        for index in range(len(self._policy[split])):
            yield self.get_policy(split, index)

    def values(self, split):
        require(split in SPLITS, "Only TRAIN and VALIDATION may be opened")
        arrays, metadata = self._values[split]
        for index, row in enumerate(metadata):
            target = arrays["targets.f32"].row(index)
            for position in range(row["start"], row["end"]):
                yield ValueExample(board_rows(arrays["board.u8"].row(position)),
                                   arrays["context.i32"].row(position), target, row)


def validate_disjoint(caches):
    caches = tuple(caches)
    require(caches, "Supply at least one cache")
    ids, keys, hashes = set(), set(), set()
    for cache in caches:
        require(cache.seed == caches[0].seed, "Mixed split seeds across caches")
        for game in cache.games:
            require(game["game_id"] not in ids and game["logical_key"] not in keys
                    and game["archive_sha256"] not in hashes, "Duplicate game across caches")
            ids.add(game["game_id"]); keys.add(game["logical_key"]); hashes.add(game["archive_sha256"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    cache = SelfPlayCache(args.cache)
    print("BEGIN SELF-PLAY CACHE READBACK")
    for split in SPLITS:
        print(f"{split}: policy_examples={cache.policy_counts[split]} value_positions={cache.value_counts[split]}")
    print(f"Games={len(cache.games)} policy_examples={sum(cache.policy_counts.values())} "
          f"value_positions={sum(cache.value_counts.values())}")
    print(f"Dataset fingerprint: {cache.fingerprint}")
    print(f"Manifest SHA-256: {cache.manifest_hash}")
    print("No training, model loading or TEST access.")
    print("END SELF-PLAY CACHE READBACK")


if __name__ == "__main__":
    main()