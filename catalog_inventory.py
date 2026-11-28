"""Read-only inventory of additional DiploBN and webDiplomacy training candidates."""

import argparse
from collections import Counter, defaultdict
from contextlib import ExitStack, closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import uuid


EXPECTED_INDEX = "45107d9f1ebe78f4572deef3504f88f24057a362cfab94421542b429eb0454cc"
SPLITS = ("TRAIN", "VALIDATION", "TEST")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def split(source, key):
    bucket = int(sha(f"7\n{source}\n{key}".encode("utf-8"))[:16], 16) % 100
    return "TRAIN" if bucket < 80 else "VALIDATION" if bucket < 90 else "TEST"


def identity(source, key):
    return {"source": source, "game_key": key}


def object_json(raw, context):
    try:
        result = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError(f"Invalid JSON for {context}: {error.msg}") from error
    require(isinstance(result, dict), f"Expected a JSON object for {context}")
    return result


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None


def query(connection, sql, parameters=()):
    with closing(connection.execute(sql, parameters)) as cursor:
        return cursor.fetchall()


def capture(connection, expected_source):
    rows = query(connection, """
        SELECT source_type, external_key, source_payload_sha256
        FROM catalog_game ORDER BY source_type, external_key
    """)
    result = {}
    for source, key, fingerprint in rows:
        require(source == expected_source, f"Unexpected source in {expected_source} catalog: {source}")
        require(isinstance(key, str) and key.strip(), f"Invalid {source} game key")
        require(valid_hash(fingerprint), f"Invalid payload receipt for {source}/{key}")
        require((source, key) not in result, f"Duplicate catalog identity: {source}/{key}")
        result[source, key] = fingerprint
    return result


def communication(payload, source, key):
    context = f"{source}/{key}"
    document = object_json(payload, context)
    field = "CommunicationType"
    if source == "WEBDIPLOMACY":
        require(document.get("schema") == "jreferee-webdiplomacy-raw-v1"
                and type(document.get("gameID")) is int and str(document["gameID"]) == key,
                f"Unexpected webDiplomacy envelope for {context}")
        for name in ("game", "history", "variant"):
            part = document.get(name)
            require(isinstance(part, dict) and isinstance(part.get("json"), str),
                    f"Missing embedded {name} JSON for {context}")
            require(sha(part["json"].encode("utf-8")) == part.get("sha256"),
                    f"Embedded {name} checksum differs for {context}")
        document = object_json(document["game"]["json"], context + "/game")
        require(type(document.get("schema")) is int and document["schema"] == 1
                and type(document.get("gameID")) is int and str(document["gameID"]) == key
                and document.get("file") == "game", f"Unexpected game header for {context}")
        variant = document.get("variant")
        require(isinstance(variant, dict) and type(variant.get("id")) is int and variant["id"] == 1
                and variant.get("name") == "Classic" and document.get("phase") == "Finished"
                and document.get("playerTypes") == "Members" and document.get("isSandbox") is False
                and document.get("gameOver") in ("Won", "Drawn"),
                f"Imported game differs from the scraper selection contract: {context}")
        field = "pressType"
    label = document.get(field)
    status = "MISSING" if label is None else "LITERAL"
    if label is not None and (not isinstance(label, str) or not label.strip()):
        label, status = None, "MALFORMED"
    return {"field": field, "label": label, "status": status}


