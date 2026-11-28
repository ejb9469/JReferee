"""Run a bounded, read-only TRAIN source-admission diagnostic."""

import argparse
from collections import Counter, defaultdict
from contextlib import ExitStack, closing
import json
from pathlib import Path
import sqlite3
import subprocess
from tempfile import TemporaryDirectory
import uuid

from catalog_inventory import (
    EXPECTED_INDEX, capture, object_json, query, require, sha, split, valid_hash,
)
from webdiplomacy_fixtures import EXPECTED_INVENTORY, LABELS, fields, project


PER_LABEL = 4
MAX_PAYLOAD_BYTES = 16 * 1024 * 1024


def encoded(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def selected(report):
    require(report["schema"] == "jreferee-catalog-inventory-v1" and report["complete"] is True
            and report["baseline_unchanged"] is True and report["admission_performed"] is False
            and report["split_seed"] == 7, "Unexpected inventory contract")
    groups, seen = defaultdict(list), set()
    for row in report["candidates"]:
        identity = row["source"], row["game_key"]
        require(identity not in seen and row["split"] == split(*identity) == "TRAIN"
                and valid_hash(row["payload_sha256"]), "Invalid or repeated TRAIN candidate")
        seen.add(identity)
        if row["source"] == "WEBDIPLOMACY":
            metadata = row["communication"]
            require(metadata["status"] == "LITERAL" and metadata["field"] == "pressType"
                    and metadata["label"] in LABELS, "Unexpected press metadata")
            groups[metadata["label"]].append(row)
    result = []
    for label in LABELS:
        require(len(groups[label]) >= PER_LABEL, f"Insufficient reviewed candidates for {label}")
        result.extend(sorted(groups[label], key=lambda row: (
            sha(("jreferee-webdip-fixtures-v1\n" + row["game_key"]).encode()), row["game_key"]))[:PER_LABEL])
    return result


def diplobn_projection(payload):
    game = object_json(payload, "DiploBN")
    phases = game.get("GamePhases")
    require(isinstance(phases, list) and phases, "Missing DiploBN GamePhases")
    kept = []
    for phase in phases:
        value = fields(phase, {"Phase": int, "Status": str, "Units": dict, "SupplyCenters": dict},
                       "DiploBN phase")
        kept.append(value)
    return {"GamePhases": kept}


def classify(references, candidates, raw_owners):
    trajectories, prefixes = defaultdict(list), defaultdict(list)
    all_rows = references + candidates
    for row in all_rows:
        if row["status"] == "FINGERPRINTED":
            identity = {"source": row["source"], "game_key": row["game_key"]}
            trajectories[row["trajectory_sha256"]].append(identity)
            if row["prefix_sha256"] is not None:
                prefixes[row["prefix_sha256"]].append(identity)
    covered = all(row["status"] == "FINGERPRINTED" and row["prefix_sha256"] is not None
                  for row in references)
    for row in candidates:
        identity = row["source"], row["game_key"]
        blockers = ["HELD_OUT_SEMANTIC_FINGERPRINTS_UNAVAILABLE"]
        if not covered:
            blockers.append("INCOMPLETE_TRAIN_REFERENCE_FINGERPRINTS")
        raw_matches = sorted(raw_owners[row["source_payload_sha256"]] - {identity})
        row["raw_collisions"] = [{"source": source, "game_key": key, "split": split(source, key)}
                                 for source, key in raw_matches]
        if raw_matches:
            blockers.append("RAW_PAYLOAD_COLLISION")
        row["trajectory_collisions"], row["prefix_collisions"] = [], []
        row["replay_review_clear"] = False
        if row["status"] != "FINGERPRINTED":
            blockers.append("INVALID_SOURCE")
        else:
            for field, index in (("trajectory", trajectories), ("prefix", prefixes)):
                fingerprint = row[field + "_sha256"]
                row[field + "_collisions"] = [
                    other for other in index.get(fingerprint, [])
                    if (other["source"], other["game_key"]) != identity
                ]
                if row[field + "_collisions"]:
                    blockers.append(field.upper() + "_COLLISION")
            if row["prefix_sha256"] is None:
                blockers.append("INSUFFICIENT_PREFIX_LENGTH")
            replay = row["replay"]
            if replay["findings"]:
                blockers.append("REPLAY_REVIEW_REQUIRED")
            elif replay["counts"].get("movements_resolved", 0) != replay["counts"]["movement_roots"]:
                blockers.append("INCOMPLETE_REPLAY")
            else:
                row["replay_review_clear"] = True
        row["admission_blockers"] = blockers
        row["admitted"] = False
    return covered


def pilot(inventory_path, index_path, catalogs, classpath, output, java="java"):
    require(output.parent.is_dir() and not output.exists() and not output.is_symlink(),
            "Report must be a new file in an existing directory")
    inventory_raw, index_raw = inventory_path.read_bytes(), index_path.read_bytes()
    require(sha(inventory_raw) == EXPECTED_INVENTORY, "Inventory receipt differs")
    require(sha(index_raw) == EXPECTED_INDEX, "Frozen game-index receipt differs")
    inventory = object_json(inventory_raw, "inventory")
    candidates = selected(inventory)
    prior = {}
    for line in index_raw.splitlines():
        row = object_json(line, "frozen game index")
        identity = row["source"], row["game_key"]
        require(identity not in prior and row["split"] == split(*identity)
                and valid_hash(row["payload_sha256"]), "Invalid frozen game identity")
        prior[identity] = row["payload_sha256"]
    references = [
        {"source": source, "game_key": key, "payload_sha256": fingerprint, "split": "TRAIN"}
        for (source, key), fingerprint in sorted(prior.items())
        if source == "DIPLOBN" and split(source, key) == "TRAIN"
    ]
    references += [row for row in inventory["candidates"] if row["source"] == "DIPLOBN"]
    require(len(prior) == 1192 and len(references) == 943 and len(candidates) == 16,
            "The reviewed pilot cohort differs")
    paths = {source: Path(path).resolve() for source, path in catalogs.items()}
    require(set(paths) == {"DIPLOBN", "WEBDIPLOMACY"} and all(path.is_file() for path in paths.values()),
            "Both existing catalogs are required")
    require(not paths["DIPLOBN"].samefile(paths["WEBDIPLOMACY"]), "Catalog paths alias the same file")
    rows = references + candidates
    captured, latest, connections, rejected, requested = {}, {}, {}, {}, []
    with TemporaryDirectory(prefix="webdip-admission-", dir=output.parent) as temporary, ExitStack() as stack:
        temporary = Path(temporary)
        for source, path in paths.items():
            connection = stack.enter_context(closing(sqlite3.connect(
                path.as_uri() + "?mode=ro", uri=True, isolation_level=None, timeout=5)))
            connection.execute("PRAGMA query_only=ON")
            connections[source] = connection
            captured.update(capture(connection, source))
        require(all(captured.get(identity) == fingerprint for identity, fingerprint in prior.items()),
                "Original catalog receipts changed or disappeared")
        require(all(captured.get((row["source"], row["game_key"])) == row["payload_sha256"] for row in rows),
                "A reviewed TRAIN receipt changed or disappeared")
        requests = temporary / "requests.jsonl"
        with requests.open("x", encoding="utf-8", newline="\n") as stream:
            for number, row in enumerate(rows, 1):
                source, key = row["source"], row["game_key"]
                require(split(source, key) == "TRAIN", "Held-out payload read prohibited")
                found = query(connections[source], """
                    SELECT source_payload, source_payload_sha256 FROM catalog_game
                    WHERE source_type=? AND external_key=?
                """, (source, key))
                require(len(found) == 1 and found[0][1] == row["payload_sha256"],
                        f"TRAIN receipt changed: {source}/{key}")
                payload = found[0][0]
                require(isinstance(payload, str) and sha(payload.encode("utf-8")) == row["payload_sha256"],
                        f"Raw payload checksum differs: {source}/{key}")
                require(len(payload.encode("utf-8")) <= MAX_PAYLOAD_BYTES,
                        f"Raw payload exceeds the pilot bound: {source}/{key}")
                try:
                    projection = project(payload, row) if source == "WEBDIPLOMACY" else diplobn_projection(payload)
                except ValueError as error:
                    rejected[source, key] = {
                        "source": source, "game_key": key, "source_payload_sha256": row["payload_sha256"],
                        "status": "INVALID_SOURCE", "detail": str(error),
                    }
                    continue
                content = encoded(projection)
                request = {
                    "source": source, "game_key": key, "source_payload_sha256": row["payload_sha256"],
                    "content_sha256": sha(content.encode("utf-8")), "content": content,
                }
                line = encoded(request)
                require(len(line.encode("utf-8")) <= MAX_PAYLOAD_BYTES, "Projection exceeds the pilot bound")
                stream.write(line + "\n")
                requested.append({name: value for name, value in request.items() if name != "content"})
                if number % 100 == 0:
                    print(f"[PREPARE] Verified {number}/{len(rows)} TRAIN payloads", flush=True)
        results_path = temporary / "results.jsonl"
        if requested:
            subprocess.run([java, "-Xmx1g", "-cp", classpath, "_app.TrainingSourcePilotApp",
                            str(requests), str(results_path)], check=True, timeout=1800)
            results = [object_json(line, "Java result") for line in results_path.read_bytes().splitlines()]
        else:
            results = []
        require(len(results) == len(requested), "Incomplete Java result set")
        inspected = dict(rejected)
        for expected, result in zip(requested, results):
            require(all(result.get(name) == value for name, value in expected.items()), "Java result identity differs")
            require(result.get("status") in ("INVALID_SOURCE", "FINGERPRINTED"), "Unknown Java result status")
            inspected[result["source"], result["game_key"]] = result
        for source, connection in connections.items():
            latest.update(capture(connection, source))
        require(all(latest.get(identity) == fingerprint for identity, fingerprint in captured.items()),
                "Captured catalog receipts changed during the pilot")
    require(index_path.read_bytes() == index_raw and inventory_path.read_bytes() == inventory_raw,
            "Pinned metadata changed during the pilot")
    reference_results = [inspected[row["source"], row["game_key"]] for row in references]
    candidate_results = [inspected[row["source"], row["game_key"]] for row in candidates]
    for result, candidate in zip(candidate_results, candidates):
        result["communication"] = candidate["communication"]
    raw_owners = defaultdict(set)
    for identity, fingerprint in latest.items():
        raw_owners[fingerprint].add(identity)
    covered = classify(reference_results, candidate_results, raw_owners)
    summary = Counter(blocker for row in candidate_results for blocker in row["admission_blockers"])
    report = {
        "schema": "jreferee-webdiplomacy-raw-train-pilot-v1",
        "complete": True, "review_required": True, "admission_performed": False,
        "split": "TRAIN", "split_seed": 7,
        "inventory_sha256": sha(inventory_raw), "original_index_sha256": sha(index_raw),
        "candidate_selection": "first four per literal press label by the existing fixture rank; no replacement",
        "reference_games": len(reference_results), "candidate_games": len(candidate_results),
        "train_reference_fingerprints_complete": covered,
        "held_out_semantic_clearance": "BLOCKED_MISSING_FINGERPRINTS",
        "held_out_payloads_opened": 0, "metadata_rechecked": True,
        "catalog_metadata_sha256": sha(encoded([[source, key, value]
                                               for (source, key), value in sorted(latest.items())]).encode("utf-8")),
        "later_imports_not_included": len(latest) - len(captured),
        "replay_clear_candidates": sum(row["replay_review_clear"] for row in candidate_results),
        "human_only_verified": False, "default_order_status": "UNKNOWN",
        "admission_blocker_counts": dict(sorted(summary.items())),
        "references": reference_results, "candidates": candidate_results,
        "limits": [
            "Gameplay fingerprints cover these TRAIN projections only; held-out semantic clearance is unavailable.",
            "Trajectory collisions and shared four-movement-board prefixes require duplicate review.",
            "Shared openings can occur in distinct games; prefix collisions are conservative quarantine evidence.",
            "Absent or invalid reference fingerprints block clearance. Source outcomes do not select replacements.",
            "Raw receipts are screened against captured catalog metadata from every split.",
            "Members does not prove human-only play. Default-order provenance remains UNKNOWN.",
            "Finished ownership and terminal value labels remain unverified. No training dataset is exported.",
        ],
    }
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, indent=2, allow_nan=False)
        stream.write("\n")
    print(json.dumps({name: report[name] for name in (
        "reference_games", "candidate_games", "train_reference_fingerprints_complete",
        "replay_clear_candidates", "held_out_semantic_clearance", "admission_blocker_counts",
    )}, indent=2))
    print(f"Report: {output.resolve()}")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--index", type=Path, default=Path("data/training-movement-v4/games.jsonl"))
    parser.add_argument("--diplobn", type=Path, default=Path("data/diplobn-catalog.sqlite"))
    parser.add_argument("--webdiplomacy", type=Path, default=Path("data/webdiplomacy-catalog.sqlite"))
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or Path("data") / f"webdiplomacy-admission-{uuid.uuid4().hex}.json"
    pilot(args.inventory, args.index, {"DIPLOBN": args.diplobn, "WEBDIPLOMACY": args.webdiplomacy},
          args.classpath, output)
    print("Pilot completed with admission blockers. No games were admitted.")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())