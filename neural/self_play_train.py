"""Distill typed DiL policies with whole-action cross-entropy and held-out selection."""

import argparse
from dataclasses import asdict, dataclass
import math
import os
from pathlib import Path
import platform
import time

import numpy as np
import torch

from .progress import Progress
from .self_play_cache import POWERS, SPLITS, SelfPlayCache, integer, require, sha
from .self_play_data import PolicyData, candidate_chunks, type_specification
from .self_play_model import (
    MODEL_SCHEMA, SPEC_HASH, CandidateBatch, joint_log_probabilities, load_start,
    load_typed_policy, save_typed_policy, snapshot, weighted_loss,
)
from .train import atomic_json


@dataclass(frozen=True)
class Settings:
    epochs: int = 3
    batch_size: int = 8
    candidate_batch_size: int = 32
    max_options: int = 16384
    learning_rate: float = 0.0001
    weight_decay: float = 0.0001
    gradient_clip: float = 1.0
    seed: int = 7
    threads: int = 4
    device: str = "cpu"

    def __post_init__(self):
        integer(self.epochs, 1, 10000, "epochs")
        integer(self.batch_size, 1, 4096, "root minibatch size")
        integer(self.candidate_batch_size, 1, 4096, "candidate minibatch size")
        integer(self.max_options, 1, 1_000_000, "minibatch option limit")
        integer(self.seed, 0, (1 << 63) - 10001, "training seed")
        integer(self.threads, 1, 256, "threads")
        require(self.device in ("cpu", "cuda"), "Device must be cpu or cuda")
        for name in ("learning_rate", "weight_decay", "gradient_clip"):
            value = getattr(self, name)
            require(type(value) in (int, float) and math.isfinite(value)
                    and (value >= 0 if name == "weight_decay" else value > 0), f"Invalid {name}")


def configure(settings):
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    require(settings.device != "cuda" or torch.cuda.is_available(), "CUDA was requested but is unavailable")
    torch.set_num_threads(settings.threads)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(settings.seed)


def _cross_entropies(model, roots, settings, backward=False):
    losses = [0.0] * len(roots)
    seen = 0
    with torch.set_grad_enabled(backward):
        for chunk in candidate_chunks(roots, settings.candidate_batch_size, settings.max_options):
            batch = CandidateBatch.from_chunk(roots, chunk, settings.device)
            joint = joint_log_probabilities(model, batch)
            loss = weighted_loss(joint, batch)
            if backward:
                loss.backward()
            for (root_index, candidate), logp in zip(chunk.references, joint.detach().cpu().tolist()):
                losses[root_index] -= roots[root_index].probabilities[candidate] * logp
                seen += 1
    require(seen == sum(sum(p > 0 for p in root.probabilities) for root in roots),
            "Incomplete candidate pass")
    require(all(math.isfinite(value) and value >= 0 for value in losses), "Invalid root cross-entropy")
    return losses


def _summary(rows):
    require(rows, "Cannot summarize an empty evaluation")
    count = len(rows)
    ce = math.fsum(row[0] for row in rows) / count
    entropy = math.fsum(row[1] for row in rows) / count
    return {
        "roots": count, "cross_entropy": ce, "target_entropy": entropy,
        "kl": ce - entropy, "uniform_cross_entropy": math.fsum(row[2] for row in rows) / count,
    }


@torch.no_grad()
def evaluate(model, data, split, settings, progress=None, label=None):
    require(split in SPLITS and data.records[split], "Evaluation requires a nonempty policy split")
    progress = Progress() if progress is None else progress
    model.eval()
    progress.start("VALID", label or f"{split} evaluation", len(data.records[split]), unit="roots")
    start = time.perf_counter()
    rows, games, powers, profiles = [], {}, {}, {}
    orders = candidates = 0
    for records, roots in data.batches(split, settings.batch_size):
        losses = _cross_entropies(model, roots, settings)
        for record, root, loss in zip(records, roots, losses):
            entropy = -math.fsum(p * math.log(p) for p in root.probabilities if p > 0)
            uniform = math.fsum(root.probabilities) * math.fsum(math.log(n) for n in root.counts)
            row = (loss, entropy, uniform)
            rows.append(row)
            games.setdefault(record.game, []).append(row)
            powers.setdefault(POWERS[root.context[0]], []).append(row)
            profile = ("OPENING" if root.type_ids[0] == 1 else "STANDARD")
            profile += "/" + ("FINAL" if root.type_ids[1] == 1 else "AVERAGE")
            profiles.setdefault(profile, []).append(row)
            orders += record.units
            candidates += record.positive_candidates
        progress.update(len(rows))
    require(len(rows) == len(data.records[split]), "Incomplete root evaluation")
    progress.finish()
    result = _summary(rows)
    result.update(
        split=split, games=len(games), orders=orders, positive_candidates=candidates,
        game_mean_cross_entropy=math.fsum(_summary(group)["cross_entropy"] for group in games.values()) / len(games),
        by_power={key: _summary(group) for key, group in sorted(powers.items())},
        by_profile={key: _summary(group) for key, group in sorted(profiles.items())},
        elapsed_seconds=time.perf_counter() - start,
    )
    return result


