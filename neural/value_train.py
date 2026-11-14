"""Position-weighted SoS regression, validation selection and explicit baselines."""

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

from .cache import digest, integer, parse_json, require
from .value_cache import REWARD, SPEC_HASH, ValueCache, checked_hash, current_sos
from .value_model import MODEL_SCHEMA, ValueNetwork


@dataclass(frozen=True)
class Settings:
    epochs: int = 20
    hidden: int = 64
    batch_size: int = 128
    learning_rate: float = 0.001
    weight_decay: float = 0.0001
    gradient_clip: float = 1.0
    seed: int = 7
    threads: int = 4

    def __post_init__(self):
        for name, low, high in (("epochs", 1, 10000), ("hidden", 16, 512),
                               ("batch_size", 1, 4096), ("seed", 0, 2**63 - 1),
                               ("threads", 1, 256)):
            integer(getattr(self, name), low, high, name)
        for name in ("learning_rate", "weight_decay", "gradient_clip"):
            value = getattr(self, name)
            require(math.isfinite(value) and (value >= 0 if name == "weight_decay" else value > 0),
                    f"Invalid {name}")


def atomic_json(path, value):
    pending = path.with_suffix(path.suffix + ".pending")
    with pending.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, allow_nan=False, indent=2, sort_keys=True)
        stream.write("\n")
    os.replace(pending, path)


def batch(split, indices):
    return (torch.tensor(split.board[indices], dtype=torch.long),
            torch.tensor(split.context[indices], dtype=torch.long),
            torch.tensor(split.targets[split.game_ids[indices]], dtype=torch.float32))


def training_mean(split):
    require(len(split) > 0, "Empty TRAIN split")
    return np.average(split.targets.astype(np.float64), axis=0, weights=np.diff(split.offsets))


def metrics(split, predicted, powers):
    expected = split.targets[split.game_ids].astype(np.float64)
    require(predicted.shape == expected.shape and np.isfinite(predicted).all()
            and np.all((predicted >= 0) & (predicted <= 1))
            and np.allclose(predicted.sum(1), 1, atol=1e-6, rtol=0), "Invalid predictions")
    error = predicted.astype(np.float64) - expected

    def summary(indices):
        differences = error[indices]
        ids = split.game_ids[indices]
        unique, inverse, counts = np.unique(ids, return_inverse=True, return_counts=True)
        mse = (differences ** 2).mean(1)
        mae = np.abs(differences).mean(1)
        return {
            "positions": len(indices), "games": len(unique),
            "mse": float(mse.mean()), "mae": float(mae.mean()),
            "game_mean_mse": float((np.bincount(inverse, weights=mse) / counts).mean()),
            "game_mean_mae": float((np.bincount(inverse, weights=mae) / counts).mean()),
            "power_mse": dict(zip(powers, map(float, (differences ** 2).mean(0)))),
        }

    result = {"overall": summary(np.arange(len(split)))}
    labels = [row["communication_type"] for row in split.metadata]
    groups = {}
    for g, label in enumerate(labels):
        group = "UNKNOWN" if label is None else "LABEL:" + label
        groups.setdefault(group, []).append(g)
    result["communication"] = {
        label: summary(np.flatnonzero(np.isin(split.game_ids, games)))
        for label, games in sorted(groups.items())
    }
    result["phase"] = {
        str(phase): summary(np.flatnonzero(split.context[:, 0] == phase))
        for phase in sorted(set(map(int, split.context[:, 0])))
    }
    return result


@torch.no_grad()
def predict(model, split, batch_size):
    model.eval()
    result = np.empty((len(split), 7), dtype=np.float32)
    for start in range(0, len(split), batch_size):
        indices = np.arange(start, min(start + batch_size, len(split)))
        board, context, _ = batch(split, indices)
        result[indices] = model(board, context).numpy()
    return result


def save_model(path, model, cache, epoch):
    state = {name: value.detach().cpu().clone() for name, value in model.state_dict().items()}
    require(all(torch.isfinite(value).all().item() for value in state.values()), "Nonfinite model")
    payload = {
        "schema": MODEL_SCHEMA, "reward_contract": REWARD,
        "specification_sha256": SPEC_HASH, "specification": cache.spec_text,
        "dataset_fingerprint": cache.fingerprint, "cache_manifest_sha256": cache.manifest_hash,
        "config": {"hidden": model.hidden}, "epoch": epoch, "state_dict": state,
    }
    pending = path.with_suffix(".pt.pending")
    with pending.open("xb") as stream:
        torch.save(payload, stream)
    os.replace(pending, path)


def load_model(path, spec):
    payload = torch.load(path, map_location="cpu", weights_only=True)
    require(payload["schema"] == MODEL_SCHEMA and payload["reward_contract"] == REWARD
            and payload["specification_sha256"] == SPEC_HASH
            and hashlib.sha256(payload["specification"].encode()).hexdigest() == SPEC_HASH
            and parse_json(payload["specification"]) == spec, "Incompatible value checkpoint")
    checked_hash(payload["dataset_fingerprint"])
    checked_hash(payload["cache_manifest_sha256"])
    integer(payload["epoch"], 1, 10000, "checkpoint epoch")
    require(set(payload["config"]) == {"hidden"}, "Unknown model configuration")
    model = ValueNetwork(spec, **payload["config"])
    state = payload["state_dict"]
    expected = model.state_dict()
    require(state.keys() == expected.keys(), "Checkpoint tensor names differ")
    for name, value in state.items():
        require(isinstance(value, torch.Tensor) and value.shape == expected[name].shape
                and value.dtype == expected[name].dtype and torch.isfinite(value).all().item(),
                f"Invalid checkpoint tensor: {name}")
    for name, value in model.named_buffers():
        require(torch.equal(state[name], value), f"Checkpoint map differs: {name}")
    model.load_state_dict(state, strict=True)
    model.eval()
    return model, payload


