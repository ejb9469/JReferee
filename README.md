# JReferee v1.5

A DATC-compliant* Diplomacy adjudicator, written in base Java (natively: OpenJDK 25).

---

At present, the program's "core" (i.e. its grandfather entry point) reads the [DATC test cases](https://petermc.net/diplomacy/datc_v3_3.html) from disk (or, alternatively, games from external platforms), and compares them to  the adjudicator's results. 

These test cases include:
* Illegal orders & invalid orders
* Basic movement - i.e. 'moves, holds, supports, & convoys'
* Advanced tactics - e.g. cyclical movement, head-to-head battles, beleaguered garrisons, convoy swaps, etc.
* "Simple" convoy paradoxes
* Multi-layer "complex" convoy paradoxes
* Butterfly effect & "broken" paradoxes
* Retreats & Adjustments

\*Several DATC cases are incompatible / adjusted with the program - see: *Limitations and Idiosyncrasies* below, and <u>`misc/testcase_alterations.txt`</u>.
<br><br>The DATC allows an adjudicator to be called compliant when every test passes or deviations are made consciously. (<a href=https://petermc.net/diplomacy/datc_v3_3.html>petermc.net</a>)

---

## Limitations & Idiosyncrasies

1. *JReferee* allows convoy kidnapping.

It is fun!
Some adjudicators (like <a href="https://www.backstabbr.com/">Backstabbr</a>) allow it, and some do not.

2. *JReferee* does not natively support a `via convoy` flag.

The existence of a convoy operation is an implied result of the convoying fleet 'succeeding', the move succeeding, and the move itself existing.

---

## Notable Classes & Infrastructure

- ### `src._app.*`
  - `TestCaseManager` — loads and runs DATC test cases
  - `DBNGameCatalogApp` — compares game(s) hosted on <u>[diplobn.com](https://diplobn.com/)</u> to local adjudication results
  - `DBNChecker` — compares a single game to local adjudication results, in detail
  - `DBNGameScraper` — populates the games database with games scraped from diplobn
  - `BoardViewerApp` — entry point for WIP graphics interface
  - `OpeningBrowserApp` — entry point for graphical openings catalog 

- ### `src.adjudication.*`
  - `Adjudicator` — *interface of* deterministic ('rules-based') orders resolvers; '*Adjudicators*'
    - `Judge` — resolves an *ordered set* of orders
    - `Justice` *(ext. Judge)* — runs multiple shuffled adjudications and selects a result when raw results differ
    - `SzykmanReferee` *(ext. Justice)* — applies Szykman convoy-paradox rules where conflicting convoy outcomes require, 
        & compares result with result of a Jury if necessary
  - `Resolver`  *interface of* non-deterministic ('assumptions-based') orders resolvers \[i.e. no *adjudicate(...)*\]
    - `Jury` — determines whether a potential convoy-paradox has a complete "ordinary resolution", without guesses

- ### `src.phase.*`
  - `Processor<PhaseInput, PhaseResult>` — *interface of* Retreats, Adjustments, and no-adjudication movement orders
    - `movement.MovementProcessor`
    - `retreats.RetreatProcessor`
    - `adjustments.AdjustmentProcessor`

- ### `src.game.*`
  - `Game` — primary orchestrator / internal representation of a Dip game: stores board & time information, board state, and related *Processor*s
  - `BoardState` — record (immutable) of a Board State - i.e. unit locations & supply-center ownership
  - `record` package: `GameRecord` and `GameRecordBuilder` — record & record-builder for Games
  - `press` package: `PressMessage` and `PressLedger` for basic storage

- ### `src.ui.*`
  - `BoardViewerServer` — loopback-only HTTP endpoint at `GET /api/board` for current `Game` state
  - `BoardSnapshotMapper` / `BoardSnapshotJsonWriter` — dependency-free DTO mapping and JSON serialization for viewer clients
  - `OpeningBrowserApp` — loopback-only HTTP endpoint for a basic openings browser

---

## Tests

Option A. Run `TestCaseManager`.

The test cases are read from:
* SUITE 1 (Raw adjudication): \[\[`src/resources/testgames/*` & `/src/resources/testgames_solutions/*`\]\]
* SUITE 2 (Board state, Retreats & adjustments): \[\[`src/resources/testgames_phase/*`\]\]

The program prints each test result and gives a final DATC-compliance score.

b. Option B. Run `DiploBNAdjudicationComparator` and supply a [diplobn.com](https://diplobn.com/) game link to test against.

---

## Board viewer API integration (experimental!)

`BoardViewerServer` is reusable around any already-constructed `game.Game`:

```java
Game game = ...; // caller-owned game instance
try (BoardViewerServer server = new BoardViewerServer(game, 0)) { // 0 = ephemeral port
    server.start();
    int port = server.address().getPort();
    // GET http://127.0.0.1:<port>/api/board
}
```

`GET /api/board` returns JSON like:

```json
{
  "year": 1901,
  "phase": "SPRING_MOVEMENT",
  "units": [
    { "nation": "ENGLAND", "type": "FLEET", "province": "Edi" }
  ],
  "supplyCenterOwners": {
    "Edi": "ENGLAND"
  }
}
```

Notes:
- Unit locations preserve exact provinces (including split coasts like `StpNC`).
- Supply-center ownership keys are canonicalized (for example `Stp`, `Spa`, `Bul`).
- This change does not require static asset hosting; callers can serve existing `resources/ui/...` assets separately if desired.

---

## Experimental movement strategy

The route-based movement strategy is an opt-in API. It composes the existing
`RoutePreferences` model, one named `PredictionPolicy`, complete national plans,
complete opponent scenarios, and the existing `OutcomeEvaluator`. Callers
supply the objectives, evaluator/scoring parameters, movement processor, and
search limits. A returned ranking is only a ranking under those supplied
settings; it is not a claim of tactical strength or a calibrated probability.
The recommendation path does not resolve orders against or mutate the caller's
board, game, histories, or trained model.

`analysis.MovementStrategy.recommend(...)` accepts Spring or Fall movement only.
It throws `IllegalArgumentException` for retreat/adjustment phases and history
entries for unknown or inactive units. Omitted histories mean position-only
evidence is requested; supplied histories must be past-only. A nation with no
active units, missing own/opponent evidence, or own predictions emptied by
filtering returns an explicit abstention status with diagnostics, never
synthetic holds. With no opponents, the scenario generator supplies one
complete empty-order scenario. The four explicit policies are `ORIGINAL`,
`REFERENCE_FILTERED`, `YEAR_POOLED_SELECTION`, and
`YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED`. Filtering runs after selection
and before candidate truncation; no threshold retry or full legality check is
performed.

The synthetic integration demonstration does not require a route corpus:

```sh
java -cp "$CP" _app.MovementStrategyDemoApp
```

### Coordinated own-country recommendations

Independent frequency-ranked orders can disagree: for example, `F NTH CONVOY
Yor -> Bel` paired with `A Yor MOVE Nwy`. Adjudication and outcome scoring do
not repair such a candidate. Coordination is therefore checked during own-plan
beam expansion, before incompatible partial plans consume beam slots.

Pass a sixth `MovementStrategy.Configuration` argument:

```java
new MovementStrategy.Configuration(
        PredictionPolicy.REFERENCE_FILTERED, 3, 4, 8, 32,
        CoordinationMode.STRICT)
```

The five-argument constructor still selects `RAW`. `JointOrders.generate`,
opponent distributions, historical experiments, prediction-policy defaults,
observation denominators, and evaluator weights are unchanged.

* `RAW`: historical independent combinations, including incoherent submissions.
* `STRICT`: friendly support/convoy references must agree with the selected
  orders; required convoy routes must be self-contained.
* `CONDITIONAL`: permits foreign cooperation, but reports required foreign
  moves, stationary orders, and matching convoy orders per plan signature.
  These assumptions must admit a consistent simultaneous assignment; one fleet
  cannot promise two different convoys. Ranking still uses explicit opponent
  scenarios, which need not fulfill the assumptions.

Hold support accepts stationary support/convoy orders as well as HOLD, not
MOVE. Reference lookup recognizes canonical territories, but support matching
preserves the phase engine's **exact encoded origins and destinations**:
`Spa`, `SpaNC`, and `SpaSC` are not silently substituted. The limited
`ReferenceCompatibility` filter is unchanged. Geography checks reuse
`Orders.orderIsValid` and `Province` adjacency, including the Judge's coastal
fleet path rule. Connected multi-fleet convoy reachability uses the engine's
matching endpoints and sea-fleet adjacency; own fleets must have matching
convoy choices, not merely occupy seas. This establishes a **potential route**,
not survival against disruption. Adjacent army moves do not require convoys;
there is no invented via-convoy flag or change to convoy kidnapping.
Self-bounces and tactical sacrifices are not blanket-filtered.

Domains still retain only the top `choicesPerUnit` **observed** orders. No
holds, modified targets, counts, or extra evidence are synthesized. Forward
checks keep later-assigned units available optimistically; complete plans are
checked again. Beam search can still miss a coherent plan. Conditional foreign
assignment search is separately bounded to 4096 nodes per complete-plan check.
`Recommendation.coordination()` reports the mode, expanded/rejected candidates,
omitted choices, beam truncation, foreign-assignment nodes/truncation, rejection
reasons, and signature-keyed dependencies. `OWN_COORDINATION_REJECTED` means
all supplied own domains were exhausted under the requested mode;
`OWN_COORDINATION_LIMIT_REACHED` means **no coherent plan within current
limits**, not proof that no such plan exists. Missing evidence and all-filtered
predictions retain their separate abstentions. No fallback incoherent plan is
ranked.

### Strategy browser

`_app.StrategyBrowserApp [database-path]` uses the existing editable strategy
page and explicitly defaults to `STRICT`; RAW and CONDITIONAL remain selectable.
JSON and UI show coordination rejection reasons, search limits, and plan-specific
foreign dependencies. Cancellation/invalidation clears generated results and
ignores stale responses. Generated overlays and imported historical submission
overlays remain separate. Imports are comparison/position input only, never
current-turn evidence or additions to training.

All active units remain fully visible; an outline emphasizes the advised
country. The map retains its last validated position while generation is
pending, cancelled, or invalidated. Edited position input is explicitly labelled
unvalidated until a successful response; imported source snapshots are displayed
immediately and labelled as source data. Ownership tinting uses only recorded
`supplyCenterOwners`, including vacant centers: pale territories, saturated center
markers, and restoration of the SVG's original inline fill/priority on removal or
clear. Spring occupation is not capture, and Fall movement with unresolved
retreats is not final ownership. No capture history is inferred.

Selecting a plan (including keyboard selection) opens a persistent inspector.
It freezes result-time settings and source metadata, shows explicit evaluated
opponent orders, and reconciles objective/center-position/dislodgement components
with equally weighted scenario scores, mean, worst, and
`baseCombined=(1-caution)*mean+caution*worst`. The recommendation score is
`adjusted=baseCombined-tacticalBiasWeight*offendingMoveCount`; the inspector
and list show both scores and the separate penalty. `logPreference` remains a
historical frequency tiebreaker, not a probability of success. Empty objectives are shown
as empty rather than replaced with hidden goals.

Conditional dependencies are immutable, signature-keyed alternative assumption
sets: alternatives are **OR**, requirements within each set are **AND**. Exact
origins, split coasts, endpoints, affected friendly orders, and whole-conjunction
scenario coverage are inspectable in a separate dashed owner-color map layer.
Coverage is not probability or a confirmed commitment. Conditional ranking can
include scenarios that do not satisfy any reported set. RAW means unchecked,
not dependency-free; STRICT means self-contained, not guaranteed success.

### Focused friendly support/convoy soft bias

The browser now explicitly opts into a **strong, untuned heuristic**: default
`tacticalBiasWeight=5` outcome-score points per distinct incoming MOVE, compared
with the default center weight of 1. This implements the requested preference
against moving into a different friendly unit's occupied province when its
**selected own order** is SUPPORT (hold or move support) or CONVOY, rather than
the earlier warning-only policy. Current board locations are compared as canonical
territories; exact coasts remain intact for order legality and support matching.
Supporting or convoying a foreign unit does not exempt the incoming friendly move.

The incoming MOVE is penalized once, not the stationary support/convoy instruction.
Ordinary holds, friendly units moving away, empty destinations, attacks on foreign
supporters/convoy fleets, and legitimate support orders are outside this pattern.
Findings are explanations, not legality rules: no assertion that the move cuts own
support, self-dislodges, or automatically disrupts a convoy. A flagged-only candidate
set remains available, and sufficiently better adjudicated outcome scores can
outweigh the finite penalty. Self-bounces and tactical sacrifices are not banned.

Weight **0** disables the bias exactly. Legacy API constructors and experiment
configurations default to 0; imported historical submissions and RAW historical
generation remain unpenalized. Coordination mode is a separate hard-consistency
setting, not a switch for this heuristic. Explicit nonzero evaluator configuration
can still rank RAW candidates by adjusted score, without changing RAW generation.
`MovementStrategy` requires matching configuration and evaluator bias weights,
rejecting inconsistent search/ranking settings rather than silently mixing policies.
The browser's inspector freezes the result-time weight and explains base versus
adjusted ranks; changing the form invalidates prior recommendations.

For **any positive weight**, coordinated beam search prefers fewer known selected collisions
before truncation, then uses the unchanged historical log preference and stable
signature. This is a search-order heuristic, **not** subtraction of outcome points
from log probabilities. Related own units are expanded as deterministic dependency
groups, with potential stationary collision targets assigned first, so the
relevant selections can be assessed before the requested beam cutoff.
Intermediate groups retain at most `max(256, beamWidth)`
partials; earlier internal pruning is still possible and reported as beam truncation.
Unassigned orders are not violations. The bounded search still uses observed
retained domains only: no invented Edinburgh–NWG choice,
synthesized hold, fabricated frequency, or widened evidence. Cutoffs and pruning
remain visible; this is not a global-optimum or tactical-strength claim.

**Compare observed alternatives** is opt-in and defaults off. Only a suggested
support-hold actually present in selected route evidence can be compared; missing,
invalid, or budget-limited alternatives are explicitly unevaluated. A replacement
must be structurally/geographically coordinated, retain real observation counts
and provenance, and be evaluated against the identical explicit opponent scenarios
and scoring settings, including the same tactical-bias weight. Raw scenario,
mean, worst, and base combined deltas are reported separately from the adjusted
recommendation delta and penalty change. Removing a penalty can improve heuristic
preference without improving an adjudicated outcome. The request has separate fixed caps of eight comparisons
and 128 paired scenario adjudications; it does not widen the frequency-ranked
search or rewrite recommendations. Better/equal/worse deltas are conditional on
these scenarios, never a claim of universal superiority.

The server still binds to `127.0.0.1`, validates Host/origin/per-start token,
bounds request bodies and search sizes, and uses one generation semaphore.
It loads TRAINING only, requests empty histories, and neither writes the
database nor executes orders against the edited position. Opening-browser
assets, navigation, singleton filtering, and maps are not replaced.

### Soft-bias validation and limitations

All Java sources, including `StrategyBrowserApp`, compiled with
`/usr/lib/jvm/temurin-25-jdk-amd64/bin/javac --release 25` and the existing
`/usr/share/gradle-9.8.0/lib/annotations-24.0.1.jar`. No project dependency was
added. Java 25 ran these corpus-free main-based checks:

- `TacticalBiasSelfCheck`, `CoordinatedOrdersSelfCheck`, `MovementStrategySelfCheck`,
  `TacticalPrinciplesSelfCheck`, `BackendDiagnosticsSelfCheck`
- `JointOrdersSelfCheck`, `OpponentScenariosSelfCheck`, `OutcomeEvaluatorSelfCheck`
- `StrategyBrowserSelfCheck`, `StrategyBrowserHttpSelfCheck`
- `ReferenceFilteringSelfCheck`, `YearPooledSelectionSelfCheck`,
  `FourPolicyEvaluationSelfCheck`, `RoutePreferencesSelfCheck`, `YearPoolingSelfCheck`,
  `OccupancyRelaxationSelfCheck`, `CandidateSelectionAuditSelfCheck`,
  `StageCoverageSelfCheck`, `HeldOutEvaluationSelfCheck`, `ScenarioCoverageSelfCheck`,
  `ScenarioWidthComparisonSelfCheck`

All passed. `_app.TestCaseManager` also passed DATC (132/132 cases, 677/677 orders),
phase checks (34/34 cases, 163/163 checks), and parser fixtures (2/2 cases, 96/96 checks).
`node --input-type=module --check` validated the strategy module.

Actual Chromium DOM/SVG checks used the committed synthetic fixtures and a mocked
API, through Node's native WebSocket CDP client because Playwright's transport was
unavailable. Checks covered default and zero weights, list/inspector agreement,
support/convoy flags, raw/adjusted comparisons, safe warning text, all countries'
units and exact coasts, ownership tint, plan/scenario/dependency switching,
reset/invalidation/import/history, a delayed cancelled response, 390px mobile
layout, and original openings navigation/singleton filtering. These are renderer
checks, not a real-corpus end-to-end test. Compilation/browser scratch files were
removed; no private corpus, reserved TEST records, or extra training was used.

The bias does not solve missing evidence, choices omitted before the beam, large
dependency groups pruned at their internal bound, opponent-model uncertainty, or
multi-turn strategy. It implements the requested preference, not a calibrated
measure or a tactical-strength improvement.

### Previous warning-only browser-improvement validation (PR #8)

The following is the validation record for PR #8, not a record of the later
soft-bias implementation. Commands run from the repository root (existing
annotations jar downloaded to `/tmp`; no new project dependencies):

```sh
JDK=/usr/lib/jvm/temurin-25-jdk-amd64/bin
ANNOTATIONS_JAR=/tmp/jreferee-deps/annotations-26.0.2.jar
"$JDK/javac" --release 25 -cp "$ANNOTATIONS_JAR" -d /tmp/jreferee-classes \
  $(find /home/runner/work/JReferee/JReferee/src -name '*.java')
CP="/tmp/jreferee-classes:/home/runner/work/JReferee/JReferee/src:$ANNOTATIONS_JAR"
for check in CoordinatedOrdersSelfCheck JointOrdersSelfCheck \
  OpponentScenariosSelfCheck OutcomeEvaluatorSelfCheck MovementStrategySelfCheck \
  StrategyBrowserSelfCheck ReferenceFilteringSelfCheck \
  YearPooledSelectionSelfCheck FourPolicyEvaluationSelfCheck \
  BackendDiagnosticsSelfCheck TacticalPrinciplesSelfCheck StrategyBrowserHttpSelfCheck \
  RoutePreferencesSelfCheck YearPoolingSelfCheck OccupancyRelaxationSelfCheck \
  CandidateSelectionAuditSelfCheck StageCoverageSelfCheck HeldOutEvaluationSelfCheck \
  ScenarioCoverageSelfCheck ScenarioWidthComparisonSelfCheck
do
  "$JDK/java" -cp "$CP" "testing.$check" || exit 1
done
"$JDK/java" -cp "$CP" _app.TestCaseManager
```

All listed checks passed. DATC: 132/132 cases, 677/677 orders; phase checks:
34/34 cases, 163/163 checks; parser fixtures: 2/2, 96/96 checks. Synthetic
regressions cover the exact NTH/Yor mismatch, connected/disconnected routes,
assigned incompatible fleets, adjacent moves, stationary support, explicit
coasts, later assignments, cutoff/beam/foreign-budget exhaustion, conflicting
foreign assumptions, stable insertion/UUID behavior, immutable inputs, legacy
generation, and strategy-to-ranking orchestration.

For this browser-improvements PR, final full compilation (including
`StrategyBrowserApp`) and all 20 listed corpus-free self-checks passed, together
with the DATC, phase, and parser totals above. Compilation emitted only existing
deprecation notes. `RouteCorpusSelfCheck` was not run because the SQLite JDBC jar
was unavailable; no new dependency or database was added. The updated adapter's fixtures also exercise
immutable settings, explicit scenario/component JSON, warning-only findings,
evidence-backed comparison provenance, and opt-in validation.
`StrategyBrowserHttpSelfCheck` exercises the real handler without a corpus:
Host/origin/token rejection, method/content-type checks, unknown/duplicate fields,
32 KiB body bounds, the generation semaphore, security headers, opt-in parsing,
and abstention retaining foreign units and a vacant recorded center.

Real headless Chromium exercised the DOM and SVG against the committed synthetic
JSON fixtures under `src/resources/ui/fixtures/`. Playwright's transport was
unavailable; the browser check instead used the installed Chromium and Node's
native WebSocket CDP client, with an inline fixture HTTP server and no new
dependencies or helper files:

```sh
node --input-type=module
# Inline fixture harness launched the existing browser with:
chromium --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
  --remote-debugging-port=9224 --user-data-dir=<temporary-profile>
```

The CDP harness used `Runtime.evaluate`, `DOM.setFileInputFiles`,
`Emulation.setDeviceMetricsOverride`, and `Page.captureScreenshot`. Assertions
covered all 34 center resets/markers and original `!important` priority, unchanged
SVG geometry, split coasts, foreign visibility, import/historical switching,
retained boards and cancelled/stale responses, accessible scenario/requirement
switching, safe `textContent`, frozen source/settings, mobile layout, and the
opening-browser search/navigation/single-entry filter. Screenshots were captured
and kept outside tracked files. These are real renderer tests with **mocked API
results**, not a database-backed browser end-to-end or tactical-strength test.
Changed files passed secret scanning before each commit. A separate read-only
review reported no significant issues. Earlier CodeQL runs reported zero alerts,
but a later Java scan timed out; the final PR-wide automated review/security
request could not run because the validation time budget was exhausted. Final
automated security validation therefore remains outstanding, not a clean result.
The ignored local SQLite database is absent, so corpus recall experiments and
database-backed browser startup remain unverified. Reserved TEST games were
not used. Exact submitted-order recall is separate from coherence and tactical
strength; no imitation, later-game, or strength improvements are claimed.

Compatibility/UI review checklist for these improvements:

- [x] Legacy constructors, five-argument RAW configuration, numerical score
  accumulation, frequency tiebreakers, and intentional self-bounces retained.
- [x] Recorded vacant-center ownership and exact unit coasts remain separate;
  SVG geometry and baseline fill priorities are preserved.
- [x] All countries' units remain visible; clearing recommendations retains the
  loaded board and labels unvalidated position edits.
- [x] Plan/scenario/assumption switching uses accessible controls and clears old
  dependency layers; historical overlays remain separately labelled.
- [x] Imports never send historical orders or source labels to generation,
  mutate training, or write the corpus database.
- [x] Opening-browser country/search/navigation and **Hide single-entry
  openings** remain available.
- [x] Warning metadata never rejects orders. The separate opt-in tactical bias
  adjusts recommendation scores; comparison requires explicit opt-in, observed
  evidence, coordinated geometry, and fixed paired-work limits.
- [ ] Database-backed startup and real-corpus experiments: unavailable because
  the ignored local corpus is absent. Synthetic results are not strength evidence.
- [ ] Final PR-wide automated review/security scan: unavailable due to exhausted
  validation time budget.

### Four-policy experiment

`_app.FourPolicyExperimentApp` explicitly runs the paired factorial
comparison on TRAINING and VALIDATION partitions only:

| Policy | Selection and filtering |
| --- | --- |
| A `ORIGINAL` | Existing exact, suffix, position, and enabled fallback behavior |
| B `REFERENCE_FILTERED` | A, then filter incompatible support/convoy references |
| C `YEAR_POOLED_SELECTION` | Independently qualifying year-pooled override |
| D `YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED` | C, then filter |

The report contains structured immutable overall and year-specific reports,
paired gains and losses for all six policy pairs, label exclusions, evidence
and candidate-removal audits, and scenario counts. A paired net equals gains
minus losses; policies may improve one stage and lose another. D's filtering
audit compares its selected distribution against C before filtering. These
are exact-submission imitation funnels and are not evidence that any variant
is the best production policy.

The experiment fixes minimum observations at 3, maximum suffix at 4, per-unit
choices at 4, national plans at 8, and opponent scenarios at 32. It trains one
model, evaluates the same eligible submissions for all four policies, and
never supplies reserved TEST records. Do not tune on TEST.

This repository has no Maven or Gradle build. With JDK 25 and the existing
JetBrains annotations and SQLite JDBC jars available, set the jar paths and run
the following from the repository root:

```sh
export ANNOTATIONS_JAR=/path/to/annotations-26.0.2.jar
export SQLITE_JAR=/path/to/sqlite-jdbc-3.53.4.0.jar
mkdir -p out
javac --release 25 -cp "$ANNOTATIONS_JAR" -d out $(find src -name '*.java')
export CP="out:$ANNOTATIONS_JAR:$SQLITE_JAR"
java -cp "$CP" _app.MovementStrategyDemoApp
java -cp "$CP" _app.FourPolicyExperimentApp /absolute/path/to/diplobn-catalog.sqlite
```

For focused route, policy, scenario, and strategy regressions, run:

```sh
for check in \
  RoutePreferencesSelfCheck YearPoolingSelfCheck OccupancyRelaxationSelfCheck \
  CandidateSelectionAuditSelfCheck StageCoverageSelfCheck \
  YearPooledSelectionSelfCheck ReferenceFilteringSelfCheck \
  JointOrdersSelfCheck OpponentScenariosSelfCheck OutcomeEvaluatorSelfCheck \
  HeldOutEvaluationSelfCheck RouteCorpusSelfCheck ScenarioCoverageSelfCheck \
  ScenarioWidthComparisonSelfCheck FourPolicyEvaluationSelfCheck \
  MovementStrategySelfCheck
do
  java -cp "$CP" "testing.$check" || exit 1
done
java -cp "$CP" _app.TestCaseManager
```

`RouteCorpusSelfCheck` requires SQLite JDBC. `TestCaseManager` runs the
repository's DATC, phase, and parser checks; existing known DATC limitations
remain unchanged. Before interpreting corpus output, verify that the corpus
partition and replay compatibility report is valid and that TEST remains
reserved. Compare all four policies on the same TRAINING/VALIDATION inputs;
report gains, losses, and denominators together. Treat a change as a candidate
for further study only if paired outcomes, coverage losses, year-specific
variation, and data limitations are understood. Neither an imitation gain nor
these validation records establish tactical strength or justify selecting a
production winner.

No corpus experiment was run for this change: the local SQLite corpus and JDBC
runtime were unavailable. Therefore, no new corpus measurements are claimed;
combined-policy results remain pending. Previously supplied corpus figures
are historical user-run results, not reproduced or extended here. The openings
browser and its collapsed historical-results section remain unchanged.