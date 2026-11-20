"""Warm starts and distinct, provenance-bound self-play value checkpoints."""

import io
import json
from pathlib import Path

import torch

from .self_play_cache import checked_hash, fields, integer, long_text, require, sha
from .self_play_model import snapshot
from .self_play_value_data import cohort
from .value_cache import REWARD, SPEC_HASH
from .value_model import MODEL_SCHEMA as BASE_SCHEMA, ValueNetwork, restore
from .value_train import load_model as load_base


MODEL_SCHEMA = "jreferee-self-play-value-network-v1"


def model_from(payload, spec):
    fields(payload, "schema reward_contract specification_sha256 specification config datasets split_seed "
           "dataset_cohort_sha256 initial_value epoch validation_mse state_dict", "self-play value checkpoint")
    require(payload["schema"] == MODEL_SCHEMA and payload["reward_contract"] == REWARD
            and payload["specification_sha256"] == SPEC_HASH
            and type(payload["specification"]) is str
            and sha(payload["specification"].encode()) == SPEC_HASH
            and json.loads(payload["specification"]) == spec, "Incompatible self-play value specification")
    seed = long_text(payload["split_seed"])
    require(cohort(payload["datasets"], seed) == payload["dataset_cohort_sha256"], "Value cohort mismatch")
    fields(payload["initial_value"], "sha256 schema epoch", "initial value")
    checked_hash(payload["initial_value"]["sha256"])
    require(payload["initial_value"]["schema"] in (BASE_SCHEMA, MODEL_SCHEMA), "Unknown initial value schema")
    integer(payload["initial_value"]["epoch"], 0 if payload["initial_value"]["schema"] == MODEL_SCHEMA else 1,
            10000, "initial value epoch")
    integer(payload["epoch"], 0, 10000, "value epoch")
    score = payload["validation_mse"]
    require(type(score) in (int, float) and 0 <= score <= 1, "Invalid value validation MSE")
    fields(payload["config"], "hidden", "value configuration")
    return restore(ValueNetwork(spec, **payload["config"]), payload["state_dict"])


def load_model(path, spec):
    raw = snapshot(path)
    payload = torch.load(io.BytesIO(raw), map_location="cpu", weights_only=True)
    return model_from(payload, spec), payload, sha(raw)


def load_start(path, data):
    raw = snapshot(path)
    payload = torch.load(io.BytesIO(raw), map_location="cpu", weights_only=True)
    require(type(payload) is dict, "Expected a value checkpoint")
    if payload.get("schema") == BASE_SCHEMA:
        model, payload = load_base(io.BytesIO(raw), data.spec)
    elif payload.get("schema") == MODEL_SCHEMA:
        model = model_from(payload, data.spec)
        require(long_text(payload["split_seed"]) == data.split_seed, "Warm-start value split seed mismatch")
    else:
        raise ValueError("Unsupported initial value checkpoint")
    return model, {"sha256": sha(raw), "schema": payload["schema"], "epoch": payload["epoch"]}


def save_model(path, model, data, initial, epoch, score):
    payload = {
        "schema": MODEL_SCHEMA, "reward_contract": REWARD, "specification_sha256": SPEC_HASH,
        "specification": data.spec_text, "config": {"hidden": model.hidden},
        "datasets": data.receipts(), "split_seed": str(data.split_seed),
        "dataset_cohort_sha256": data.fingerprint, "initial_value": dict(initial),
        "epoch": epoch, "validation_mse": score,
        "state_dict": {name: value.detach().cpu().clone() for name, value in model.state_dict().items()},
    }
    require(all(torch.isfinite(tensor).all().item() for tensor in payload["state_dict"].values()),
            "Nonfinite value checkpoint")
    path = Path(path)
    pending = path.with_suffix(path.suffix + ".pending")
    with pending.open("xb") as stream:
        torch.save(payload, stream)
    pending.replace(path)