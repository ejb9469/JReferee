"""Supervised GRU training with validation-selected checkpoints."""

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import time

import numpy as np
import torch

from .cache import Cache, SPEC_HASH, digest, integer, require
from .model import Batch, MODEL_SCHEMA, MovementGRU, coordination, sampled_actions, segment_argmax


@dataclass(frozen=True)
class Settings:
    epochs: int = 3
    hidden: int = 128
    batch_size: int = 32
    max_options: int = 16384
    bucket_window: int = 256
    learning_rate: float = 0.001
    weight_decay: float = 0.0001
    gradient_clip: float = 1.0
    seed: int = 7
    validation_samples: int = 1
    threads: int = 4
    device: str = "cpu"

    def __post_init__(self):
        integer(self.epochs, 1, 10000, "epochs")
        integer(self.hidden, 16, 512, "hidden size")
        integer(self.batch_size, 1, 4096, "batch size")
        integer(self.max_options, 1, 1_000_000, "batch option limit")
        integer(self.bucket_window, self.batch_size, 65536, "bucket window")
        integer(self.seed, 0, 2**63 - 10001, "seed")
        integer(self.validation_samples, 1, 16, "validation samples")
        integer(self.threads, 1, 256, "CPU threads")
        require(self.device in ("cpu", "cuda"), "Device must be cpu or cuda")
        for name in ("learning_rate", "gradient_clip", "weight_decay"):
            value = getattr(self, name)
            require(math.isfinite(value) and (value >= 0 if name == "weight_decay" else value > 0), f"Invalid {name}")


def atomic_json(path, value):
    pending = path.with_suffix(path.suffix + ".pending")
    with pending.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, allow_nan=False, sort_keys=True, indent=2)
        stream.write("\n")
    os.replace(pending, path)


def save_policy(path, model, cache, epoch):
    payload = {
        "schema": MODEL_SCHEMA, "specification_sha256": SPEC_HASH,
        "specification": cache.spec_text, "config": {"hidden": model.hidden},
        "dataset_index_sha256": cache.manifest["dataset_index_sha256"],
        "cache_manifest_sha256": cache.manifest_hash, "epoch": epoch,
        "state_dict": {key: value.detach().cpu().clone() for key, value in model.state_dict().items()},
    }
    require(all(torch.isfinite(value).all().item() for value in payload["state_dict"].values()), "Nonfinite checkpoint")
    pending = path.with_suffix(".pt.pending")
    with pending.open("xb") as stream:
        torch.save(payload, stream)
    os.replace(pending, path)


def load_policy(path, spec):
    payload = torch.load(path, map_location="cpu", weights_only=True)
    require(payload["schema"] == MODEL_SCHEMA and payload["specification_sha256"] == SPEC_HASH
            and hashlib.sha256(payload["specification"].encode()).hexdigest() == SPEC_HASH,
            "Incompatible policy specification")
    require(json.loads(payload["specification"]) == spec, "Policy and requested input specification differ")
    require(set(payload["config"]) == {"hidden"}, "Unknown model configuration")
    model = MovementGRU(spec, **payload["config"])
    state = payload["state_dict"]
    expected = model.state_dict()
    require(state.keys() == expected.keys(), "Checkpoint tensor names differ")
    for name, value in state.items():
        require(value.shape == expected[name].shape and value.dtype == expected[name].dtype
                and torch.isfinite(value).all().item(), f"Invalid checkpoint tensor: {name}")
    for name, value in model.named_buffers():
        require(torch.equal(state[name], value), f"Checkpoint map differs: {name}")
    model.load_state_dict(state, strict=True)
    model.eval()
    return model, payload


def summarize(rows):
    totals = {}
    for row in rows:
        for key, value in row.items():
            totals[key] = totals.get(key, 0) + value
    e, n = totals["examples"], totals["orders"]
    return {
        "examples": e, "orders": n, "order_nll": totals["nll"] / n,
        "uniform_order_nll": totals["uniform_nll"] / n,
        "teacher_forced_order_accuracy": totals["correct"] / n,
        "exact_action_accuracy": totals["exact"] / e,
        "observed_action_coverage_at_k": totals["covered"] / e,
        "mean_distinct_samples": totals["distinct"] / e,
        "sampled_actions": totals["sampled"],
        "sampled_mismatched_actions": totals["mismatched"],
        "sampled_mismatch_rate": totals["mismatched"] / totals["sampled"],
        **{key: totals[key] for key in ("own_supports", "bad_supports", "own_convoys", "bad_convoys")},
    }


