"""Warm-start value regression from replay-verified, naturally completed self-play."""

import argparse
from dataclasses import asdict, dataclass
import math
from pathlib import Path
import platform
import time

import numpy as np
import torch

from .progress import Progress
from .self_play_cache import SelfPlayCache, SPLITS, integer, require
from .self_play_value_data import ValueData
from .self_play_value_model import MODEL_SCHEMA, load_model, load_start, save_model
from .value_cache import REWARD, SPEC_HASH, current_sos
from .value_train import atomic_json, batch, metrics, predict, training_mean


@dataclass(frozen=True)
class Settings:
    epochs: int = 10
    batch_size: int = 128
    learning_rate: float = 0.0001
    weight_decay: float = 0.0001
    gradient_clip: float = 1.0
    seed: int = 7
    threads: int = 4

    def __post_init__(self):
        for name, low, high in (("epochs", 1, 10000), ("batch_size", 1, 4096),
                               ("seed", 0, 2**63 - 1), ("threads", 1, 256)):
            integer(getattr(self, name), low, high, name)
        for name in ("learning_rate", "weight_decay", "gradient_clip"):
            value = getattr(self, name)
            require(type(value) in (int, float) and math.isfinite(value)
                    and (value >= 0 if name == "weight_decay" else value > 0), "Invalid " + name)


def configure(settings):
    torch.set_num_threads(settings.threads)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(settings.seed)


def evaluate(model, split, data, settings):
    return metrics(split, predict(model, split, settings.batch_size), data.spec["output_powers"])


def baselines(splits, data):
    mean = training_mean(splits["TRAIN"])
    validation = splits["VALIDATION"]
    return mean.tolist(), {
        "train_mean": metrics(validation, np.broadcast_to(mean, (len(validation), 7)), data.spec["output_powers"]),
        "current_sos": metrics(validation, current_sos(validation.board), data.spec["output_powers"]),
    }


def verify(data, initial_value, settings, progress):
    data.preflight()
    configure(settings)
    splits = data.splits()
    model, initial = load_start(initial_value, data)
    mean, references = baselines(splits, data)
    results = {split: evaluate(model, splits[split], data, settings) for split in SPLITS}
    for split, result in results.items():
        score = result["overall"]
        progress.summary("VALID", f"{split}: games={score['games']} positions={score['positions']} "
                         f"MSE={score['mse']:.8f} game-mean MSE={score['game_mean_mse']:.8f}")
    progress.summary("INFO", "Verification only: no optimizer, checkpoint writes, policy update or TEST access.")
    return {"initial_value": initial, "train_mean": mean, "baselines": references, "splits": results}


