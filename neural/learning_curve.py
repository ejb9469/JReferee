"""Whole-game supervised learning curves with a fixed validation set."""

import argparse
from contextlib import contextmanager
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import platform
import re
import time

import numpy as np
import torch

from .cache import Cache, SPEC_HASH, digest, integer, require
from .progress import Progress, duration
from .train import Settings, atomic_json, evaluate, fit


SCHEMA = "jreferee-supervised-learning-curve-v1"
SPLITS = ("TRAIN", "VALIDATION")


def receipt(path):
    return {"bytes": path.stat().st_size, "sha256": digest(path)}


def sources():
    root = Path(__file__).resolve().parent
    return {name: receipt(root / name) for name in
            ("learning_curve.py", "cache.py", "model.py", "train.py", "progress.py")}


def catalog(cache, progress):
    result = {}
    for split in SPLITS:
        games = {}
        progress.start("CACHE", f"Index {split} games", len(cache.indices[split]))
        for index, (shard, row, units, options) in enumerate(cache.indices[split]):
            metadata = cache.get(shard, row).metadata
            key = metadata["source"], metadata["game_key"]
            game = games.setdefault(key, {
                **metadata, "rows": [], "orders": 0, "options": 0,
            })
            require(game["communication_type"] == metadata["communication_type"],
                    f"Communication label changed within game: {key}")
            game["rows"].append(index)
            game["orders"] += int(units)
            game["options"] += int(options)
            progress.update(index + 1)
        progress.finish(f"{len(games):,} games")
        result[split] = games
    require(all(result.values()), "TRAIN and VALIDATION must contain games")
    require(not result["TRAIN"].keys() & result["VALIDATION"].keys(), "Game crosses splits")
    return result


def describe(games):
    return {
        "games": len(games), "examples": sum(len(game["rows"]) for game in games),
        "orders": sum(game["orders"] for game in games),
        "options": sum(game["options"] for game in games),
        "members": [{key: game[key] for key in ("source", "game_key", "communication_type")}
                    for game in sorted(games, key=lambda game: (game["source"], game["game_key"]))],
    }


