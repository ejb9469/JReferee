"""Export a selected typed policy, its provenance and Python/Java parity cases."""

import argparse
from dataclasses import dataclass, replace
import io
import json
import math
from pathlib import Path
import struct

import numpy as np
import torch

from .cache import Example, digest, parse_json
from .export import checked_file, i32, synthetic, text
from .model import Batch, sampled_actions
from .self_play_cache import SelfPlayCache, integer, require, sha
from .self_play_data import PolicyData, type_features, type_specification
from .self_play_model import MODEL_SCHEMA, SPEC_HASH, CandidateBatch, load_typed_policy, snapshot
from .train import atomic_json


EXPORT_SCHEMA = "jreferee-type-conditioned-gru-java-v1"
COHORT_PREFIX = b"jreferee-self-play-cohort-v1\n"


@dataclass(frozen=True)
class Case:
    example: Example
    types: tuple
    type_ids: tuple


def batch(case):
    base = Batch.make([case.example])
    return CandidateBatch(
        base.board, base.context, base.steps, base.examples,
        torch.tensor([type_features(case)], dtype=torch.float32),
        torch.ones(1, dtype=torch.float64), ((0, 0),), 1)


def validation_case(root):
    candidate = next(index for index, probability in enumerate(root.probabilities) if probability > 0)
    example = Example(
        np.asarray(root.board, dtype=np.uint8), np.asarray(root.context, dtype=np.int32),
        np.asarray(root.locations, dtype=np.int64), np.asarray(root.counts, dtype=np.int64),
        np.asarray(root.options, dtype=np.uint8), np.asarray(root.actions[candidate], dtype=np.int64), {})
    return Case(example, root.types, root.type_ids)


def synthetic_cases(spec):
    cases = []
    for example in synthetic(spec):
        for profile in (1, 2):
            population = (0.01, 10 ** -1.5, 0.1, 10 ** -0.5) if profile == 1 else (0.0001, 0.001, 0.01, 0.1)
            values = (population[0],) + population + (0.25,) * 4
            for output in (1, 2):
                cases.append(Case(example, values, (profile, output)))
    return cases


def verify_training(training, payload, checkpoint_hash, data):
    require(training["schema"] == "jreferee-self-play-policy-training-v1"
            and training["complete"] is True
            and training["checkpoint_sha256"] == checkpoint_hash
            and training["best_epoch"] == payload["epoch"]
            and training["model_schema"] == MODEL_SCHEMA, "Training receipt and checkpoint differ")
    for name in ("anchor_updated", "value_trained", "test_evaluated", "promotion_performed", "optimizer_resumed"):
        require(training[name] is False, f"Unexpected training receipt flag: {name}")
    for record in (training, payload):
        require(record["specification_sha256"] == SPEC_HASH
                and record["type_encoding"] == type_specification()
                and record["anchor_sha256"] == data.anchor_sha256
                and record["datasets"] == data.receipts(), "Checkpoint, training receipt and caches differ")
    require(training["self_play_split_seed"] == payload["split_seed"] == str(data.split_seed)
            and training["initial_policy"] == payload["initial_policy"]
            and training["policy_roots"] == {split: len(rows) for split, rows in data.records.items()},
            "Training cohort or warm-start identity differs")
    selected = training["selected_validation"]
    score = payload["validation_cross_entropy"]
    require(training["best_validation_cross_entropy"] == score
            and selected["split"] == "VALIDATION"
            and selected["roots"] == len(data.records["VALIDATION"])
            and type(selected["cross_entropy"]) in (int, float)
            and math.isfinite(selected["cross_entropy"])
            and math.isclose(selected["cross_entropy"], score, rel_tol=1e-6, abs_tol=1e-7),
            "Selected validation receipt differs")


