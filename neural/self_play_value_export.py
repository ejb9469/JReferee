"""Export a selected self-play value checkpoint without obscuring its provenance."""

import argparse
import hashlib
import io
import math
from pathlib import Path
import struct

import numpy as np
import torch

from .cache import digest, parse_json
from .export import checked_file, i32, text
from .self_play_cache import SelfPlayCache, integer, require
from .self_play_model import snapshot
from .self_play_value_data import ValueData
from .self_play_value_model import MODEL_SCHEMA, load_model
from .value_cache import REWARD, SPEC_HASH
from .value_export import synthetic, write_parameters, write_parity
from .value_train import atomic_json, metrics, predict


EXPORT_SCHEMA = "jreferee-self-play-value-java-v1"


@torch.no_grad()
def export(data, checkpoint, training_path, output, examples=32, threads=4):
    data.preflight()
    integer(examples, 1, 256, "validation parity cases")
    integer(threads, 1, 256, "threads")
    torch.set_num_threads(threads)
    torch.use_deterministic_algorithms(True)
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    require(output.parent.is_dir(), "Output parent must already exist")
    report_bytes = snapshot(training_path)
    report = parse_json(report_bytes)
    model, payload, checkpoint_hash = load_model(checkpoint, data.spec)
    require(report["schema"] == "jreferee-self-play-value-training-v1" and report["complete"] is True
            and report["model_schema"] == MODEL_SCHEMA and report["reward_contract"] == REWARD
            and report["specification_sha256"] == SPEC_HASH
            and all(report[name] is False for name in ("test_opened", "optimizer_resumed", "policy_updated",
                                                       "anchor_updated", "promotion_performed")),
            "Expected completed, isolated self-play value training")
    require(payload["datasets"] == report["datasets"] == data.receipts()
            and payload["dataset_cohort_sha256"] == report["dataset_cohort_sha256"] == data.fingerprint
            and payload["split_seed"] == report["self_play_split_seed"] == str(data.split_seed)
            and report["source_admission"] == data.admission and report["producer_configurations"] == data.producers,
            "Value training/cache cohort mismatch")
    require(checkpoint_hash == report["checkpoint_sha256"]
            and payload["epoch"] == report["best_epoch"] and model.hidden == report["hidden"]
            and payload["initial_value"] == report["initial_value"]
            and payload["validation_mse"] == report["best_validation_mse"],
            "Selected value checkpoint/report mismatch")
    validation = data.splits()["VALIDATION"]
    actual = metrics(validation, predict(model, validation, report["settings"]["batch_size"]), data.spec["output_powers"])
    require(math.isclose(actual["overall"]["mse"], payload["validation_mse"], rel_tol=1e-6, abs_tol=1e-9),
            "Selected value checkpoint no longer reproduces its validation MSE")
    cases = synthetic(data.spec)
    synthetic_count = len(cases)
    count = min(examples, len(validation))
    indices = np.linspace(0, len(validation) - 1, count, dtype=np.int64)
    cases.extend((validation.board[index].copy(), validation.context[index].copy()) for index in indices)
    references = []
    for index in indices:
        local = int(index)
        for cache in data.caches:
            if local < cache.value_counts["VALIDATION"]:
                row = data.examples["VALIDATION"][int(index)]
                references.append({"dataset_fingerprint": cache.fingerprint, "position": local,
                                   "game_key": row.metadata["game_key"],
                                   "payload_sha256": row.metadata["payload_sha256"]})
                break
            local -= cache.value_counts["VALIDATION"]
        else:
            raise ValueError("Missing held-out value reference")
    report_hash = hashlib.sha256(report_bytes).hexdigest()
    stream = io.BytesIO()
    stream.write(b"JREFSVL1")
    for value in (EXPORT_SCHEMA, MODEL_SCHEMA, REWARD, data.spec_text):
        text(stream, value)
    for identity in (SPEC_HASH, checkpoint_hash, data.fingerprint, report_hash, payload["initial_value"]["sha256"]):
        stream.write(bytes.fromhex(identity))
    stream.write(struct.pack(">q", data.split_seed))
    i32(stream, len(data.caches))
    for receipt in data.receipts():
        stream.write(bytes.fromhex(receipt["dataset_fingerprint"]))
        stream.write(bytes.fromhex(receipt["cache_manifest_sha256"]))
    i32(stream, payload["epoch"])
    i32(stream, model.hidden)
    write_parameters(stream, model)
    output.mkdir()
    checked_file(output / "model.bin", stream.getvalue())
    write_parity(output / "parity.bin", cases, model, digest(output / "model.bin"))
    receipt = {
        "schema": EXPORT_SCHEMA, "complete": True, "model_schema": MODEL_SCHEMA,
        "reward_contract": REWARD, "specification_sha256": SPEC_HASH,
        "checkpoint_sha256": checkpoint_hash, "training_report_sha256": report_hash,
        "dataset_cohort_sha256": data.fingerprint, "datasets": data.receipts(),
        "self_play_split_seed": str(data.split_seed), "initial_value": payload["initial_value"],
        "epoch": payload["epoch"], "hidden": model.hidden, "validation_mse": payload["validation_mse"],
        "synthetic_cases": synthetic_count, "validation_cases": count, "validation_references": references,
        "source_admission": data.admission, "test_opened": False, "java_parity_verified": False,
        "files": {name: {"bytes": (output / name).stat().st_size, "sha256": digest(output / name)}
                  for name in ("model.bin", "parity.bin")},
    }
    atomic_json(output / "export.json", receipt)
    print(f"Exported self-play value epoch {payload['epoch']}; model SHA-256={receipt['files']['model.bin']['sha256']}")
    print("Run NeuralValueParitySelfCheck before using this value model. No promotion performed.")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, nargs="+", required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--training", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--examples", type=int, default=32)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    caches = []
    for path in args.cache:
        print(f"Verifying {path}", flush=True)
        caches.append(SelfPlayCache(path))
    export(ValueData(caches), args.checkpoint, args.training, args.output, args.examples, args.threads)


if __name__ == "__main__":
    main()