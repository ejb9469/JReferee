"""Bounded CPU training measurements using a disposable in-memory policy."""

import argparse
from dataclasses import asdict
import math
from pathlib import Path
import platform
import time

import numpy as np
import torch

from .progress import Progress
from .self_play_cache import SelfPlayCache, integer, require, sha
from .self_play_data import PolicyData
from .self_play_model import load_start, snapshot
from .self_play_train import Settings, configure, train_batch
from .train import atomic_json


PREPARATION = ("root_decoding", "candidate_planning", "tensor_assembly")
COMPUTATION = ("forward_loss", "backward", "optimizer")
STAGES = PREPARATION + COMPUTATION + ("other",)


def summarize(rows):
    require(rows, "Cannot summarize an empty profile")
    seconds = {stage: math.fsum(row["seconds"][stage] for row in rows) for stage in STAGES}
    elapsed = math.fsum(row["elapsed_seconds"] for row in rows)
    require(elapsed > 0, "Profile duration must be positive")
    preparation = math.fsum(seconds[stage] for stage in PREPARATION)
    computation = math.fsum(seconds[stage] for stage in COMPUTATION)
    roots = sum(row["roots"] for row in rows)
    return {
        "batches": len(rows), "roots": roots,
        "positive_candidates": sum(row["positive_candidates"] for row in rows),
        "elapsed_seconds": elapsed, "seconds_per_batch": elapsed / len(rows),
        "roots_per_second": roots / elapsed, "stage_seconds": seconds,
        "preparation_seconds": preparation, "preparation_percent": 100 * preparation / elapsed,
        "computation_seconds": computation, "computation_percent": 100 * computation / elapsed,
        "other_seconds": seconds["other"], "other_percent": 100 * seconds["other"] / elapsed,
    }


