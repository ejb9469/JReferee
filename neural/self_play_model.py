"""A baseline-preserving type adapter and candidate-weighted autoregressive loss."""

from dataclasses import dataclass, replace
import io
import json
from pathlib import Path

import numpy as np
import torch
from torch import nn

from .cache import Example, SPEC_HASH
from .model import Batch, MODEL_SCHEMA as BASE_SCHEMA, MovementGRU
from .self_play_cache import checked_hash, fields, integer, long_text, require, sha
from .self_play_data import TYPE_FIELDS, type_features, type_specification
from .train import load_policy as load_base_policy


MODEL_SCHEMA = "jreferee-type-conditioned-gru-v1"
MAX_MODEL_BYTES = 64 * 1024 * 1024


def snapshot(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), f"Expected a regular model file: {path}")
    size = path.stat().st_size
    require(0 < size <= MAX_MODEL_BYTES, "Model file size exceeds the supported bound")
    with path.open("rb") as stream:
        raw = stream.read(size + 1)
    require(len(raw) == size, "Model file changed during reading")
    return raw


@dataclass
class CandidateBatch(Batch):
    type_features: torch.Tensor
    weights: torch.Tensor
    references: tuple
    root_count: int

    @classmethod
    def from_chunk(cls, roots, chunk, device="cpu"):
        require(chunk.root_count == len(roots) and chunk.references, "Invalid candidate chunk")
        prepared, inputs, features, weights = {}, [], [], []
        for root_index, candidate in chunk.references:
            integer(root_index, 0, len(roots) - 1, "root index")
            root = roots[root_index]
            integer(candidate, 0, len(root.actions) - 1, "candidate index")
            require(root.probabilities[candidate] > 0, "Zero-weight candidates must be skipped")
            if root_index not in prepared:
                prepared[root_index] = Example(
                    np.asarray(root.board, dtype=np.uint8), np.asarray(root.context, dtype=np.int32),
                    np.asarray(root.locations, dtype=np.int64), np.asarray(root.counts, dtype=np.int64),
                    np.asarray(root.options, dtype=np.uint8), np.asarray(root.actions[0], dtype=np.int64), {})
            inputs.append(replace(prepared[root_index], targets=np.asarray(root.actions[candidate], dtype=np.int64)))
            features.append(type_features(root))
            weights.append(root.probabilities[candidate])
        base = Batch.make(inputs, device)
        return cls(base.board, base.context, base.steps, base.examples,
                   torch.tensor(features, dtype=torch.float32, device=device),
                   torch.tensor(weights, dtype=torch.float64, device=device),
                   chunk.references, chunk.root_count)


class TypeConditionedGRU(MovementGRU):
    def __init__(self, spec, hidden=128):
        super().__init__(spec, hidden)
        self.type_projection = nn.Linear(len(TYPE_FIELDS), hidden, bias=False)
        nn.init.zeros_(self.type_projection.weight)

    def prepare(self, batch):
        require(isinstance(batch, CandidateBatch), "A typed policy requires CandidateBatch inputs")
        require(batch.type_features.shape == (len(batch.examples), len(TYPE_FIELDS))
                and torch.isfinite(batch.type_features).all().item(), "Invalid encoded type inputs")
        board, initial, options = super().prepare(batch)
        return board, initial + self.type_projection(batch.type_features).tanh(), options


def joint_log_probabilities(model, batch):
    decoded = model(batch)
    require(len(decoded) == len(batch.steps) and batch.steps, "Incomplete autoregressive decode")
    joint = batch.weights.new_zeros(len(batch.examples))
    for step, (logp, _) in zip(batch.steps, decoded):
        require(torch.isfinite(logp).all().item(), "Nonfinite conditional log probabilities")
        joint = joint.index_add(0, step.active, logp[step.targets].double())
    require(torch.isfinite(joint).all().item(), "Nonfinite complete-action log probabilities")
    return joint


def weighted_loss(joint, batch):
    require(joint.shape == batch.weights.shape and batch.root_count > 0, "Invalid weighted loss shape")
    require(torch.isfinite(batch.weights).all().item() and (batch.weights > 0).all().item(),
            "Invalid candidate loss weights")
    loss = -(joint.double() * batch.weights).sum() / batch.root_count
    require(torch.isfinite(loss).item(), "Nonfinite candidate-weighted loss")
    return loss


