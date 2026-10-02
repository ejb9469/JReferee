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

The server still binds to `127.0.0.1`, validates Host/origin/per-start token,
bounds request bodies and search sizes, and uses one generation semaphore.
It loads TRAINING only, requests empty histories, and neither writes the
database nor executes orders against the edited position. Opening-browser
assets, navigation, singleton filtering, and maps are not replaced.

### Coordination validation and remaining work

Actual commands run for this change from the repository root (existing
annotations jar downloaded to `/tmp`; no new project dependencies):

```sh
JDK=/usr/lib/jvm/temurin-25-jdk-amd64/bin
ANNOTATIONS_JAR=/tmp/jreferee-deps/annotations-26.0.2.jar
"$JDK/javac" --release 25 -cp "$ANNOTATIONS_JAR" -d /tmp/jreferee-classes \
  $(find /home/runner/work/JReferee/JReferee/src -name '*.java' \
      ! -name 'StrategyBrowserApp.java')
CP="/tmp/jreferee-classes:$ANNOTATIONS_JAR"
for check in CoordinatedOrdersSelfCheck JointOrdersSelfCheck \
  OpponentScenariosSelfCheck OutcomeEvaluatorSelfCheck MovementStrategySelfCheck \
  ReferenceFilteringSelfCheck YearPooledSelectionSelfCheck FourPolicyEvaluationSelfCheck
do
  "$JDK/java" -cp "$CP" "testing.$check" || exit 1
done
"$JDK/java" -cp "$CP" _app.MovementStrategyDemoApp
"$JDK/java" -cp "$CP" _app.TestCaseManager
```

All listed checks passed. DATC: 132/132 cases, 677/677 orders; phase checks:
34/34 cases, 163/163 checks; parser fixtures: 2/2, 96/96 checks. Synthetic
regressions cover the exact NTH/Yor mismatch, connected/disconnected routes,
assigned incompatible fleets, adjacent moves, stationary support, explicit
coasts, later assignments, cutoff/beam/foreign-budget exhaustion, conflicting
foreign assumptions, stable insertion/UUID behavior, immutable inputs, legacy
generation, and strategy-to-ranking orchestration. CodeQL reported zero Java
and JavaScript alerts. The automated review executable was unavailable; a
separate read-only review found three defects, now fixed and re-reviewed.

Backend-only compilation was run. Browser application compilation, the added
corpus-free `testing.StrategyBrowserSelfCheck` JSON adapter check, and live UI
checks were **not run**. After full compilation using the commands below,
run that check and manually verify strict abstention, conditional dependency
display, cancellation/invalidation during a pending request, switching between
generated and historical overlays, and unchanged opening-browser navigation.
The ignored local SQLite database is absent, so corpus recall experiments and
database-backed browser startup remain unverified. Reserved TEST games were
not used. Exact submitted-order recall is separate from coherence and tactical
strength; no imitation, later-game, or strength improvements are claimed.

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