@torch.no_grad()
def evaluate(model, cache, settings):
    model.eval()
    start = time.perf_counter()
    generator = torch.Generator(device=settings.device).manual_seed(settings.seed + 10000)
    all_rows, powers, communications = [], {}, {}
    for examples in cache.batches("VALIDATION", settings.batch_size, settings.max_options, window=settings.bucket_window):
        batch = Batch.make(examples, settings.device)
        prepared = model.prepare(batch)
        predicted = model.decode(batch, prepared)
        losses = np.zeros(len(examples))
        correct = np.zeros(len(examples), dtype=np.int64)
        for step, (logp, _) in zip(batch.steps, predicted):
            require(torch.isfinite(logp).all().item(), "Nonfinite validation probabilities")
            owners = step.active.cpu().numpy()
            losses[owners] -= logp[step.targets].cpu().numpy()
            correct[owners] += (segment_argmax(logp, step.counts) == step.targets).cpu().numpy()
        rows, seen = [], [set() for _ in examples]
        for i, example in enumerate(examples):
            rows.append(dict(examples=1, orders=len(example.targets), nll=float(losses[i]),
                             uniform_nll=float(np.log(example.counts).sum()), correct=int(correct[i]),
                             exact=int(correct[i] == len(example.targets)), covered=0, distinct=0,
                             sampled=0, mismatched=0, own_supports=0, bad_supports=0, own_convoys=0, bad_convoys=0))
        for sample in range(settings.validation_samples):
            decoded = model.decode(batch, prepared, "sample", generator)
            actions, fields = sampled_actions(batch, decoded)
            for i, (action, orders) in enumerate(zip(actions, fields)):
                seen[i].add(tuple(action))
                rows[i]["covered"] |= int(action == examples[i].targets.tolist())
                support, bad_support, convoy, bad_convoy = coordination(orders, cache.spec["canonical_locations"])
                rows[i]["sampled"] += 1
                rows[i]["mismatched"] += int(bad_support + bad_convoy > 0)
                for key, value in zip(("own_supports", "bad_supports", "own_convoys", "bad_convoys"),
                                      (support, bad_support, convoy, bad_convoy)):
                    rows[i][key] += value
        for row, choices, example in zip(rows, seen, examples):
            row["distinct"] = len(choices)
            all_rows.append(row)
            power = cache.spec["powers"][int(example.context[0])]
            powers.setdefault(power, []).append(row)
            communications.setdefault(example.metadata["communication_type"], []).append(row)
    require(len(all_rows) == cache.split_info["VALIDATION"]["examples"] and all_rows, "Incomplete validation pass")
    return {
        "split": "VALIDATION", "samples_per_example": settings.validation_samples,
        "overall": summarize(all_rows), "by_power": {key: summarize(rows) for key, rows in powers.items()},
        "by_communication": [{"communication_type": key, "metrics": summarize(rows)}
                             for key, rows in sorted(communications.items(), key=lambda item: (item[0] is not None, item[0] or ""))],
        "elapsed_seconds": time.perf_counter() - start,
    }