def inventory(catalogs, index):
    require(set(catalogs) == {"DIPLOBN", "WEBDIPLOMACY"}, "Specify both source-specific catalogs")
    raw = index.read_bytes()
    require(sha(raw) == EXPECTED_INDEX, "The original games.jsonl differs from its reviewed receipt")
    prior = {}
    for number, line in enumerate(raw.splitlines(), 1):
        row = object_json(line, f"previous index line {number}")
        source, key = row["source"], row["game_key"]
        require(source in catalogs and isinstance(key, str) and key.strip(), "Invalid previous identity")
        require(valid_hash(row["payload_sha256"]), f"Invalid previous hash: {source}/{key}")
        require(row["split"] == split(source, key), f"Previous split differs: {source}/{key}")
        require((source, key) not in prior, f"Duplicate previous identity: {source}/{key}")
        prior[source, key] = row["payload_sha256"]
    require(prior, "The original game index is empty")
    paths = {source: Path(path).resolve() for source, path in catalogs.items()}
    for path in paths.values():
        require(path.is_file(), f"Catalog database is missing: {path}")
    ordered = list(paths.values())
    require(not any(path.samefile(other) for i, path in enumerate(ordered) for other in ordered[:i]),
            "Catalog paths refer to the same database")

    snapshots, captured, connections = {}, {}, {}
    candidates, duplicates = [], []
    counts = {source: Counter() for source in catalogs}
    labels = {source: Counter() for source in catalogs}
    with ExitStack() as stack:
        for source, path in paths.items():
            print(f"[INVENTORY] Capturing {source} catalog metadata", flush=True)
            connection = stack.enter_context(closing(sqlite3.connect(
                path.as_uri() + "?mode=ro", uri=True, isolation_level=None, timeout=5)))
            connection.execute("PRAGMA query_only=ON")
            connections[source] = connection
            captured[source] = capture(connection, source)
            rows = [[s, key, fingerprint] for (s, key), fingerprint in captured[source].items()]
            snapshots[source] = {
                "database": str(path), "captured_games": len(rows),
                "captured_at_utc": datetime.now(timezone.utc).isoformat(),
                "metadata_sha256": sha(json.dumps(rows, separators=(",", ":")).encode("utf-8")),
            }
        current = {key: value for catalog in captured.values() for key, value in catalog.items()}
        missing = [identity(*key) for key in prior if key not in current]
        changed = [identity(*key) for key in prior if key in current and prior[key] != current[key]]
        baseline_ok = not missing and not changed
        owners = defaultdict(set)
        for mapping in (prior, current):
            for key, fingerprint in mapping.items():
                owners[fingerprint].add(key)
        for (source, key), fingerprint in sorted(current.items()):
            if (source, key) in prior:
                continue
            purpose = split(source, key)
            counts[source][purpose] += 1
            if purpose != "TRAIN" or not baseline_ok:
                continue
            row = dict(identity(source, key), payload_sha256=fingerprint, split=purpose)
            others = owners[fingerprint] - {(source, key)}
            if others:
                row["other_identities"] = [
                    dict(identity(s, k), split=split(s, k), in_previous_export=(s, k) in prior)
                    for s, k in sorted(others)
                ]
                duplicates.append(row)
                continue
            found = query(connections[source], """
                SELECT source_payload, source_payload_sha256 FROM catalog_game
                WHERE source_type=? AND external_key=?
            """, (source, key))
            require(len(found) == 1 and found[0][1] == fingerprint,
                    f"Captured entry changed during inventory: {source}/{key}")
            payload = found[0][0]
            require(isinstance(payload, str) and sha(payload.encode("utf-8")) == fingerprint,
                    f"Raw payload checksum differs: {source}/{key}")
            row["communication"] = communication(payload, source, key)
            label = row["communication"]
            labels[source][label["status"], label["label"]] += 1
            candidates.append(row)
            if len(candidates) % 100 == 0:
                print(f"[INVENTORY] Read metadata for {len(candidates)} new TRAIN candidates", flush=True)
        for source, connection in connections.items():
            latest = capture(connection, source)
            require(all(latest.get(key) == value for key, value in captured[source].items()),
                    f"Captured {source} entries changed or disappeared during inventory; rerun")
            snapshots[source]["later_imports_not_included"] = len(latest) - len(captured[source])

    return {
        "schema": "jreferee-catalog-inventory-v1", "complete": True, "admission_performed": False,
        "split_seed": 7, "catalogs": snapshots,
        "previous_index": {"file": str(index.resolve()), "bytes": len(raw), "sha256": sha(raw)},
        "previous_export_games": len(prior), "baseline_unchanged": baseline_ok,
        "previous_games_missing": missing, "previous_payload_receipts_changed": changed,
        "new_games_by_source_and_split": {
            source: {purpose: count[purpose] for purpose in SPLITS} for source, count in counts.items()
        },
        "new_train_metadata_candidates": {
            source: sum(count.values()) for source, count in labels.items()
        },
        "new_train_communication": {
            source: [{"status": status, "label": label, "games": count}
                     for (status, label), count in sorted(values.items(), key=lambda item: (
                         item[0][0], item[0][1] or ""))]
            for source, values in labels.items()
        },
        "new_train_exact_payload_duplicates": duplicates, "candidates": candidates,
        "notes": [
            "Only new, nonduplicate TRAIN payloads were read; a baseline mismatch blocks payload reads.",
            "Original and new VALIDATION/TEST payloads were not opened or reassigned.",
            "Original payload receipts were compared; their raw bytes were not rehashed.",
            "Short autocommit reads allow continued imports; each catalog has its own capture time.",
            "Exact raw-hash screening does not establish semantic or cross-provider deduplication.",
            "Literal press labels remain source-specific; Members does not prove human-only play.",
            "Metadata candidates still need source translation, legality/replay checks and admission.",
        ],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diplobn", type=Path, default=Path("data/diplobn-catalog.sqlite"))
    parser.add_argument("--webdiplomacy", type=Path, default=Path("data/webdiplomacy-catalog.sqlite"))
    parser.add_argument("--index", type=Path, default=Path("data/training-movement-v4/games.jsonl"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    output = args.output if args.output is not None else Path("data") / f"catalog-inventory-{uuid.uuid4().hex}.json"
    require(output.parent.is_dir(), f"Output parent does not exist: {output.parent}")
    require(not output.exists() and not output.is_symlink(), f"Output already exists: {output}")
    result = inventory({"DIPLOBN": args.diplobn, "WEBDIPLOMACY": args.webdiplomacy}, args.index)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, indent=2, allow_nan=False)
        stream.write("\n")
    summary = {key: result[key] for key in (
        "baseline_unchanged", "new_games_by_source_and_split",
        "new_train_metadata_candidates", "new_train_communication",
    )}
    summary["captured_games"] = {source: row["captured_games"] for source, row in result["catalogs"].items()}
    summary["new_train_exact_payload_duplicates"] = len(result["new_train_exact_payload_duplicates"])
    print(json.dumps(summary, indent=2))
    print(f"Report: {output.resolve()}")
    if not result["baseline_unchanged"]:
        print("WARNING: original catalog entries differ. New payloads were not read; review the report before export.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())