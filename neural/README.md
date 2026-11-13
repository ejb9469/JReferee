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

## Java export

Export the selected checkpoint without retraining or regenerating the cache:

```powershell
.venv\Scripts\python.exe -m neural.test_export --spec data/movement-neural-cache-v1/specification.json
.venv\Scripts\python.exe -m neural.export --checkpoint data/movement-gru-v1/policy.pt --output data/movement-gru-java-v1 --cache data/movement-neural-cache-v1 --examples 32
```

The output directory must be new and its parent must exist. Export runs on CPU
with one thread. It verifies the checkpoint's architecture, tensors and exact
specification. With `--cache`, it verifies only VALIDATION payloads and requires
the original cache-manifest and dataset-index hashes from training. TRAIN and
TEST payloads remain unopened. No training or evaluation metrics are computed.

Omitting `--cache` exports only built-in parity cases. With the cache, up to
`--examples` evenly spaced validation records are added (default 32, range
1..256). Built-in cases cover all powers, coast choices, support/convoy fields,
different prefixes, zero units, singleton masks and an 18-unit position.
Each case records observed/default, last-option, greedy and seeded-sample paths,
deduplicated when they coincide. Every conditional distribution along each
path is retained. Player identities and source metadata are excluded.

The directory contains:

| File | Contents |
|---|---|
| `model.bin` | Frozen float32 parameters and model/input identities |
| `parity.bin` | Numeric positions, paths and Python conditional log probabilities |
| `export.json` | Completion receipt, original checkpoint hash, counts and file hashes |

`export.json` is written last. A failed export leaves incomplete artifacts for
inspection; use a new directory after correcting the failure. Keep the original
`policy.pt`, training report, cache and frozen linear `policy.bin`.
The receipt's `checkpoint_sha256` must equal the selected checkpoint hash in
`training.json`.

The binary schema is `jreferee-gru-java-v1`. All multibyte values are big-endian;
the numeric cache remains little-endian. Both binaries end with a 32-byte
SHA-256 of their preceding bytes. Receipt file hashes cover the entire files,
including those trailers. Java rejects files larger than 64 MiB, incompatible
specifications, wrong tensor names/shapes, nonfinite weights and trailing data.

## Java inference and parity

Rebuild JReferee, then run `testing.selfcheck.GruPolicySelfCheck` with the
repository root as working directory, `-Xmx2G -Xms1G` as VM option(s) and this argument:

```text
data/movement-gru-java-v1
```

The self-check confirms numeric-encoder agreement, every exported conditional,
complete-action scores, separated greedy maxima, arbitrary prefix calls,
seeded Java sampling, restricted masks, live catalog legality and malformed
model rejection. It also reports preparation/greedy and prepared sample/score
timings using the exported position with the most options. These are local
warm-up measurements, not a standardized benchmark.

Java uses double arithmetic on the exported float32 weights. Conditional
log probabilities must differ by at most `2e-4`; joint scores use that tolerance
times the number of slots. Near-tied greedy choices may differ. Python uses
Gumbel-max sampling and Java uses inverse-CDF sampling, so matching random
seeds do not imply matching sampled actions across languages.

`GruMovementPolicy` implements the existing `MovementPolicy` interface:

```java
var policy = GruMovementPolicy.load(Path.of("data/movement-gru-java-v1/model.bin"));
var encoding = new MovementEncoding(root, power);
var prepared = policy.prepare(encoding.input());
var action = prepared.sample(new Random(7));
var orders = encoding.decode(action);
double logProbability = prepared.logProbability(action);
```

Load the model once. Each prepared position retains its board and option
representations and can be reused for sampling and scoring. Prefix queries
replay the supplied prefix; complete-action methods advance sequentially.
Weights and prepared positions are immutable. Share neither a mutable random
generator nor external game mutations across concurrent calls.

Java inference needs no PyTorch installation or additional Java dependency.
It preserves every supplied option and performs no coordination repair.
This remains a movement policy; retreats and adjustments are unchanged.

## Controlled proposal experiment

Run `_app.PolicyProposalExperimentApp` from the repository root with `-Xmx512m`.
For the opening position, use:

```text
--proposal-model data/movement-linear-v1/policy.bin --anchor-model data/movement-linear-v1/policy.bin --gru-model data/movement-gru-java-v1/model.bin --position opening
```

Repeat with `--position contact`. Keep the existing default budgets and seeds.
Without `--gru-model`, the original five methods run unchanged. With it, `GRU`
and `GRU_HYBRID` are added. The hybrid reserves half its places for neural draws
and half for heuristics; an odd extra place goes to the neural side.

All seven methods use the frozen full-catalog linear anchor, common fresh
opponent draws and unchanged rollout policy. The GRU changes proposals only.
`log_learned_proposal` remains the linear model's action score; the added
`log_gru_proposal` scores every method's candidates under the GRU. Neither
column describes the hybrid's selection probability. Candidate deduplication
and hybrid selection do not redefine the full-policy probabilities.

With full pools and default settings, each method makes 800 search and 896
fresh root-evaluation requests. Short pools are reported explicitly.
Additional paired comparisons cover GRU versus LEARNED, and GRU_HYBRID versus
LEARNED, HYBRID and GRU. These fixture-board estimates are not full-game strength
measurements, and no dataset TEST split is opened.

Send `export.json`, the complete `GruPolicySelfCheck` output, and both experiment
outputs. Keep the model binaries, parity fixtures and dataset shards locally.