def fit(data, initial_value, output, settings, progress):
    data.preflight()
    configure(settings)
    splits = data.splits()
    model, initial = load_start(initial_value, data)
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    require(output.parent.is_dir(), "Output parent must already exist")
    mean, references = baselines(splits, data)
    initial_metrics = evaluate(model, splits["VALIDATION"], data, settings)
    best, best_epoch = initial_metrics["overall"]["mse"], 0
    report = {
        "schema": "jreferee-self-play-value-training-v1", "complete": False,
        "model_schema": MODEL_SCHEMA, "reward_contract": REWARD, "specification_sha256": SPEC_HASH,
        "dataset_cohort_sha256": data.fingerprint, "datasets": data.receipts(),
        "self_play_split_seed": str(data.split_seed), "initial_value": initial,
        "source_admission": data.admission, "producer_configurations": data.producers,
        "settings": asdict(settings), "hidden": model.hidden, "device": "cpu",
        "objective": "mean_over_positions(mean_squared_error_over_seven_powers)",
        "selection": "minimum_validation_position_mse_including_epoch_zero; strict_improvement",
        "policy_updated": False, "anchor_updated": False, "test_opened": False,
        "promotion_performed": False, "optimizer_resumed": False,
        "train_mean": mean, "baselines": references, "initial_validation": initial_metrics,
        "best_epoch": 0, "best_validation_mse": best, "epochs": [],
        "versions": {"python": platform.python_version(), "numpy": str(np.__version__), "torch": str(torch.__version__)},
    }
    output.mkdir()
    atomic_json(output / "training.json", report)
    save_model(output / "best.pt", model, data, initial, 0, best)
    progress.summary("VALID", f"Epoch 0 validation MSE={best:.8f}")
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings.learning_rate, weight_decay=settings.weight_decay)
    generator = np.random.default_rng(settings.seed)
    training = splits["TRAIN"]
    for epoch in range(1, settings.epochs + 1):
        started = time.perf_counter()
        model.train()
        order = generator.permutation(len(training))
        seen = steps = 0
        loss_sum = 0.0
        progress.start("TRAIN", f"Value epoch {epoch}/{settings.epochs}", len(training), unit="positions")
        for start in range(0, len(order), settings.batch_size):
            indices = order[start:start + settings.batch_size]
            board, context, target = batch(training, indices)
            optimizer.zero_grad(set_to_none=True)
            loss = (model(board, context) - target).square().mean()
            require(torch.isfinite(loss).item(), "Nonfinite value loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), settings.gradient_clip, error_if_nonfinite=True)
            optimizer.step()
            require(all(torch.isfinite(p).all().item() for p in model.parameters()), "Nonfinite value weights")
            seen += len(indices)
            steps += 1
            loss_sum += loss.item() * len(indices)
            progress.update(seen, f"online MSE {loss_sum / seen:.8f}")
        require(seen == len(training), "Incomplete value epoch")
        progress.finish()
        validation = evaluate(model, splits["VALIDATION"], data, settings)
        score = validation["overall"]["mse"]
        if score < best:
            best, best_epoch = score, epoch
            save_model(output / "best.pt", model, data, initial, epoch, score)
        report["epochs"].append({
            "epoch": epoch, "positions": seen, "optimizer_steps": steps, "online_train_mse": loss_sum / seen,
            "validation": validation, "elapsed_seconds": time.perf_counter() - started,
        })
        report.update(best_epoch=best_epoch, best_validation_mse=best)
        atomic_json(output / "training.json", report)
        progress.summary("EPOCH", f"Epoch {epoch}: validation MSE={score:.8f}; selected epoch={best_epoch}")
    selected, payload, checkpoint_hash = load_model(output / "best.pt", data.spec)
    require(payload["epoch"] == best_epoch and payload["initial_value"] == initial
            and payload["datasets"] == data.receipts() and payload["split_seed"] == str(data.split_seed),
            "Selected value checkpoint identity mismatch")
    selected_metrics = evaluate(selected, splits["VALIDATION"], data, settings)
    require(math.isclose(selected_metrics["overall"]["mse"], best, rel_tol=1e-6, abs_tol=1e-9),
            "Reloaded value checkpoint does not reproduce its selected score")
    report.update(complete=True, checkpoint_sha256=checkpoint_hash, selected_validation=selected_metrics,
                  improved_over_initial=best < initial_metrics["overall"]["mse"],
                  beats_baselines={name: best < value["overall"]["mse"] for name, value in references.items()})
    atomic_json(output / "training.json", report)
    progress.summary("DONE", f"Saved selected value epoch {best_epoch} to {output / 'best.pt'}")
    progress.summary("INFO", "Policy and human anchor unchanged. No TEST access, deployment or strength promotion.")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, nargs="+", required=True)
    parser.add_argument("--initial-value", type=Path, required=True)
    parser.add_argument("--expected-cohort")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("-v", "--verbose", action="store_true")
    defaults = Settings()
    for name in ("epochs", "batch_size", "seed", "threads"):
        parser.add_argument("--" + name.replace("_", "-"), type=int, default=getattr(defaults, name))
    for name in ("learning_rate", "weight_decay", "gradient_clip"):
        parser.add_argument("--" + name.replace("_", "-"), type=float, default=getattr(defaults, name))
    args = parser.parse_args()
    require((args.verify_only and args.output is None) or (not args.verify_only and args.output is not None),
            "Use --verify-only without --output, or supply a new training --output")
    settings = Settings(**{name: getattr(args, name) for name in asdict(defaults)})
    progress = Progress(args.verbose)
    caches = []
    for path in args.cache:
        progress.message("CACHE", f"Verifying {path}")
        caches.append(SelfPlayCache(path))
    data = ValueData(caches)
    require(args.expected_cohort is None or args.expected_cohort == data.fingerprint,
            "Value caches differ from the explicitly approved cohort")
    for split, counts in data.admission.items():
        progress.summary("INFO", f"{split}: {counts}")
    progress.summary("INFO", "Value cohort SHA-256=" + data.fingerprint)
    if args.verify_only:
        verify(data, args.initial_value, settings, progress)
    else:
        fit(data, args.initial_value, args.output, settings, progress)


if __name__ == "__main__":
    main()