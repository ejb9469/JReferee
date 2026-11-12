"""Board/order encoders and a masked autoregressive movement policy."""

from dataclasses import dataclass
import math

import numpy as np
import torch
from torch import nn


MODEL_SCHEMA = "jreferee-gru-policy-v1"


@dataclass
class Step:
    active: torch.Tensor
    owners: torch.Tensor
    counts: torch.Tensor
    starts: torch.Tensor
    options: torch.Tensor
    targets: torch.Tensor
    locations: torch.Tensor


@dataclass
class Batch:
    board: torch.Tensor
    context: torch.Tensor
    steps: list[Step]
    examples: list

    @classmethod
    def make(cls, examples, device="cpu"):
        if not examples:
            raise ValueError("Cannot build an empty batch")

        def tensor(values):
            return torch.as_tensor(np.asarray(values, dtype=np.int64), device=device)

        offsets = [np.concatenate(([0], np.cumsum(e.counts))) for e in examples]
        steps = []
        for time in range(max(len(e.locations) for e in examples)):
            active = [i for i, e in enumerate(examples) if time < len(e.locations)]
            counts = [int(examples[i].counts[time]) for i in active]
            starts = np.concatenate(([0], np.cumsum(counts)[:-1]))
            options = np.concatenate([examples[i].options[offsets[i][time]:offsets[i][time + 1]] for i in active])
            steps.append(Step(tensor(active), tensor(np.repeat(np.arange(len(active)), counts)),
                              tensor(counts), tensor(starts), tensor(options),
                              tensor(starts + [examples[i].targets[time] for i in active]),
                              tensor([examples[i].locations[time] for i in active])))
        return cls(tensor(np.stack([e.board for e in examples])),
                   tensor(np.stack([e.context for e in examples])), steps, examples)


def segment_log_softmax(scores, counts):
    maxima = torch.segment_reduce(scores.detach(), "max", lengths=counts)
    shifted = scores - torch.repeat_interleave(maxima, counts, output_size=scores.numel())
    sums = torch.segment_reduce(shifted.exp(), "sum", lengths=counts)
    return shifted - torch.repeat_interleave(sums.log(), counts, output_size=scores.numel())


def segment_argmax(scores, counts):
    maxima = torch.segment_reduce(scores, "max", lengths=counts)
    best = scores == torch.repeat_interleave(maxima, counts, output_size=scores.numel())
    indices = torch.arange(scores.numel(), device=scores.device, dtype=scores.dtype)
    return torch.segment_reduce(indices.masked_fill(~best, float("inf")), "min", lengths=counts).long()


