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

## Tactical analysis

The catalogue defines **101 `TacticKind`s**. Current implementations cover
**36 structural/static kinds** in `DetectiveAgency` and
**1 resolved-event kind** in `OutcomeAgency`: `SUPPORTER_DISLODGEMENT`.
The remaining **64 kinds** require implementation, additional evidence, or
clarified semantics. See
[implementation status and prerequisites](docs/detective-implementation-status.md)
for the complete inventory.

### Detection, outcomes, and assessment

- `TacticInvestigator<C, F>` is the shared typed investigation contract.
- `analysis.tactics.Detective` is the structural base class; concrete
  implementations live in `analysis.tactics.detective`.
- `OutcomeDetective` consumes an identified `ResolvedMovementContext`;
  it does not adjudicate independently.
- `SupportToMoveAssessor` and `SupportToHoldAssessor` evaluate baseline support
  claims. `SupportAssessmentBatch` shares one resolved result across findings.
- `SELF_BOUNCE` and `BELEAGUERED_GARRISON` have assessment claim contracts,
  but no concrete assessors.

Structural findings describe submissions, not successful tactics, agreements,
or strategic value. Unknown instructions are not implicit HOLDs. A complete
local pattern may still belong to an incomplete scenario.

Local assessments distinguish accepted support, successful movement, and
recipient survival. Accepted support is not necessarily decisive; rejected
support does not by itself establish a cut. DBN outcome annotations remain
separate from local adjudication.

`TacticDefinitionRegistry` records each kind's semantics, evidence requirements,
and definition version. Definition and investigator versions are independent.
Catalogue entries do not imply implementation availability.

### Reports and browser

Each agency reports its own implementation, applicability, finding counts, and
missing evidence through `InvestigationCoverage`. Filtering preserves the
original counts. Unsupported, skipped, and evaluated-negative categories are
distinct; unsupported counts from different agencies must not be added.

The browser API keeps structural findings at the investigation root, resolved
events under `resolvedEvents`, and support claims under
`localSupportAssessments`. The current UI displays structural findings and
support assessments; resolved-event rendering is not yet implemented.

To enable local resolution, add this **VM option** to `GameBrowserApp`:

```text
-Djreferee.assessment.engineRevision=YOUR_BUILD_ID
```

Use the actual adjudicator build identifier. The browser resolves complete
movement snapshots with `SZYKMAN_JUSTICE`, 32 trials, and seed 0, sharing each
result between outcome investigation and support assessment. Unknown
instructions block resolution; structural findings remain available.
Retreat and adjustment snapshots are not investigated.

Detective checkboxes select multiple kinds using OR, combined with country and
completeness filters using AND. All kinds start selected; clearing selects none.
Filtering does not rerun analysis or change whole-snapshot coverage.
New snapshots reset selections.

### Checks

Compile with OpenJDK 25, then run the standalone checks without `-ea`:

```sh
java -cp "$CP" testing.selfcheck.DefaultDetectivesSelfCheck
java -cp "$CP" testing.selfcheck.StructuralSupportMovementSelfCheck
java -cp "$CP" testing.selfcheck.ConvoyConstructionSelfCheck
java -cp "$CP" testing.selfcheck.RegisteredDetectivesSelfCheck
java -cp "$CP" testing.selfcheck.SupportAssessmentSelfCheck
java -cp "$CP" testing.selfcheck.OutcomeDetectiveSelfCheck
java -cp "$CP" testing.selfcheck.GameInvestigationIntegrationSelfCheck
node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
```

`CP` includes compiled classes and required dependencies; the integration check
requires SQLite JDBC. The implementation inventory lists the broader regression
suite and distinguishes historical test results from current verification.
Bounded tests do not prove universally zero false positives or false negatives.
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