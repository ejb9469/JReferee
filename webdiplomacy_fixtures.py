"""Prepare eight mechanics-only TRAIN fixtures for the webDiplomacy adapter."""

import argparse
from collections import Counter, defaultdict
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import uuid
from zipfile import ZIP_DEFLATED, ZipFile

from catalog_inventory import communication, object_json, query, require, sha, split


EXPECTED_INVENTORY = "d576bb5fcf140ccbbcddd70043f8c1759216d4bf316ce26e834af9fc2d1f832c"
LABELS = ("NoPress", "PublicPressOnly", "Regular", "RulebookPress")
NULL_INT = (int, type(None))
HEADER = {"schema": int, "gameID": int, "file": str, "version": str}
GAME = dict(HEADER, turn=int, phase=str, gameOver=str, pressType=str,
            playerTypes=str, isSandbox=bool)
UNIT = {"countryID": int, "type": str, "terrID": int, "retreating": bool}
CENTER = {"terrID": int, "countryID": int}
ORDER = {
    "countryID": int, "terrID": NULL_INT, "unitType": (str, type(None)), "type": str,
    "toTerrID": NULL_INT, "fromTerrID": NULL_INT, "viaConvoy": bool,
    "success": bool, "dislodged": bool,
}
TERRITORY = {
    "id": int, "name": str, "type": str, "supply": bool, "homeCountryID": NULL_INT,
    "coast": str, "coastParentID": int,
}


def fields(value, shape, context):
    require(isinstance(value, dict), f"Expected an object: {context}")
    result = {}
    for name, expected in shape.items():
        types = expected if isinstance(expected, tuple) else (expected,)
        require(name in value and type(value[name]) in types, f"Unexpected field type: {context}.{name}")
        result[name] = value[name]
    return result


def rows(value, shape, context):
    require(isinstance(value, list), f"Expected an array: {context}")
    return [fields(row, shape, f"{context}[{index}]") for index, row in enumerate(value)]


def select(report):
    require(report["schema"] == "jreferee-catalog-inventory-v1" and report["complete"] is True
            and report["baseline_unchanged"] is True and report["admission_performed"] is False
            and report["split_seed"] == 7, "Inventory is not the reviewed pre-admission cohort")
    groups, seen = defaultdict(list), set()
    for row in report["candidates"]:
        if row["source"] != "WEBDIPLOMACY":
            continue
        key = row["game_key"]
        require(isinstance(key, str) and key.isascii() and key.isdigit() and str(int(key)) == key,
                "Invalid webDiplomacy game key")
        require(key not in seen and row["split"] == split("WEBDIPLOMACY", key) == "TRAIN",
                "Duplicate or non-TRAIN fixture candidate")
        seen.add(key)
        metadata = row["communication"]
        require(metadata["field"] == "pressType" and metadata["status"] == "LITERAL"
                and metadata["label"] in LABELS, "Unexpected candidate press metadata")
        groups[metadata["label"]].append(row)
    chosen = []
    for label in LABELS:
        require(len(groups[label]) >= 2, f"Need two reviewed TRAIN candidates for {label}")
        ranked = sorted(groups[label], key=lambda row: (
            sha(("jreferee-webdip-fixtures-v1\n" + row["game_key"]).encode()), row["game_key"]))
        chosen.extend(ranked[:2])
    return chosen


def project(payload, candidate):
    key = candidate["game_key"]
    require(communication(payload, "WEBDIPLOMACY", key) == candidate["communication"],
            f"Press metadata changed: {key}")
    envelope = object_json(payload, key)
    documents = {name: object_json(envelope[name]["json"], f"{key}/{name}")
                 for name in ("game", "history", "variant")}
    game = fields(documents["game"], GAME, key + "/game")
    game["variant"] = fields(documents["game"]["variant"], {"id": int, "name": str, "version": str},
                             key + "/game.variant")
    history = fields(documents["history"], HEADER, key + "/history")
    require(history["schema"] == 1 and history["gameID"] == int(key) and history["file"] == "history",
            f"Unexpected history header: {key}")
    phases = documents["history"].get("phases")
    require(isinstance(phases, list) and len(phases) >= 2, f"Missing history phases: {key}")
    history["phases"] = []
    for index, phase in enumerate(phases):
        context = f"{key}/history.phases[{index}]"
        kept = fields(phase, {"turn": int, "phase": str}, context)
        for name, shape in (("units", UNIT), ("centers", CENTER), ("orders", ORDER)):
            kept[name] = rows(phase.get(name), shape, context + "." + name)
        history["phases"].append(kept)
    original = documents["variant"]
    variant = fields(original, {
        "schema": int, "variantID": int, "mapID": int, "name": str,
        "supplyCenterCount": int, "supplyCenterTarget": int,
    }, key + "/variant")
    require((variant["schema"], variant["variantID"], variant["mapID"], variant["name"],
             variant["supplyCenterCount"], variant["supplyCenterTarget"]) == (1, 1, 1, "Classic", 34, 18),
            f"Unexpected variant header: {key}")
    variant["countries"] = rows(original.get("countries"), {"countryID": int, "name": str},
                                key + "/variant.countries")
    require(len(variant["countries"]) == 7, f"Expected seven Classic countries: {key}")
    territories = original.get("territories")
    require(isinstance(territories, list) and territories, f"Missing variant territories: {key}")
    variant["territories"] = []
    for index, territory in enumerate(territories):
        context = f"{key}/variant.territories[{index}]"
        kept = fields(territory, TERRITORY, context)
        for name in ("borders", "coastalBorders"):
            kept[name] = rows(territory.get(name), {"id": int, "army": bool, "fleet": bool},
                              context + "." + name)
        variant["territories"].append(kept)
    if "rules" in original:
        variant["rules"] = fields(original["rules"], {"buildAnywhere": bool}, key + "/variant.rules")
    return {
        "schema": "jreferee-webdiplomacy-mechanics-fixture-v1",
        "source": "WEBDIPLOMACY", "game_key": key, "split": "TRAIN",
        "source_payload_sha256": candidate["payload_sha256"],
        "source_document_sha256": {name: envelope[name]["sha256"] for name in documents},
        "game": game, "history": history, "variant": variant,
    }


