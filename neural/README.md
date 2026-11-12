# Supervised movement GRU

Run from the repository root with Python 3.12. Use the existing
`movement-neural-cache-v1` directory; no new game export is needed.
The Java cache specification is described in `src/agents/encoding/neural.txt`.

## Windows

```bat
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r neural\requirements.txt
.venv\Scripts\python -m neural.test_neural --spec data/movement-neural-cache-v1/specification.json
.venv\Scripts\python -m neural.test_verbose --spec data/movement-neural-cache-v1/specification.json
.venv\Scripts\python -m neural.train --cache data/movement-neural-cache-v1 --verify-only -v
.venv\Scripts\python -m neural.train --cache data/movement-neural-cache-v1 --output data/movement-gru-v1 --epochs 3 --hidden 128 --batch-size 32 --max-options 16384 --bucket-window 256 --learning-rate 0.001 --seed 7 --validation-samples 4 --threads 4 -v
```

On Linux/macOS, create the environment with `python3.12 -m venv .venv`
and use `.venv/bin/python`. For a CPU-only Linux installation, install
`torch==2.6.0` from PyTorch's CPU wheel index before installing requirements:

```sh
.venv/bin/python -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
.venv/bin/python -m pip install -r neural/requirements.txt
```

CPU is the default. `--device cuda` requires a compatible PyTorch/CUDA
installation. CPU checks used PyTorch 2.6.0; CUDA has not been validated.
Deterministic kernels are requested. Unsupported deterministic operations fail
explicitly. Results need not be bit-identical across hardware/library versions.

The self-check creates small temporary caches using your specification. It
does not read your game shards. It covers malformed caches, coast options,
batch coverage, causal decoding, joint-order learning, checkpoint selection,
and training with deliberately damaged TEST files.

## Verbose console

Add `-v` or `--verbose` to training or `--verify-only`. It prints stage starts,
progress about every two seconds at example/batch boundaries, and completion
lines. Cache verification, initial validation, each training epoch and each
validation pass have separate counters. Percentages, elapsed time, throughput
and approximate ETA describe the current pass, not the whole job.

Verbose training also reports the running online NLL, model/batch settings,
sampling diagnostics, timing breakdowns and checkpoint decisions. It does not
change random seeds, model settings, data selection or checkpoint selection.
Without `-v`, the original brief output remains.

Color is enabled for supported interactive terminals, including Windows consoles
with virtual-terminal processing. Redirected output is plain text, with one
update per line and no cursor animation. Unsupported consoles also use plain
text. Set `NO_COLOR` to disable color while keeping verbose output; in PowerShell:

```powershell
$env:NO_COLOR = "1"
```

The verbose self-check covers progress throttling, color selection and identical
quiet/verbose model weights and validation metrics.

## Data path

`Cache` verifies TRAIN and VALIDATION sizes, hashes, offsets, categories,
geometry, targets, identities and original game-level splits before training.
It preserves the export filter, including press and unknown labels. TEST
descriptions are checked in the manifest; TEST payloads are never opened.
Metadata is used only for verification and subgroup reporting.

The reader keeps at most two shards mapped. Returned examples own their arrays.
Compact indices require memory proportional to the example count; verification
also temporarily tracks decision identities. Each epoch shuffles shard order
and then examples within each shard. Windows of similar unit/option counts are
batched together. This is a shard-local shuffle, not a uniform global shuffle.

Both example count and actual option count bound a batch. Every example is
visited once per epoch, including incomplete final batches. An example larger
than `--max-options` fails before output creation; increase that bound if needed.
No legal option is dropped or truncated. Smaller bounds reduce memory use but
can increase training time. Epoch reports separate reading, tensor batching,
and model/optimizer time.

## Model

`Batch` packs active slots and their legal options separately at each decoder
step. Segmented softmax normalizes over each slot's actual legal options.
There are no padded options or all-padding slots.

`MovementGRU` embeds fixed board rows and categorical context, then applies two
residual neighbor-aggregation layers over direct Army/Fleet map edges. Potential
convoy edges are excluded from board adjacency. Exact coast embeddings remain
available alongside canonical board features.

Each option embeds its five structured fields and the board features at its
origin, target and auxiliary location. The current GRU state and issuing
province score these options. After choosing an order, its representation
updates the GRU state. Board and option encodings are reused during repeated
sampling of an example.

Training uses teacher forcing and AdamW: each conditional is scored before the
current observed order enters the GRU. The joint action probability is the
product of these conditionals. Earlier support/move/convoy choices can therefore
change later distributions. No outcome features, coordination repairs or
extra rule-based losses are used.

## Reports and artifacts

`training.json` records the dataset/specification hashes, unchanged filter,
settings, versions, initial validation, every trained epoch, timings and the
selected checkpoint hash. The smallest validation order NLL selects among
trained epochs; ties keep the earlier epoch. TEST remains held out.

Order NLL and order accuracy use observed prefixes. Exact-action accuracy
requires every observed-prefix argmax to match, which is also the complete
greedy-action match condition. Free-running samples report observed-action
coverage at K, distinct actions, and own-support/convoy mismatches, overall
and by power/communication label. These counts do not establish complete
convoy routes, tactical success or playing strength. Hold-heavy sampling can
reduce mismatches without improving the policy.

Validation sampling uses a separate, reset RNG so it does not alter training
randomness. Changing batch bounds can change the sample draw sequence.
Use the same settings when comparing models.

`policy.pt` contains CPU weights, architecture settings and the exact input
specification. Loading uses `weights_only=True` and checks tensor/map agreement.
It has no optimizer state and cannot resume interrupted training. The output
directory must be new; failures leave incomplete artifacts for inspection.
`training.json` becomes complete only after the selected checkpoint reloads.

Send `training.json` and the console output. Keep `policy.pt` locally.
This checkpoint cannot yet replace the Java linear `policy.bin`. The next
integration is a Java inference adapter and cross-language conditional-probability
parity, followed by the existing proposal/search experiments.