def train(cache, output, settings):
    train_split, valid = (cache.splits[name] for name in ("TRAIN", "VALIDATION"))
    require(len(train_split) > 0 and len(valid) > 0, "Training requires nonempty TRAIN and VALIDATION")
    torch.set_num_threads(settings.threads)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(settings.seed)
    generator = np.random.default_rng(settings.seed)
    output = Path(output)
    output.mkdir(parents=False, exist_ok=False)
    powers = cache.spec["output_powers"]
    mean = training_mean(train_split)
    baselines = {
        "train_mean": metrics(valid, np.broadcast_to(mean, (len(valid), 7)), powers),
        "current_sos": metrics(valid, current_sos(valid.board), powers),
    }
    model = ValueNetwork(cache.spec, settings.hidden)
    initial = metrics(valid, predict(model, valid, settings.batch_size), powers)
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings.learning_rate,
                                  weight_decay=settings.weight_decay)
    print("Validation baselines:", json.dumps({k: v["overall"] for k, v in baselines.items()}), flush=True)
    history, best = [], float("inf")
    best_epoch = None
    for epoch in range(1, settings.epochs + 1):
        started = time.perf_counter()
        model.train()
        order = generator.permutation(len(train_split))
        loss_sum = 0.0
        visited = 0
        last_progress = started
        for start in range(0, len(order), settings.batch_size):
            indices = order[start:start + settings.batch_size]
            board, context, target = batch(train_split, indices)
            optimizer.zero_grad(set_to_none=True)
            loss = (model(board, context) - target).square().mean()
            require(torch.isfinite(loss).item(), "Nonfinite training loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), settings.gradient_clip,
                                           error_if_nonfinite=True)
            optimizer.step()
            loss_sum += loss.item() * len(indices)
            visited += len(indices)
            now = time.perf_counter()
            if now - last_progress >= 2:
                print(f"Epoch {epoch}/{settings.epochs}: {visited}/{len(order)} "
                      f"positions; online MSE={loss_sum / visited:.6f}", flush=True)
                last_progress = now
        validation = metrics(valid, predict(model, valid, settings.batch_size), powers)
        score = validation["overall"]["mse"]
        selected = score < best
        if selected:
            best, best_epoch = score, epoch
            save_model(output / "best.pt", model, cache, epoch)
        row = {"epoch": epoch, "online_train_mse": loss_sum / visited,
               "validation": validation, "selected": selected,
               "seconds": time.perf_counter() - started}
        history.append(row)
        print(f"Epoch {epoch}: train={row['online_train_mse']:.6f} validation={score:.6f}"
              + (" SAVED" if selected else ""), flush=True)
    restored, payload = load_model(output / "best.pt", cache.spec)
    selected_metrics = metrics(valid, predict(restored, valid, settings.batch_size), powers)
    require(payload["epoch"] == best_epoch and selected_metrics["overall"]["mse"] == best,
            "Checkpoint readback differs from selected model")
    report = {
        "schema": "jreferee-value-training-v1", "complete": True,
        "model_schema": MODEL_SCHEMA, "reward_contract": REWARD,
        "specification_sha256": SPEC_HASH, "dataset_fingerprint": cache.fingerprint,
        "cache_manifest_sha256": cache.manifest_hash, "settings": asdict(settings),
        "device": "cpu", "python": platform.python_version(),
        "numpy": np.__version__, "torch": str(torch.__version__),
        "objective": "mean over positions of mean squared error over seven powers",
        "selection": "lowest validation position MSE among trained epochs; earlier wins ties",
        "test_opened": False, "train_mean": mean.tolist(), "baselines": baselines,
        "initial_validation": initial, "epochs": history, "best_epoch": best_epoch,
        "selected_validation": selected_metrics, "checkpoint_sha256": digest(output / "best.pt"),
        "beats_baselines": {name: best < result["overall"]["mse"]
                            for name, result in baselines.items()},
    }
    atomic_json(output / "training.json", report)
    print(f"Selected epoch {best_epoch}; validation MSE={best:.6f}; "
          f"beats baselines={report['beats_baselines']}", flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--output")
    parser.add_argument("--verify-only", action="store_true")
    defaults = Settings()
    for name in ("epochs", "hidden", "batch_size", "seed", "threads"):
        parser.add_argument("--" + name.replace("_", "-"), type=int, default=getattr(defaults, name))
    for name in ("learning_rate", "weight_decay", "gradient_clip"):
        parser.add_argument("--" + name.replace("_", "-"), type=float, default=getattr(defaults, name))
    args = parser.parse_args()
    if not args.verify_only and args.output is None:
        parser.error("--output is required for training")
    settings = Settings(**{name: getattr(args, name) for name in Settings.__dataclass_fields__})
    print("Verifying TRAIN and VALIDATION; TEST will not be opened.", flush=True)
    cache = ValueCache(args.cache)
    for name, split in cache.splits.items():
        print(f"{name}: {len(split.metadata)} games, {len(split)} positions", flush=True)
    print("Verified specification:", SPEC_HASH, flush=True)
    if not args.verify_only:
        train(cache, args.output, settings)


if __name__ == "__main__":
    main()