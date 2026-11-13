"""Export a frozen GRU and conditional-probability fixtures for Java."""

import argparse
from dataclasses import replace
import hashlib
import io
from pathlib import Path
import struct

import numpy as np
import torch

from .cache import Cache, Example, SPEC_HASH, digest, integer, parse_json, require
from .model import Batch, MODEL_SCHEMA, sampled_actions
from .train import atomic_json, load_policy


EXPORT_SCHEMA = "jreferee-gru-java-v1"


def i32(stream, value):
    stream.write(struct.pack(">i", value))


def text(stream, value):
    raw = value.encode("utf-8")
    i32(stream, len(raw))
    stream.write(raw)


def checked_file(path, payload):
    require(len(payload) <= 64 * 1024 * 1024 - 32, "Export exceeds 64 MiB")
    with path.open("xb") as stream:
        stream.write(payload)
        stream.write(hashlib.sha256(payload).digest())


def synthetic(spec):
    locations = {name: index for index, name in enumerate(spec["locations"])}

    def make(power, units, orders, year=1902, phase=2):
        board = np.zeros((75, 4), dtype=np.uint8)
        for owner, kind, place in units:
            location = locations[place]
            row = spec["location_board_rows"][location]
            board[row, :3] = kind, owner, location
            if spec["supply_centers"][row]:
                board[row, 3] = owner
        slots, counts, options = [], [], []
        for place, labels in sorted(orders.items()):
            slots.append(locations[place])
            counts.append(len(labels))
            for label in sorted(labels):
                kind, origin, order, target, auxiliary = label.split()
                options.append([spec["unit_types"].index(kind), locations[origin],
                                spec["order_types"].index(order),
                                0 if target == "-" else locations[target],
                                0 if auxiliary == "-" else locations[auxiliary]])
        return Example(board, np.array([power, phase, year], dtype=np.int32),
                       np.array(slots, dtype=np.uint8), np.array(counts, dtype=np.int64),
                       np.array(options, dtype=np.uint8).reshape(-1, 5),
                       np.zeros(len(slots), dtype=np.int64), {})

    joint = make(3, [(3, 1, "Gas"), (3, 1, "Par")], {
        "Gas": ["ARMY Gas HOLD - -", "ARMY Gas MOVE Bur -", "ARMY Gas SUPPORT Par Bur"],
        "Par": ["ARMY Par HOLD - -", "ARMY Par MOVE Bur -", "ARMY Par SUPPORT Gas Bur"]})
    coasts = [(3, 2, "MAO"), (3, 1, "Spa"), (6, 2, "StpSC"), (7, 2, "BulEC")]
    many = [spec["locations"][location] for location, terrain in zip(spec["board_locations"], spec["terrain"])
            if terrain != 2][:18]
    return [
        joint, replace(joint, targets=np.array([2, 1])),
        make(3, coasts, {
            "MAO": ["FLEET MAO HOLD - -", "FLEET MAO MOVE SpaNC -", "FLEET MAO MOVE SpaSC -",
                    "FLEET MAO SUPPORT Spa -", "FLEET MAO CONVOY Spa Lon"],
            "Spa": ["ARMY Spa HOLD - -", "ARMY Spa MOVE Gas -", "ARMY Spa SUPPORT MAO Por"]}),
        make(6, coasts, {"StpSC": ["FLEET StpSC HOLD - -", "FLEET StpSC MOVE BOT -"]}, 2030, 1),
        make(7, coasts, {"BulEC": ["FLEET BulEC HOLD - -", "FLEET BulEC MOVE BLA -"]}),
        make(2, [(2, 2, "ENG"), (2, 2, "NTH"), (2, 1, "Yor")], {
            "ENG": ["FLEET ENG CONVOY Yor Bel", "FLEET ENG HOLD - -"],
            "NTH": ["FLEET NTH CONVOY Yor Bel", "FLEET NTH HOLD - -"],
            "Yor": ["ARMY Yor HOLD - -", "ARMY Yor MOVE Bel -"]}),
        make(4, coasts, {}),
        make(1, [(1, 1, "Vie")], {"Vie": ["ARMY Vie HOLD - -"]}, 1, 1),
        make(5, [(5, 1, "Rom")], {"Rom": ["ARMY Rom HOLD - -"]}),
        make(1, [(1, 1, place) for place in many], {place: [f"ARMY {place} HOLD - -"] for place in many}),
    ]