def encoded(value):
    return (json.dumps(value, indent=2, allow_nan=False) + "\n").encode("utf-8")


def bundle(inventory_path, database, output):
    require(output.parent.is_dir() and not output.exists() and not output.is_symlink(),
            "Output must be a new file in an existing directory")
    raw = inventory_path.read_bytes()
    require(sha(raw) == EXPECTED_INVENTORY, "Inventory differs from the reviewed receipt")
    chosen = select(object_json(raw, "inventory"))
    require(database.is_file(), f"Catalog database is missing: {database}")
    contents, records = {}, []
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True,
                                isolation_level=None, timeout=5)) as connection:
        connection.execute("PRAGMA query_only=ON")
        for candidate in chosen:
            key, fingerprint = candidate["game_key"], candidate["payload_sha256"]
            found = query(connection, """
                SELECT source_payload, source_payload_sha256 FROM catalog_game
                WHERE source_type=? AND external_key=?
            """, ("WEBDIPLOMACY", key))
            require(len(found) == 1 and found[0][1] == fingerprint,
                    f"Reviewed catalog entry is missing or changed: {key}")
            payload = found[0][0]
            require(isinstance(payload, str) and sha(payload.encode("utf-8")) == fingerprint,
                    f"Raw payload checksum differs: {key}")
            fixture = project(payload, candidate)
            name = f"games/{key}.json"
            contents[name] = encoded(fixture)
            phases = fixture["history"]["phases"]
            records.append({
                "game_key": key, "press_type": fixture["game"]["pressType"],
                "source_payload_sha256": fingerprint, "file": name,
                "bytes": len(contents[name]), "sha256": sha(contents[name]),
                "phases": len(phases), "phase_types": dict(Counter(p["phase"] for p in phases)),
                "order_types": dict(Counter(o["type"] for p in phases for o in p["orders"])),
            })
            print(f'[FIXTURE] {key}: {fixture["game"]["pressType"]}, {len(phases)} phases', flush=True)
        for candidate in chosen:
            found = query(connection, """
                SELECT source_payload_sha256 FROM catalog_game WHERE source_type=? AND external_key=?
            """, ("WEBDIPLOMACY", candidate["game_key"]))
            require(found == [(candidate["payload_sha256"],)], "Selected catalog receipts changed during extraction")
    manifest = {
        "schema": "jreferee-webdiplomacy-fixture-bundle-v1", "complete": True,
        "inventory_sha256": sha(raw), "inventory_bytes": len(raw),
        "split": "TRAIN", "split_seed": 7, "games": records,
        "selection": "two per press label; SHA-256 of jreferee-webdip-fixtures-v1\\n followed by game key",
        "admission_performed": False,
        "notes": [
            "Field-allowlisted mechanics projections with separate file and source hashes.",
            "Members, messages, game titles, URLs and account metadata are excluded.",
            "Source success/dislodged flags are retained only for diagnostic comparison.",
            "No held-out payload, catalog update, dataset export or model change occurred.",
        ],
    }
    target = output.open("xb")
    written = False
    try:
        with target, ZipFile(target, "w", compression=ZIP_DEFLATED) as archive:
            for name, data in contents.items():
                archive.writestr(name, data)
            archive.writestr("manifest.json", encoded(manifest))
        written = True
    finally:
        if not written:
            output.unlink()
    print(f"Bundle: {output.resolve()}")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--database", type=Path, default=Path("data/webdiplomacy-catalog.sqlite"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output if args.output is not None else Path("data") / f"webdiplomacy-fixtures-{uuid.uuid4().hex}.zip"
    bundle(args.inventory, args.database, output)


if __name__ == "__main__":
    main()