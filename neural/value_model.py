"""Seven-power value network using the existing map's direct-edge graph."""

import torch
from torch import nn

from .cache import integer, require


MODEL_SCHEMA = "jreferee-value-network-v1"


class ValueNetwork(nn.Module):
    def __init__(self, spec, hidden=64):
        super().__init__()
        self.hidden = integer(hidden, 16, 512, "hidden size")
        board = spec["board_specification"]
        self.unit = nn.Embedding(3, 4, padding_idx=0)
        self.power = nn.Embedding(8, 8, padding_idx=0)
        self.location = nn.Embedding(82, 16, padding_idx=0)
        self.phase = nn.Embedding(4, 4, padding_idx=0)
        self.terrain = nn.Embedding(3, 4)
        self.board_in = nn.Linear(70, hidden)
        self.layers = nn.ModuleList([nn.Linear(2 * hidden, hidden) for _ in range(2)])
        self.head = nn.Sequential(nn.Linear(hidden + 5, hidden), nn.ReLU(), nn.Linear(hidden, 7))
        for name, values in (("positions", board["board_locations"]),
                             ("terrains", board["terrain"]), ("homes", board["home_owners"])):
            self.register_buffer(name, torch.tensor(values, dtype=torch.long))
        self.register_buffer("centers", torch.tensor(board["supply_centers"], dtype=torch.float32))
        adjacency = torch.eye(75)
        rows = board["location_board_rows"]
        for field in ("army_direct", "fleet_direct"):
            for origin, destination in board[field]:
                adjacency[rows[origin], rows[destination]] = 1
        self.register_buffer("adjacency", adjacency / adjacency.sum(1, keepdim=True))
        self.year_origin, self.year_scale = spec["year_origin"], spec["year_scale"]

    def forward(self, board, context):
        size = board.shape[0]
        global_features = torch.cat((self.phase(context[:, 0]),
                ((context[:, 1] - self.year_origin).float() / self.year_scale).unsqueeze(1)), dim=1)
        local = torch.cat((
            self.unit(board[:, :, 0]), self.power(board[:, :, 1]),
            self.location(board[:, :, 2]), self.power(board[:, :, 3]),
            self.location(self.positions).expand(size, -1, -1),
            self.terrain(self.terrains).expand(size, -1, -1),
            self.power(self.homes).expand(size, -1, -1),
            self.centers.expand(size, -1).unsqueeze(2),
            global_features.unsqueeze(1).expand(-1, 75, -1),
        ), dim=2)
        encoded = self.board_in(local).relu()
        for layer in self.layers:
            encoded = (encoded + layer(torch.cat((encoded, self.adjacency @ encoded), dim=2))).relu()
        return self.head(torch.cat((encoded.mean(1), global_features), dim=1)).softmax(dim=1)


def restore(model, state):
    expected = model.state_dict()
    require(isinstance(state, dict) and state.keys() == expected.keys(), "Checkpoint tensor names differ")
    for name, value in state.items():
        require(isinstance(value, torch.Tensor) and value.layout == torch.strided
                and value.shape == expected[name].shape and value.dtype == expected[name].dtype
                and torch.isfinite(value).all().item(), f"Invalid checkpoint tensor: {name}")
    for name, value in model.named_buffers():
        require(torch.equal(state[name], value), f"Checkpoint map differs: {name}")
    model.load_state_dict(state, strict=True)
    model.eval()
    return model