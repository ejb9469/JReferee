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

The tactical API lives in `analysis.tactics`. The abstract `Detective` and its
four current implementations live in `analysis.tactics.detective` (this is a
Java package, not a separate module). Callers that previously imported a
detective from `analysis.tactics` must now import it from the `detective`
subpackage; importing `analysis.tactics.*` does not import subpackages.

`DetectiveAgency` currently registers structural investigators for
`SUPPORT_TO_MOVE`, `SUPPORT_TO_HOLD`, `SELF_BOUNCE`, and
`BELEAGUERED_GARRISON`. `TacticDefinitionRegistry` defines the full 100-kind
concept catalogue, including support, movement, convoy, retreat, center,
multi-phase, agreement, and diagnostic concepts. Each kind has an explicit
family, interpretation, evidence requirements, semantic version, and short
meaning. These identifiers are application vocabulary, not official
Diplomacy terminology. Similar-looking variants have distinct definitions;
none is silently aliased to another kind.

Definitions do not imply implementation availability. Only the four kinds
above have built-in structural investigators, and only those kinds currently
have registered adjudicated-claim contracts. The remaining catalogue entries
are definitions for future investigators; they do not produce speculative
matches or fabricated assessment outcomes. Structural order candidates do
not establish legality, effective support, intent, hostility, agreements, or
strategic value. Agreement and multi-phase concepts explicitly require
evidence that the movement-only `TacticalContext` does not carry.

`DetectiveAgency.investigate(context)` retains its list-returning API.
`investigateReport(context)` additionally reports, for every kind, whether an
implementation is registered, whether required evidence makes it applicable,
and whether it found matches, found none, was skipped for missing evidence,
or is unsupported. A complete local match is not a complete scenario or proof
that the entire investigation covered every kind. Filtering an
`InvestigationReport` preserves its original investigation coverage and marks
the result as a filtered view; it does not re-run detectors or turn an
unselected finding into evidence of absence.

The standalone self-check entry points require no `-ea` flag. After compiling
the project with OpenJDK 25, run:

```text
java -cp out testing.selfcheck.DetectiveSelfCheck
java -cp out testing.selfcheck.DetectiveAgencySelfCheck
java -cp out testing.selfcheck.TacticArchitectureSelfCheck
java -cp out testing.selfcheck.InvestigationReportSelfCheck
```

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