def _typed_model(payload, spec):
    fields(payload, "schema specification_sha256 specification type_encoding config anchor_sha256 "
           "split_seed initial_policy datasets epoch validation_cross_entropy state_dict", "typed checkpoint")
    require(payload["schema"] == MODEL_SCHEMA and payload["specification_sha256"] == SPEC_HASH
            and type(payload["specification"]) is str
            and sha(payload["specification"].encode()) == SPEC_HASH
            and json.loads(payload["specification"]) == spec, "Incompatible typed policy specification")
    require(payload["type_encoding"] == type_specification(), "Incompatible type encoding")
    checked_hash(payload["anchor_sha256"])
    long_text(payload["split_seed"])
    fields(payload["initial_policy"], "sha256 schema epoch", "initial policy")
    checked_hash(payload["initial_policy"]["sha256"])
    require(payload["initial_policy"]["schema"] in (BASE_SCHEMA, MODEL_SCHEMA), "Unknown initial policy schema")
    integer(payload["initial_policy"]["epoch"], 0, 10000, "initial epoch")
    require(type(payload["datasets"]) is list and payload["datasets"], "Missing dataset receipts")
    seen = set()
    for receipt in payload["datasets"]:
        fields(receipt, "dataset_fingerprint cache_manifest_sha256", "dataset receipt")
        for value in receipt.values():
            checked_hash(value)
        require(receipt["dataset_fingerprint"] not in seen, "Duplicate checkpoint dataset")
        seen.add(receipt["dataset_fingerprint"])
    integer(payload["epoch"], 0, 10000, "checkpoint epoch")
    score = payload["validation_cross_entropy"]
    require(type(score) in (int, float) and np.isfinite(score) and score >= 0, "Invalid checkpoint score")
    fields(payload["config"], "hidden", "model configuration")
    model = TypeConditionedGRU(spec, **payload["config"])
    state, expected = payload["state_dict"], model.state_dict()
    require(isinstance(state, dict) and state.keys() == expected.keys(), "Checkpoint tensor names differ")
    for name, tensor in state.items():
        require(isinstance(tensor, torch.Tensor) and tensor.layout == torch.strided
                and tensor.shape == expected[name].shape and tensor.dtype == expected[name].dtype
                and torch.isfinite(tensor).all().item(), f"Invalid checkpoint tensor: {name}")
    for name, tensor in model.named_buffers():
        require(torch.equal(state[name], tensor), f"Checkpoint map differs: {name}")
    model.load_state_dict(state, strict=True)
    model.eval()
    return model


def load_typed_policy(path, spec):
    raw = snapshot(path)
    payload = torch.load(io.BytesIO(raw), map_location="cpu", weights_only=True)
    return _typed_model(payload, spec), payload, sha(raw)


def load_start(path, spec, anchor_sha256, split_seed):
    raw = snapshot(path)
    payload = torch.load(io.BytesIO(raw), map_location="cpu", weights_only=True)
    require(isinstance(payload, dict), "Expected a policy checkpoint")
    if payload.get("schema") == BASE_SCHEMA:
        base, payload = load_base_policy(io.BytesIO(raw), spec)
        model = TypeConditionedGRU(spec, base.hidden)
        state = model.state_dict()
        state.update(base.state_dict())
        model.load_state_dict(state, strict=True)
    elif payload.get("schema") == MODEL_SCHEMA:
        model = _typed_model(payload, spec)
        require(payload["anchor_sha256"] == anchor_sha256, "Warm-start human anchor mismatch")
        require(long_text(payload["split_seed"]) == split_seed, "Warm-start self-play split seed mismatch")
    else:
        raise ValueError("Unsupported warm-start policy schema")
    integer(payload["epoch"], 0, 10000, "initial epoch")
    model.eval()
    receipt = {"sha256": sha(raw), "schema": payload["schema"], "epoch": payload["epoch"]}
    return model, payload["specification"], receipt


def save_typed_policy(path, model, data, specification, initial_policy, epoch, score):
    require(isinstance(model, TypeConditionedGRU), "Expected a type-conditioned model")
    integer(epoch, 0, 10000, "checkpoint epoch")
    require(type(score) in (int, float) and np.isfinite(score) and score >= 0, "Invalid checkpoint score")
    payload = {
        "schema": MODEL_SCHEMA, "specification_sha256": SPEC_HASH, "specification": specification,
        "type_encoding": type_specification(), "config": {"hidden": model.hidden},
        "anchor_sha256": data.anchor_sha256, "split_seed": str(data.split_seed),
        "initial_policy": dict(initial_policy), "datasets": data.receipts(),
        "epoch": epoch, "validation_cross_entropy": score,
        "state_dict": {name: value.detach().cpu().clone() for name, value in model.state_dict().items()},
    }
    require(all(torch.isfinite(value).all().item() for value in payload["state_dict"].values()),
            "Nonfinite checkpoint weights")
    path = Path(path)
    pending = path.with_suffix(path.suffix + ".pending")
    with pending.open("xb") as stream:
        torch.save(payload, stream)
    pending.replace(path)