def fit(cache, output, settings):
    require(cache.selected == ("TRAIN", "VALIDATION"), "Training requires only TRAIN and VALIDATION")
    require(settings.device != "cuda" or torch.cuda.is_available(), "CUDA was requested but is unavailable")
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    torch.set_num_threads(settings.threads)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(settings.seed)
    require(all(len(cache.indices[split]) > 0 for split in ("TRAIN", "VALIDATION")), "Training and validation must be nonempty")
    # Check batch bounds before creating an output directory or fitting weights.
    require(all(cache.indices[split][:, 3].max() <= settings.max_options for split in ("TRAIN", "VALIDATION")),
            "One example exceeds --max-options; raise the bound")
    output = Path(output)
    output.mkdir()
    model = MovementGRU(cache.spec, settings.hidden).to(settings.device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings.learning_rate, weight_decay=settings.weight_decay)
    initial = evaluate(model, cache, settings)
    report = {
        "schema": "jreferee-gru-training-v1", "complete": False, "test_evaluated": False,
        "model_schema": MODEL_SCHEMA, "specification_sha256": SPEC_HASH,
        "dataset_index_sha256": cache.manifest["dataset_index_sha256"], "cache_manifest_sha256": cache.manifest_hash,
        "filter": cache.filter, "settings": asdict(settings),
        "versions": {"python": platform.python_version(), "numpy": np.__version__, "torch": torch.__version__},
        "device": str(next(model.parameters()).device),
        "parameters": sum(p.numel() for p in model.parameters()),
        "source_sha256": {name: cache.split_info[name]["source_sha256"] for name in ("TRAIN", "VALIDATION")},
        "cache_verification_seconds": cache.verification_seconds, "initial_validation": initial, "epochs": [],
    }
    atomic_json(output / "training.json", report)
    best_loss = float("inf")
    for epoch in range(1, settings.epochs + 1):
        model.train()
        start, nll, examples_seen, orders_seen = time.perf_counter(), 0.0, 0, 0
        ready, read_seconds, batch_seconds = start, 0.0, 0.0
        for examples in cache.batches("TRAIN", settings.batch_size, settings.max_options,
                                      settings.seed + epoch - 1, settings.bucket_window):
            received = time.perf_counter()
            read_seconds += received - ready
            batch = Batch.make(examples, settings.device)
            batch_seconds += time.perf_counter() - received
            optimizer.zero_grad(set_to_none=True)
            decoded = model(batch)
            loss_sum = -sum(logp[step.targets].sum() for step, (logp, _) in zip(batch.steps, decoded))
            orders = sum(len(example.targets) for example in examples)
            require(torch.isfinite(loss_sum).item(), "Nonfinite training loss")
            (loss_sum / orders).backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), settings.gradient_clip, error_if_nonfinite=True)
            optimizer.step()
            nll += loss_sum.detach().item()
            examples_seen += len(examples)
            orders_seen += orders
            ready = time.perf_counter()
        elapsed = time.perf_counter() - start
        require(examples_seen == cache.split_info["TRAIN"]["examples"]
                and orders_seen == cache.split_info["TRAIN"]["slots"], "Incomplete training epoch")
        validation = evaluate(model, cache, settings)
        loss = validation["overall"]["order_nll"]
        report["epochs"].append({"epoch": epoch, "online_order_nll": nll / orders_seen,
                                 "examples": examples_seen, "orders": orders_seen,
                                 "elapsed_seconds": elapsed, "read_seconds": read_seconds,
                                 "batch_seconds": batch_seconds,
                                 "compute_seconds": elapsed - read_seconds - batch_seconds,
                                 "validation": validation})
        if loss < best_loss:
            best_loss = loss
            save_policy(output / "policy.pt", model, cache, epoch)
            report["best_epoch"] = epoch
        atomic_json(output / "training.json", report)
        metrics = validation["overall"]
        print(f'Epoch {epoch}: online NLL {nll / orders_seen:.6f}; validation NLL {loss:.6f}; '
              f'order accuracy {metrics["teacher_forced_order_accuracy"]:.4f}; '
              f'sampled mismatch {metrics["sampled_mismatch_rate"]:.4f}', flush=True)
    loaded, payload = load_policy(output / "policy.pt", cache.spec)
    require(payload["epoch"] == report["best_epoch"], "Saved checkpoint is not the selected epoch")
    report["model_sha256"] = digest(output / "policy.pt")
    report["complete"] = True
    atomic_json(output / "training.json", report)
    print(f'Saved validation-selected epoch {report["best_epoch"]} to {output / "policy.pt"}')
    if best_loss >= initial["overall"]["order_nll"]:
        print("WARNING: no trained epoch improved on the initial validation NLL.")
    print("Test was not opened. Keep policy.pt and send training.json plus this console output.")
    return loaded, report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path("data/movement-neural-cache-v1"))
    parser.add_argument("--output", type=Path, default=Path("data/movement-gru-v1"))
    parser.add_argument("--verify-only", action="store_true")
    defaults = Settings()
    for name in ("epochs", "hidden", "batch_size", "max_options", "bucket_window", "seed", "validation_samples", "threads"):
        parser.add_argument("--" + name.replace("_", "-"), type=int, default=getattr(defaults, name))
    for name in ("learning_rate", "weight_decay", "gradient_clip"):
        parser.add_argument("--" + name.replace("_", "-"), type=float, default=getattr(defaults, name))
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    args = parser.parse_args(argv)
    settings = Settings(**{key: getattr(args, key) for key in asdict(defaults)})
    with Cache(args.cache) as cache:
        print(f"Verified TRAIN and VALIDATION in {cache.verification_seconds:.3f} seconds.", flush=True)
        print(f"Dataset SHA-256={cache.manifest['dataset_index_sha256']}", flush=True)
        print(f"Specification SHA-256={SPEC_HASH}", flush=True)
        if not args.verify_only:
            fit(cache, args.output, settings)


if __name__ == "__main__":
    main()