@torch.no_grad()
def export(checkpoint, training_path, cache_paths, anchor_path, output, examples=32):
    integer(examples, 1, 256, "validation parity roots")
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    require(output.parent.is_dir(), "Output parent must already exist")
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    data = PolicyData([SelfPlayCache(path) for path in cache_paths], sha(snapshot(anchor_path)))
    require(data.records["TRAIN"] and data.records["VALIDATION"], "Both policy splits are required")
    model, payload, checkpoint_hash = load_typed_policy(checkpoint, data.spec)
    training_raw = snapshot(training_path)
    training = parse_json(training_raw)
    verify_training(training, payload, checkpoint_hash, data)
    receipts = data.receipts()
    integer(len(receipts), 1, 1024, "dataset receipts")
    receipt_bytes = b"".join(bytes.fromhex(row[key]) for row in receipts
                             for key in ("dataset_fingerprint", "cache_manifest_sha256"))
    cohort_hash = sha(COHORT_PREFIX + receipt_bytes)
    training_hash = sha(training_raw)

    cases = synthetic_cases(data.spec)
    synthetic_count = len(cases)
    rows = data.records["VALIDATION"]
    indices = np.linspace(0, len(rows) - 1, min(examples, len(rows)), dtype=np.int64)
    selected_roots = []
    for index in indices:
        row = rows[index]
        cache = data.caches[row.cache]
        cases.append(validation_case(cache.get_policy("VALIDATION", row.index)))
        selected_roots.append({"dataset_fingerprint": cache.fingerprint, "policy_index": row.index})
    for case in cases:
        type_features(case)
        require(len(case.example.options) <= 100000, "Parity root exceeds 100000 legal options")

    output.mkdir()
    stream = io.BytesIO()
    stream.write(b"JREFTGR1")
    for value in (EXPORT_SCHEMA, MODEL_SCHEMA, payload["specification"],
                  json.dumps(type_specification(), separators=(",", ":"), sort_keys=True)):
        text(stream, value)
    for value in (SPEC_HASH, checkpoint_hash, cohort_hash, training_hash, data.anchor_sha256):
        stream.write(bytes.fromhex(value))
    stream.write(struct.pack(">q", data.split_seed))
    i32(stream, len(receipts))
    stream.write(receipt_bytes)
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
    stream.write(b"JREFTCK1")
    stream.write(bytes.fromhex(SPEC_HASH))
    stream.write(bytes.fromhex(digest(output / "model.bin")))
    i32(stream, len(cases))
    generator = torch.Generator().manual_seed(1729)
    conditionals = paths = 0
    for case in cases:
        example = case.example
        stream.write(example.board.tobytes())
        stream.write(example.context.astype(">i4").tobytes())
        i32(stream, len(example.locations))
        offset = 0
        for location, count in zip(example.locations, example.counts):
            stream.write(bytes([int(location)]))
            i32(stream, int(count))
            stream.write(example.options[offset:offset + count].tobytes())
            offset += count
        stream.write(struct.pack(">ii9d", *case.type_ids, *case.types))
        stream.write(struct.pack(">11f", *type_features(case)))
        prepared_batch = batch(case)
        prepared = model.prepare(prepared_batch)
        actions = [example.targets.tolist(), (example.counts - 1).tolist()]
        for mode in ("greedy", "sample"):
            actions.append(sampled_actions(
                prepared_batch, model.decode(prepared_batch, prepared, mode, generator))[0][0])
        actions = list(dict.fromkeys(tuple(action) for action in actions))
        i32(stream, len(actions))
        for action in actions:
            teacher = batch(replace(case, example=replace(example, targets=np.asarray(action, dtype=np.int64))))
            decoded = model.decode(teacher, prepared)
            stream.write(np.asarray(action, dtype=">i4").tobytes())
            joint = []
            for slot, (logp, _) in enumerate(decoded):
                require(torch.isfinite(logp).all().item(), "Nonfinite parity log probabilities")
                stream.write(logp.numpy().astype(">f4").tobytes())
                joint.append(float(logp[action[slot]].item()))
                conditionals += 1
            stream.write(struct.pack(">d", math.fsum(joint)))
            paths += 1
        require(stream.tell() <= 64 * 1024 * 1024 - 32, "Parity file exceeds 64 MiB; reduce --examples")
    checked_file(output / "parity.bin", stream.getvalue())
    report = {
        "schema": EXPORT_SCHEMA, "complete": True, "model_schema": MODEL_SCHEMA,
        "specification_sha256": SPEC_HASH, "type_encoding": type_specification(),
        "checkpoint_sha256": checkpoint_hash, "training_report_sha256": training_hash,
        "anchor_sha256": data.anchor_sha256, "self_play_split_seed": str(data.split_seed),
        "dataset_cohort_sha256": cohort_hash, "datasets": receipts,
        "epoch": payload["epoch"], "hidden": model.hidden,
        "synthetic_cases": synthetic_count, "validation_cases": len(selected_roots),
        "validation_roots": selected_roots, "paths": paths, "conditionals": conditionals,
        "torch": str(torch.__version__), "test_evaluated": False,
        "training_performed": False, "promotion_performed": False, "java_parity_verified": False,
        "files": {name: {"bytes": (output / name).stat().st_size, "sha256": digest(output / name)}
                  for name in ("model.bin", "parity.bin")},
    }
    atomic_json(output / "export.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--training", type=Path, required=True)
    parser.add_argument("--cache", type=Path, nargs="+", required=True)
    parser.add_argument("--anchor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--examples", type=int, default=32)
    args = parser.parse_args()
    report = export(args.checkpoint, args.training, args.cache, args.anchor, args.output, args.examples)
    print(f"Exported typed epoch {report['epoch']}; hidden={report['hidden']}; "
          f"synthetic={report['synthetic_cases']}; validation={report['validation_cases']}")
    print("Checkpoint SHA-256=" + report["checkpoint_sha256"])
    print("Model SHA-256=" + report["files"]["model.bin"]["sha256"])
    print("Saved export.json. Java parity is not yet verified. No training, TEST access or promotion.")


if __name__ == "__main__":
    main()