class MovementGRU(nn.Module):
    def __init__(self, spec, hidden=128):
        super().__init__()
        if type(hidden) is not int or not 16 <= hidden <= 512:
            raise ValueError("Hidden size must be 16..512")
        self.hidden = hidden
        self.location = nn.Embedding(82, 16, padding_idx=0)
        self.unit = nn.Embedding(3, 4, padding_idx=0)
        self.power = nn.Embedding(8, 8, padding_idx=0)
        self.order = nn.Embedding(5, 8, padding_idx=0)
        self.phase = nn.Embedding(3, 4, padding_idx=0)
        self.terrain = nn.Embedding(3, 4)
        self.board_in = nn.Linear(78, hidden)
        self.board_layers = nn.ModuleList([nn.Linear(2 * hidden, hidden) for _ in range(2)])
        self.initial = nn.Linear(hidden + 13, hidden)
        self.order_in = nn.Linear(60 + 3 * hidden, hidden)
        self.query = nn.Linear(2 * hidden, hidden)
        self.bias = nn.Linear(hidden, 1)
        self.gru = nn.GRUCell(hidden, hidden)
        self.register_buffer("positions", torch.tensor(spec["board_locations"], dtype=torch.long))
        self.register_buffer("terrains", torch.tensor(spec["terrain"], dtype=torch.long))
        self.register_buffer("homes", torch.tensor(spec["home_owners"], dtype=torch.long))
        self.register_buffer("centers", torch.tensor(spec["supply_centers"], dtype=torch.float32))
        self.register_buffer("rows", torch.tensor([75 if r < 0 else r for r in spec["location_board_rows"]], dtype=torch.long))
        adjacency = torch.eye(75)
        for name in ("army_direct", "fleet_direct"):
            for origin, destination in spec[name]:
                adjacency[self.rows[origin], self.rows[destination]] = 1
        self.register_buffer("adjacency", adjacency / adjacency.sum(dim=1, keepdim=True))
        self.year_origin, self.year_scale = spec["year_origin"], spec["year_scale"]

    def prepare(self, batch):
        board, context = batch.board, batch.context
        size = board.shape[0]
        global_features = torch.cat((self.power(context[:, 0]), self.phase(context[:, 1]),
                                     ((context[:, 2] - self.year_origin).float() / self.year_scale).unsqueeze(1)), dim=1)
        local = torch.cat((self.unit(board[:, :, 0]), self.power(board[:, :, 1]),
                           self.location(board[:, :, 2]), self.power(board[:, :, 3]),
                           self.location(self.positions).expand(size, -1, -1),
                           self.terrain(self.terrains).expand(size, -1, -1),
                           self.power(self.homes).expand(size, -1, -1),
                           self.centers.expand(size, -1).unsqueeze(2),
                           global_features.unsqueeze(1).expand(-1, 75, -1)), dim=2)
        encoded = self.board_in(local).relu()
        for layer in self.board_layers:
            encoded = (encoded + layer(torch.cat((encoded, self.adjacency @ encoded), dim=2))).relu()
        initial = self.initial(torch.cat((encoded.mean(dim=1), global_features), dim=1)).tanh()
        encoded = torch.cat((encoded, encoded.new_zeros(size, 1, self.hidden)), dim=1)
        features = []
        for step in batch.steps:
            unit, origin, order, target, auxiliary = step.options.unbind(dim=1)
            owners = step.active[step.owners]
            fields = torch.cat((self.unit(unit), self.location(origin), self.order(order),
                                self.location(target), self.location(auxiliary),
                                encoded[owners, self.rows[origin]], encoded[owners, self.rows[target]],
                                encoded[owners, self.rows[auxiliary]]), dim=1)
            features.append(self.order_in(fields).tanh())
        return encoded, initial, features

    def decode(self, batch, prepared, mode="teacher", generator=None):
        if mode not in ("teacher", "greedy", "sample"):
            raise ValueError(f"Unknown decoding mode: {mode}")
        board, hidden, features = prepared
        results = []
        for step, options in zip(batch.steps, features):
            active_hidden = hidden[step.active]
            query = self.query(torch.cat((active_hidden, board[step.active, self.rows[step.locations]]), dim=1)).tanh()
            scores = (options * query[step.owners]).sum(dim=1) / math.sqrt(self.hidden) + self.bias(options).squeeze(1)
            logp = segment_log_softmax(scores, step.counts)
            if mode == "teacher":
                selected = step.targets
            elif mode == "greedy":
                selected = segment_argmax(logp, step.counts)
            else:
                uniform = torch.rand(scores.shape, device=scores.device, generator=generator).clamp_min(torch.finfo(scores.dtype).tiny)
                selected = segment_argmax(logp - (-uniform.log()).log(), step.counts)
            results.append((logp, selected))
            hidden = hidden.index_copy(0, step.active, self.gru(options[selected], active_hidden))
        return results

    def forward(self, batch):
        return self.decode(batch, self.prepare(batch))


def sampled_actions(batch, decoded):
    actions = [[] for _ in batch.examples]
    fields = [[] for _ in batch.examples]
    for step, (_, selected) in zip(batch.steps, decoded):
        for owner, local, order in zip(step.active.tolist(), (selected - step.starts).tolist(),
                                       step.options[selected].tolist()):
            actions[owner].append(local)
            fields[owner].append(order)
    return actions, fields


def coordination(fields, canonical):
    own = {canonical[order[1]]: order for order in fields}
    supports = bad_supports = convoys = bad_convoys = 0
    for unit, origin, kind, target, destination in fields:
        if kind not in (3, 4) or canonical[target] not in own:
            continue
        referenced = own[canonical[target]]
        if kind == 3:
            supports += 1
            matches = referenced[2] != 2 if destination == 0 else (
                referenced[2] == 2 and canonical[referenced[3]] == canonical[destination])
            bad_supports += not matches
        else:
            convoys += 1
            bad_convoys += not (referenced[0] == 1 and referenced[2] == 2
                               and canonical[referenced[3]] == canonical[destination])
    return supports, bad_supports, convoys, bad_convoys