@torch.no_grad()
def export(checkpoint, output, cache_path=None, examples=32):
    integer(examples, 1, 256, "parity examples")
    checkpoint, output = Path(checkpoint), Path(output)
    if output.exists():
        raise FileExistsError(output)
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    checkpoint_hash = digest(checkpoint)
    payload = torch.load(checkpoint, map_location="cpu", weights_only=True)
    spec = parse_json(payload["specification"])
    model, payload = load_policy(checkpoint, spec)
    require(digest(checkpoint) == checkpoint_hash, "Checkpoint changed while loading")
    integer(payload["epoch"], 1, 10000, "checkpoint epoch")
    for key in ("dataset_index_sha256", "cache_manifest_sha256"):
        require(isinstance(payload[key], str) and len(payload[key]) == 64
                and all(c in "0123456789abcdef" for c in payload[key]), f"Invalid {key}")
    cases = synthetic(spec)
    synthetic_count = len(cases)
    if cache_path is not None:
        with Cache(cache_path, splits=("VALIDATION",)) as cache:
            require(cache.manifest_hash == payload["cache_manifest_sha256"]
                    and cache.manifest["dataset_index_sha256"] == payload["dataset_index_sha256"],
                    "Checkpoint and validation cache differ")
            rows = cache.indices["VALIDATION"]
            require(len(rows) > 0, "Validation is empty")
            for index in np.linspace(0, len(rows) - 1, min(examples, len(rows)), dtype=np.int64):
                shard, row, _, _ = rows[index]
                cases.append(cache.get(shard, row))

    output.mkdir()
    stream = io.BytesIO()
    stream.write(b"JREFGRU1")
    text(stream, EXPORT_SCHEMA)
    text(stream, MODEL_SCHEMA)
    text(stream, payload["specification"])
    for value in (SPEC_HASH, checkpoint_hash, payload["dataset_index_sha256"], payload["cache_manifest_sha256"]):
        stream.write(bytes.fromhex(value))
    i32(stream, payload["epoch"])
    i32(stream, model.hidden)
    parameters = list(model.named_parameters())
    i32(stream, len(parameters))
    for name, parameter in parameters:
        text(stream, name)
        i32(stream, parameter.ndim)
        for dimension in parameter.shape:
            i32(stream, dimension)
        stream.write(parameter.detach().numpy().astype(">f4").tobytes())
    checked_file(output / "model.bin", stream.getvalue())

    stream = io.BytesIO()
    stream.write(b"JREFCHK1")
    stream.write(bytes.fromhex(SPEC_HASH))
    stream.write(bytes.fromhex(digest(output / "model.bin")))
    i32(stream, len(cases))
    generator = torch.Generator().manual_seed(1729)
    conditionals = paths = 0
    for example in cases:
        stream.write(example.board.tobytes())
        stream.write(example.context.astype(">i4").tobytes())
        i32(stream, len(example.locations))
        offset = 0
        for location, count in zip(example.locations, example.counts):
            stream.write(bytes([int(location)]))
            i32(stream, int(count))
            stream.write(example.options[offset:offset + count].tobytes())
            offset += count
        batch = Batch.make([example])
        prepared = model.prepare(batch)
        actions = [example.targets.tolist(), (example.counts - 1).tolist()]
        for mode in ("greedy", "sample"):
            actions.append(sampled_actions(batch, model.decode(batch, prepared, mode, generator))[0][0])
        actions = list(dict.fromkeys(tuple(action) for action in actions))
        i32(stream, len(actions))
        for action in actions:
            teacher = Batch.make([replace(example, targets=np.array(action, dtype=np.int64))])
            decoded = model.decode(teacher, prepared)
            stream.write(np.asarray(action, dtype=">i4").tobytes())
            for logp, _ in decoded:
                require(torch.isfinite(logp).all().item(), "Nonfinite reference probabilities")
                stream.write(logp.numpy().astype(">f4").tobytes())
                conditionals += 1
            paths += 1
        require(stream.tell() <= 64 * 1024 * 1024 - 32,
                "Parity export exceeds 64 MiB; reduce --examples")
    checked_file(output / "parity.bin", stream.getvalue())
    report = {
        "schema": EXPORT_SCHEMA, "complete": True, "model_schema": MODEL_SCHEMA,
        "specification_sha256": SPEC_HASH, "checkpoint_sha256": checkpoint_hash,
        "dataset_index_sha256": payload["dataset_index_sha256"],
        "cache_manifest_sha256": payload["cache_manifest_sha256"],
        "epoch": payload["epoch"], "hidden": model.hidden, "test_evaluated": False,
        "synthetic_cases": synthetic_count, "validation_cases": len(cases) - synthetic_count,
        "paths": paths, "conditionals": conditionals, "torch": torch.__version__,
        "files": {name: {"bytes": (output / name).stat().st_size, "sha256": digest(output / name)}
                  for name in ("model.bin", "parity.bin")},
    }
    atomic_json(output / "export.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=Path("data/movement-gru-v1/policy.pt"))
    parser.add_argument("--output", type=Path, default=Path("data/movement-gru-java-v1"))
    parser.add_argument("--cache", type=Path, help="Add evenly spaced VALIDATION examples from the matching cache")
    parser.add_argument("--examples", type=int, default=32)
    args = parser.parse_args()
    report = export(args.checkpoint, args.output, args.cache, args.examples)
    print(f'Exported epoch {report["epoch"]}; hidden={report["hidden"]}; '
          f'cases={report["synthetic_cases"] + report["validation_cases"]}; conditionals={report["conditionals"]}')
    print(f'Model SHA-256={report["files"]["model.bin"]["sha256"]}')
    print(f"Saved {args.output}. No training or TEST evaluation was performed.")


if __name__ == "__main__":
    main()