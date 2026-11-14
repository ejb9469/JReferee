"""Export the selected value checkpoint and frozen Python/Java parity cases."""

import argparse
import hashlib
import io
from pathlib import Path

import numpy as np
import torch

from .cache import digest, integer, parse_json, require
from .export import checked_file, i32, text
from .value_cache import REWARD, SPEC_HASH, ValueCache
from .value_model import MODEL_SCHEMA
from .value_train import atomic_json, load_model


EXPORT_SCHEMA = "jreferee-value-java-v1"


def synthetic(spec):
    board_spec = spec["board_specification"]
    locations = {name: index for index, name in enumerate(board_spec["locations"])}
    rows = board_spec["location_board_rows"]

    def make(units=(), centers=(), phase=1, year=1901):
        board = np.zeros((75, 4), dtype=np.uint8)
        for owner, kind, name in units:
            location = locations[name]
            board[rows[location], :3] = kind, owner, location
        for owner, name in centers:
            board[rows[locations[name]], 3] = owner
        return board, np.array([phase, year], dtype=np.int32)

    centers = [(6, "Spa"), (3, "Par"), (7, "Bul"), (6, "Stp")]
    cases = [make(), make(centers=centers, phase=3, year=1907)]
    for coast in ("SpaNC", "SpaSC", "BulEC", "BulSC", "StpNC", "StpSC"):
        cases.append(make([(3, 2, coast), (6, 1, "Mos")], centers, phase=2, year=1903))
    cases.append(make([(2, 2, "NTH"), (2, 1, "Lon"), (1, 1, "Vie")],
                      [(2, "Lon"), (1, "Vie")], phase=3, year=2030))
    units = []
    for location, terrain in zip(board_spec["board_locations"], board_spec["terrain"]):
        units.append((len(units) % 7 + 1, 2 if terrain == 2 else 1,
                      board_spec["locations"][location]))
        if len(units) == 34:
            break
    cases.append(make(units, centers, phase=1, year=1909))
    return cases


@torch.no_grad()
def export(checkpoint, training_path, cache_path, output, examples=32):
    integer(examples, 1, 256, "validation parity cases")
    checkpoint, training_path, output = Path(checkpoint), Path(training_path), Path(output)
    require(not output.exists(), f"Output already exists: {output}")
    require(checkpoint.stat().st_size <= 64 * 1024 * 1024, "Checkpoint exceeds 64 MiB")
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    cache = ValueCache(cache_path)
    training_raw = training_path.read_bytes()
    training = parse_json(training_raw)
    checkpoint_hash = digest(checkpoint)
    model, payload = load_model(checkpoint, cache.spec)
    require(digest(checkpoint) == checkpoint_hash, "Checkpoint changed while loading")
    require(training["schema"] == "jreferee-value-training-v1"
            and training["complete"] is True and training["test_opened"] is False
            and training["checkpoint_sha256"] == checkpoint_hash
            and training["best_epoch"] == payload["epoch"]
            and training["model_schema"] == MODEL_SCHEMA, "Training receipt and checkpoint differ")
    for record in (training, payload):
        require(record["specification_sha256"] == SPEC_HASH
                and record["reward_contract"] == REWARD
                and record["dataset_fingerprint"] == cache.fingerprint
                and record["cache_manifest_sha256"] == cache.manifest_hash,
                "Checkpoint, training receipt and cache identities differ")

    cases = synthetic(cache.spec)
    synthetic_count = len(cases)
    validation = cache.splits["VALIDATION"]
    require(len(validation) > 0, "Validation split is empty")
    indices = np.linspace(0, len(validation) - 1, min(examples, len(validation)), dtype=np.int64)
    for index in indices:
        cases.append((validation.board[index], validation.context[index]))

    output.mkdir()
    stream = io.BytesIO()
    stream.write(b"JREFVAL1")
    for value in (EXPORT_SCHEMA, MODEL_SCHEMA, REWARD, cache.spec_text):
        text(stream, value)
    for value in (SPEC_HASH, checkpoint_hash, cache.fingerprint, cache.manifest_hash):
        stream.write(bytes.fromhex(value))
    i32(stream, payload["epoch"])
    i32(stream, model.hidden)
    parameters = list(model.named_parameters())
    i32(stream, len(parameters))
    for name, parameter in parameters:
        require(torch.isfinite(parameter).all().item(), f"Nonfinite parameter: {name}")
        text(stream, name)
        i32(stream, parameter.ndim)
        for dimension in parameter.shape:
            i32(stream, dimension)
        stream.write(parameter.detach().numpy().astype(">f4").tobytes())
    checked_file(output / "model.bin", stream.getvalue())

    stream = io.BytesIO()
    stream.write(b"JREFVCK1")
    stream.write(bytes.fromhex(SPEC_HASH))
    stream.write(bytes.fromhex(digest(output / "model.bin")))
    i32(stream, len(cases))
    for board, context in cases:
        actual = model(torch.tensor(board.astype(np.int64))[None],
                       torch.tensor(context.astype(np.int64))[None])[0]
        require(torch.isfinite(actual).all().item()
                and torch.all((actual >= 0) & (actual <= 1)).item()
                and abs(actual.sum().item() - 1) <= 1e-6, "Invalid reference distribution")
        stream.write(board.astype("u1").tobytes())
        stream.write(context.astype(">i4").tobytes())
        stream.write(actual.numpy().astype(">f4").tobytes())
    checked_file(output / "parity.bin", stream.getvalue())

    report = {
        "schema": EXPORT_SCHEMA, "complete": True, "model_schema": MODEL_SCHEMA,
        "reward_contract": REWARD, "specification_sha256": SPEC_HASH,
        "checkpoint_sha256": checkpoint_hash, "dataset_fingerprint": cache.fingerprint,
        "cache_manifest_sha256": cache.manifest_hash,
        "training_report_sha256": hashlib.sha256(training_raw).hexdigest(),
        "epoch": payload["epoch"], "hidden": model.hidden, "test_opened": False,
        "synthetic_cases": synthetic_count, "validation_cases": len(indices),
        "validation_position_indices": indices.tolist(), "torch": str(torch.__version__),
        "java_parity_verified": False,
        "files": {name: {"bytes": (output / name).stat().st_size, "sha256": digest(output / name)}
                  for name in ("model.bin", "parity.bin")},
    }
    atomic_json(output / "export.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--training", required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--examples", type=int, default=32)
    args = parser.parse_args()
    report = export(args.checkpoint, args.training, args.cache, args.output, args.examples)
    print(f"Exported epoch {report['epoch']}; hidden={report['hidden']}; "
          f"synthetic={report['synthetic_cases']}; validation={report['validation_cases']}")
    print("Checkpoint SHA-256=" + report["checkpoint_sha256"])
    print("Model SHA-256=" + report["files"]["model.bin"]["sha256"])
    print("Saved export.json. Java parity has not yet been checked. TEST was not opened.")


if __name__ == "__main__":
    main()