def profile(cache_paths, initial_policy, anchor, output, settings,
            batches=32, warmup_batches=4, progress=None):
    integer(batches, 1, 10000, "measured batches")
    integer(warmup_batches, 0, 10000, "warm-up batches")
    require(settings.device == "cpu", "Profiling supports CPU execution only")
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    require(output.parent.is_dir(), "Profile output parent must already exist")
    progress = Progress() if progress is None else progress

    started = time.perf_counter()
    anchor_hash = sha(snapshot(anchor))
    caches = []
    for path in cache_paths:
        progress.message("CACHE", f"Verifying {path}")
        caches.append(SelfPlayCache(path))
    data = PolicyData(caches, anchor_hash)
    data.preflight(settings.max_options)
    available = (len(data.records["TRAIN"]) + settings.batch_size - 1) // settings.batch_size
    total_batches = warmup_batches + batches
    require(total_batches <= available,
            f"Requested {total_batches} batches including warm-up; TRAIN supplies {available}. "
            "Reduce the requested count or supply more approved caches.")
    cache_seconds = time.perf_counter() - started

    started = time.perf_counter()
    configure(settings)
    model, _, source = load_start(initial_policy, data.spec, data.anchor_sha256, data.split_seed)
    model.to(settings.device)
    model.train()
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings.learning_rate,
                                  weight_decay=settings.weight_decay)
    model_seconds = time.perf_counter() - started
    output.mkdir()
    report = {
        "schema": "jreferee-self-play-profile-v1", "complete": False,
        "settings": {key: value for key, value in asdict(settings).items() if key != "epochs"},
        "initial_policy": source, "anchor_sha256": anchor_hash, "datasets": data.receipts(),
        "self_play_split_seed": str(data.split_seed),
        "policy_roots": {split: len(rows) for split, rows in data.records.items()},
        "split_profiled": "TRAIN", "sample_order": "first epoch, existing seeded root shuffle",
        "requested_measured_batches": batches, "requested_warmup_batches": warmup_batches,
        "startup_seconds": {"cache_verification_and_indexing": cache_seconds,
                            "model_and_optimizer_setup": model_seconds},
        "versions": {"python": platform.python_version(), "numpy": str(np.__version__),
                     "torch": str(torch.__version__)},
        "model_hidden_size": model.hidden,
        "parameters": sum(parameter.numel() for parameter in model.parameters()),
        "cache_bytes_resident_before_batches": True, "prefetch_enabled": False,
        "optimizer_resumed": False, "checkpoint_written": False,
        "validation_evaluated": False, "value_trained": False, "test_evaluated": False,
        "promotion_performed": False, "batches": [],
    }
    atomic_json(output / "profile.json", report)
    progress.summary("INFO", f"Cache verification/indexing: {cache_seconds:.3f}s; "
                     f"model/optimizer setup: {model_seconds:.3f}s.")
    progress.summary("INFO", "CPU profile: optimizer updates affect only the disposable in-memory model.")
    progress.start("PROFILE", f"{warmup_batches} warm-up + {batches} measured batches",
                   total_batches, unit="batches")
    iterator = iter(data.batches("TRAIN", settings.batch_size, settings.seed))
    for index in range(total_batches):
        timings = {stage: 0.0 for stage in STAGES}
        started = time.perf_counter()
        records, roots = next(iterator)
        timings["root_decoding"] = time.perf_counter() - started
        losses = train_batch(model, optimizer, roots, settings, timings)
        elapsed = time.perf_counter() - started
        accounted = math.fsum(timings.values())
        require(all(math.isfinite(value) and value >= 0 for value in timings.values())
                and accounted <= elapsed + 1e-9, "Invalid minibatch timing accounting")
        timings["other"] = max(0.0, elapsed - accounted)
        measured = index >= warmup_batches
        report["batches"].append({
            "index": index, "measured": measured, "roots": len(records),
            "positive_candidates": sum(record.positive_candidates for record in records),
            "cross_entropy": math.fsum(losses) / len(roots),
            "elapsed_seconds": elapsed, "seconds": timings,
        })
        progress.update(index + 1, f'{"measured" if measured else "warm-up"} batch: {elapsed:.3f}s')
    progress.finish()
    require(sha(snapshot(initial_policy)) == source["sha256"], "Initial checkpoint changed during profiling")
    require(sha(snapshot(anchor)) == anchor_hash, "Frozen anchor changed during profiling")
    report["warmup"] = summarize(report["batches"][:warmup_batches]) if warmup_batches else None
    report["measured"] = summarize(report["batches"][warmup_batches:])
    report["optimizer_steps"] = total_batches
    report["complete"] = True
    atomic_json(output / "profile.json", report)
    result = report["measured"]
    for label, name in (("Preparation", "preparation"), ("Model computation", "computation"), ("Other", "other")):
        progress.summary("PROFILE", f'{label}: {result[name + "_seconds"]:.3f}s '
                         f'({result[name + "_percent"]:.1f}%)')
    progress.summary("PROFILE", f'{result["batches"]} measured batches; {result["roots"]} roots; '
                     f'{result["seconds_per_batch"]:.3f}s/batch. Warm-up and startup excluded.')
    progress.summary("DONE", f"Report: {output / 'profile.json'}")
    progress.summary("INFO", "No checkpoint saved, validation selection, value training or TEST evaluation.")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, nargs="+", required=True)
    parser.add_argument("--initial-policy", type=Path, required=True)
    parser.add_argument("--anchor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batches", type=int, default=32)
    parser.add_argument("--warmup-batches", type=int, default=4)
    parser.add_argument("-v", "--verbose", action="store_true")
    defaults = Settings(epochs=1)
    integers = ("batch_size", "candidate_batch_size", "max_options", "seed", "threads")
    reals = ("learning_rate", "weight_decay", "gradient_clip")
    for name in integers + reals:
        parser.add_argument("--" + name.replace("_", "-"), type=int if name in integers else float,
                            default=getattr(defaults, name))
    args = parser.parse_args(argv)
    settings = Settings(epochs=1, **{name: getattr(args, name) for name in integers + reals})
    profile(args.cache, args.initial_policy, args.anchor, args.output, settings,
            args.batches, args.warmup_batches, Progress(args.verbose))


if __name__ == "__main__":
    main()