def verify(data, initial_policy, settings, progress=None):
    progress = Progress() if progress is None else progress
    data.preflight(settings.max_options)
    configure(settings)
    model, _, source = load_start(initial_policy, data.spec, data.anchor_sha256, data.split_seed)
    model.to(settings.device)
    report = {"initial_policy": source, "anchor_sha256": data.anchor_sha256, "splits": {}}
    for split in SPLITS:
        if data.records[split]:
            metrics = evaluate(model, data, split, settings, progress)
            report["splits"][split] = metrics
            progress.summary("VALID", f'{split}: roots={metrics["roots"]} '
                             f'whole-action CE={metrics["cross_entropy"]:.6f} KL={metrics["kl"]:.6f}')
        else:
            report["splits"][split] = {"status": "EMPTY_SPLIT", "roots": 0}
            progress.summary("WARN", f"{split}: empty; fitting requires both policy splits.")
    progress.summary("INFO", "Verification only: no optimizer, checkpoint writes, value training or TEST access.")
    return report


def fit(data, initial_policy, output, settings, progress=None):
    progress = Progress() if progress is None else progress
    data.preflight(settings.max_options, training=True)
    configure(settings)
    model, specification, source = load_start(
        initial_policy, data.spec, data.anchor_sha256, data.split_seed)
    model.to(settings.device)
    output = Path(output)
    output.mkdir()
    report = {
        "schema": "jreferee-self-play-policy-training-v1", "complete": False,
        "model_schema": MODEL_SCHEMA, "specification_sha256": SPEC_HASH,
        "type_encoding": type_specification(), "settings": asdict(settings),
        "initial_policy": source, "anchor_sha256": data.anchor_sha256,
        "self_play_split_seed": str(data.split_seed), "datasets": data.receipts(),
        "producer_checkpoints": list(data.producer_checkpoints),
        "objective": "mean_over_roots(sum_candidate_q_times_negative_sum_conditional_logp)",
        "selection": "minimum_validation_root_cross_entropy_including_epoch_zero; strict_improvement",
        "anchor_updated": False, "value_trained": False, "test_evaluated": False,
        "promotion_performed": False, "optimizer_resumed": False,
        "versions": {"python": platform.python_version(), "numpy": str(np.__version__),
                     "torch": str(torch.__version__)},
        "parameters": sum(parameter.numel() for parameter in model.parameters()),
        "policy_roots": {split: len(data.records[split]) for split in SPLITS}, "epochs": [],
    }
    atomic_json(output / "training.json", report)
    initial = evaluate(model, data, "VALIDATION", settings, progress, "Initial validation")
    best_loss = initial["cross_entropy"]
    report.update(initial_validation=initial, best_epoch=0, best_validation_cross_entropy=best_loss)
    save_typed_policy(output / "policy.pt", model, data, specification, source, 0, best_loss)
    atomic_json(output / "training.json", report)
    progress.summary("VALID", f"Epoch 0 validation whole-action CE={best_loss:.6f}")
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings.learning_rate, weight_decay=settings.weight_decay)
    for epoch in range(1, settings.epochs + 1):
        model.train()
        progress.start("TRAIN", f"Epoch {epoch}/{settings.epochs}", len(data.records["TRAIN"]), unit="roots")
        started, total, seen, steps = time.perf_counter(), 0.0, 0, 0
        for records, roots in data.batches("TRAIN", settings.batch_size, settings.seed + epoch - 1):
            optimizer.zero_grad(set_to_none=True)
            losses = _cross_entropies(model, roots, settings, backward=True)
            torch.nn.utils.clip_grad_norm_(model.parameters(), settings.gradient_clip, error_if_nonfinite=True)
            optimizer.step()
            require(torch.stack([torch.isfinite(p).all() for p in model.parameters()]).all().item(),
                    "Nonfinite policy parameters after optimizer update")
            total += math.fsum(losses)
            seen += len(records)
            steps += 1
            progress.update(seen, f"online whole-action CE {total / seen:.6f}")
        require(seen == len(data.records["TRAIN"]), "Incomplete training epoch")
        progress.finish()
        validation = evaluate(model, data, "VALIDATION", settings, progress, f"Epoch {epoch} validation")
        score = validation["cross_entropy"]
        report["epochs"].append({
            "epoch": epoch, "online_cross_entropy": total / seen, "roots": seen, "optimizer_steps": steps,
            "elapsed_seconds": time.perf_counter() - started, "validation": validation,
        })
        if score < best_loss:
            best_loss = score
            save_typed_policy(output / "policy.pt", model, data, specification, source, epoch, score)
            report.update(best_epoch=epoch, best_validation_cross_entropy=score)
        atomic_json(output / "training.json", report)
        progress.summary("EPOCH", f"Epoch {epoch}: online CE={total / seen:.6f}; "
                         f"validation CE={score:.6f}; selected epoch={report['best_epoch']}")
    selected, payload, checkpoint_hash = load_typed_policy(output / "policy.pt", data.spec)
    require(payload["epoch"] == report["best_epoch"] and payload["datasets"] == data.receipts()
            and payload["initial_policy"] == source and payload["anchor_sha256"] == data.anchor_sha256
            and payload["split_seed"] == str(data.split_seed), "Selected checkpoint identity mismatch")
    selected.to(settings.device)
    rechecked = evaluate(selected, data, "VALIDATION", settings, progress, "Selected checkpoint readback")
    require(math.isclose(rechecked["cross_entropy"], best_loss, rel_tol=1e-6, abs_tol=1e-7),
            "Reloaded checkpoint does not reproduce its selected score")
    report.update(complete=True, checkpoint_sha256=checkpoint_hash, selected_validation=rechecked,
                  improved_over_initial=best_loss < initial["cross_entropy"])
    atomic_json(output / "training.json", report)
    progress.summary("DONE", f"Saved selected epoch {report['best_epoch']} to {output / 'policy.pt'}")
    if report["best_epoch"] == 0:
        progress.summary("WARN", "No trained epoch improved validation; retained the initialization.")
    progress.summary("INFO", "The human anchor is unchanged. No value training, TEST access or strength promotion.")
    return selected, report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, nargs="+", required=True)
    parser.add_argument("--initial-policy", type=Path, required=True)
    parser.add_argument("--anchor", type=Path, required=True, help="Frozen linear policy.bin; hashed, never trained")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("-v", "--verbose", action="store_true")
    defaults = Settings()
    for name in ("epochs", "batch_size", "candidate_batch_size", "max_options", "seed", "threads"):
        parser.add_argument("--" + name.replace("_", "-"), type=int, default=getattr(defaults, name))
    for name in ("learning_rate", "weight_decay", "gradient_clip"):
        parser.add_argument("--" + name.replace("_", "-"), type=float, default=getattr(defaults, name))
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    args = parser.parse_args(argv)
    require((args.verify_only and args.output is None) or (not args.verify_only and args.output is not None),
            "Use --verify-only without --output, or supply a new --output for training")
    settings = Settings(**{name: getattr(args, name) for name in asdict(defaults)})
    progress = Progress(args.verbose)
    anchor_hash = sha(snapshot(args.anchor))
    caches = []
    for path in args.cache:
        progress.message("CACHE", f"Verifying {path}")
        caches.append(SelfPlayCache(path))
    data = PolicyData(caches, anchor_hash)
    progress.summary("INFO", f"Frozen anchor SHA-256={anchor_hash}")
    progress.summary("INFO", f"Policy roots: TRAIN={len(data.records['TRAIN'])} "
                     f"VALIDATION={len(data.records['VALIDATION'])}")
    if args.verify_only:
        verify(data, args.initial_policy, settings, progress)
    else:
        fit(data, args.initial_policy, args.output, settings, progress)


if __name__ == "__main__":
    main()