def selections(games, percentages, seed):
    integer(seed, 0, 2**63 - 1, "subset seed")
    for percent in percentages:
        integer(percent, 1, 100, "game percentage")
    require(len(percentages) >= 2 and list(percentages) == sorted(set(percentages))
            and percentages[-1] == 100, "Use increasing, distinct percentages ending in 100")
    strata = {}
    for game in games.values():
        strata.setdefault((game["source"], game["communication_type"]), []).append(game)

    def rank(game):
        raw = json.dumps([SCHEMA, seed, game["source"], game["game_key"]],
                         ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(raw).digest(), game["source"], game["game_key"]

    ordered = [sorted(group, key=rank) for group in strata.values()]
    result, previous = [], set()
    for percent in percentages:
        chosen = [game for group in ordered for game in group[:(len(group) * percent + 99) // 100]]
        identities = {(game["source"], game["game_key"]) for game in chosen}
        require(previous < identities, "Percentages produced identical subsets; use fewer percentages")
        previous = identities
        rows = np.asarray(sorted(row for game in chosen for row in game["rows"]), dtype=np.int64)
        result.append(({"percent": percent, "directory": f"p{percent:03d}", "training": describe(chosen)}, rows))
    return result


@contextmanager
def training_subset(cache, rows):
    original_indices, original_info = cache.indices["TRAIN"], cache.split_info["TRAIN"]
    selected = original_indices[rows]
    cache.indices["TRAIN"] = selected
    cache.split_info["TRAIN"] = {
        **original_info, "examples": len(selected),
        "slots": int(selected[:, 2].sum()), "options": int(selected[:, 3].sum()),
    }
    try:
        yield cache
    finally:
        cache.indices["TRAIN"], cache.split_info["TRAIN"] = original_indices, original_info


def check_inputs(cache, expected, code):
    require(receipt(cache.root / "manifest.json") == expected["manifest.json"],
            "Cache manifest changed during the experiment")
    require(receipt(cache.root / "specification.json") == expected["specification.json"],
            "Input specification changed during the experiment")
    require(sources() == code, "Python sources changed during the experiment")
    for shard in range(len(cache.shards)):
        cache._check_files(shard)


def metrics(validation):
    return {key: value for key, value in validation.items() if key != "elapsed_seconds"}


def run(cache_path, output, expected_cache_sha256, settings,
        percentages=(25, 50, 100), subset_seed=7, progress=None):
    progress = Progress() if progress is None else progress
    require(settings.device == "cpu", "This experiment uses CPU training")
    require(isinstance(expected_cache_sha256, str)
            and re.fullmatch("[0-9a-f]{64}", expected_cache_sha256), "Invalid expected cache SHA-256")
    cache_path, output = Path(cache_path).resolve(), Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    output = output.resolve()
    require(output.parent.is_dir() and not output.is_relative_to(cache_path),
            "Use a new output directory with an existing parent outside the cache")
    require(digest(cache_path / "manifest.json") == expected_cache_sha256, "Unexpected cache manifest")
    started = time.perf_counter()
    with Cache(cache_path, splits=SPLITS, progress=progress) as cache:
        require(cache.manifest_hash == expected_cache_sha256, "Cache changed while opening")
        require(all(len(cache.indices[split]) and cache.indices[split][:, 3].max() <= settings.max_options
                    for split in SPLITS), "Empty split or example exceeding --max-options")
        games = catalog(cache, progress)
        points = selections(games["TRAIN"], percentages, subset_seed)
        inputs = {name: receipt(cache.root / name) for name in ("manifest.json", "specification.json")}
        require(inputs["manifest.json"]["sha256"] == expected_cache_sha256
                and inputs["specification.json"]["sha256"] == SPEC_HASH, "Cache metadata changed")
        code = sources()
        plan = {
            "schema": SCHEMA, "parent_cache": str(cache_path), "inputs": inputs, "sources": code,
            "dataset_index_sha256": cache.manifest["dataset_index_sha256"],
            "filter": cache.filter, "settings": asdict(settings),
            "versions": {"python": platform.python_version(), "numpy": str(np.__version__),
                         "torch": str(torch.__version__)},
            "subset_seed": subset_seed, "strata": ["source", "communication_type"],
            "selection": "SHA256([schema,subset_seed,source,game_key]); ceiling per stratum",
            "validation": describe(list(games["VALIDATION"].values())),
            "points": [point for point, _ in points],
            "budget": "SAME_EPOCHS_PER_SIZE", "initialization": "FRESH_SAME_SEED_PER_SIZE",
            "test_evaluated": False, "promotion_performed": False,
        }
        check_inputs(cache, inputs, code)
        output.mkdir()
        atomic_json(output / "plan.json", plan)
        result = {"schema": SCHEMA, "complete": False, "plan": receipt(output / "plan.json"),
                  "points": [], "test_evaluated": False, "promotion_performed": False}
        atomic_json(output / "curve.json", result)
        baseline = None
        for point, rows in points:
            percent, directory = point["percent"], output / point["directory"]
            progress.summary("INFO", f'{percent}%: {point["training"]["games"]:,} TRAIN games; '
                             f'{len(rows):,} examples; {settings.epochs} epochs; {settings.threads} CPU threads')
            check_inputs(cache, inputs, code)
            run_started = time.perf_counter()
            with training_subset(cache, rows):
                model, training = fit(cache, directory, settings, progress)
                selected = min(training["epochs"], key=lambda epoch: epoch["validation"]["overall"]["order_nll"])
                require(selected["epoch"] == training["best_epoch"], "Checkpoint selection differs")
                checked = evaluate(model, cache, settings, progress, "Selected checkpoint readback")
                require(metrics(checked) == metrics(selected["validation"]), "Selected validation did not reproduce")
            initial = metrics(training["initial_validation"])
            if baseline is None:
                baseline = initial
            require(initial == baseline, "Initial validation differs between subset runs")
            require(digest(directory / "policy.pt") == training["model_sha256"], "Checkpoint receipt differs")
            check_inputs(cache, inputs, code)
            result["points"].append({
                "percent": percent, "directory": point["directory"],
                "training_games": point["training"]["games"], "training_examples": len(rows),
                "training_orders": point["training"]["orders"], "selected_epoch": training["best_epoch"],
                "selected_validation": checked,
                "last_validation_order_nll": training["epochs"][-1]["validation"]["overall"]["order_nll"],
                "selected_at_epoch_budget": training["best_epoch"] == settings.epochs,
                "elapsed_seconds": time.perf_counter() - run_started,
                "files": {name: receipt(directory / name) for name in ("training.json", "policy.pt")},
            })
            atomic_json(output / "curve.json", result)
        require(receipt(output / "plan.json") == result["plan"], "Experiment plan changed")
        for point in result["points"]:
            for name, expected in point["files"].items():
                require(receipt(output / point["directory"] / name) == expected, f"Output changed: {name}")
        check_inputs(cache, inputs, code)
        result.update(complete=True, elapsed_seconds=time.perf_counter() - started)
        atomic_json(output / "curve.json", result)
    progress.summary("DONE", f'Learning curve completed in {duration(result["elapsed_seconds"])}')
    for point in result["points"]:
        progress.summary("VALID", f'{point["percent"]}% | games={point["training_games"]} | '
                         f'epoch={point["selected_epoch"]} | '
                         f'NLL={point["selected_validation"]["overall"]["order_nll"]:.6f}')
    progress.summary("INFO", "Keep plan.json, curve.json and all checkpoints. TEST was not opened.")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--cache-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--percentages", type=int, nargs="+", default=[25, 50, 100])
    parser.add_argument("--subset-seed", type=int, default=7)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    settings = Settings(epochs=args.epochs, threads=args.threads, seed=args.seed, validation_samples=4)
    run(args.cache, args.output, args.cache_sha256, settings, args.percentages,
        args.subset_seed, Progress(args.verbose))


if __name__ == "__main__":
    main()