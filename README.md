# JReferee [v2/v1.6] (2026-11-17)

***JReferee*** is a Java-based *Diplomacy* engine and research toolkit for resolving orders, cataloging and analyzing games, and training and evaluating AI players.

The program's core is a DATC-compliant* Diplomacy adjudicator, written in base* Java (natively: OpenJDK25).


---

## Core Functionality

1. The `_app` package contains a whole buncha' entry points for the project’s individual tools: i.e. adjudication tests, game importing and cataloging, browser interfaces, AI experiments, dataset preparation, and model training/evaluation. These applications assemble and configure components from the other packages to enable custom workflows.


2. The `adjudication` package & grandfather test-runner entry point `_app.`/`TestCaseManager`) reads the [DATC test cases](https://petermc.net/diplomacy/datc_v3_3.html) from disk (or, alternatively, games from external platforms), and compares them to  the adjudicator's results.
   1. `Judge` (the principal `Adjudicator`) - performs a duplex recursive adjudication of a `List<Order>`:
      1. "adjudicate by force" `adjudicate(...)`
      2. ... which calls "adjudicate by deduction" `resolve(...)`,
      3. ... which in turn calls `adjudicate(...)`.
      4. State is highly-mutable.
   2. `Justice` (ext. `Judge`) - tests shuffled iterations of `Judge::orders` for potential order-sensitivity.
      1. Selects a final result where outcomes differ.
   3. `SzykmanJustice` (ext. `Justice`) handles complex convoy paradoxes using the Szykman principle.
      1. Consults `Jury` where appropriate before applying paradox-specific fallback rules and re-adjudicating affected positions.
   4. `Jury` (the principal `Resolver`) - determines which outcomes can be established *without speculation or Szkyman overrides*.
      1. Uses known facts to establish outcomes without guessing or applying paradox overrides.
      2. Returns an immutable `OrdinaryResolution`
      3. `Jury` preserves unresolved dependencies rather than forcing a verdict. Its purpose is as an *advisor* class vis a vis `SzykmanJustice` (and any future implementations), *NOT* as a standalone adjudicator.


3. The `phase` package is responsible for processing movement, retreats, and adjustments. 
   1. The `Processor<PhaseInput, PhaseResult>` implementations accept a starting position and submitted orders, apply the relevant rules, and return a phase result.
   2. `MovementProcessor<>` delegates movement-order resolution to the configured adjudicator.


4. The `game` package maintains the board, calendar, and current phase.
   1. `Game` coordinates the phase processors and advances play
   2. `BoardState` represents unit locations and supply-center ownership.
   3. The `game.record` package preserves resolved phases and game histories; 'snapshots' (see `contracts.Snapshot<>`) allow speculative moves to be explored and rolled back.


5. `domain` defines the shared vocabulary and geography of standard Diplomacy: nations, provinces, coasts, unit types, order types, seasons, and constants.


6. `contracts` defines interfaces shared program-wide:
   1. Including:  `OrderForm` (top-level 'Order' API),
   2. `OrderTranslator` for converting btwn. representations
   3. `Snapshot` for saving and restoring state.
   4. These contracts let different implementations expose consistent APIs.


7. The `parsing` and `io` packages connect external data to the internal game model.
   1. Infrastructure within these packages read DATC fixtures and DiploBN games, translate orders between internal forms (see `contracts.OrderForm` & `contracts.OrderTranslator<OrderForm, OrderForm>`) , manage game catalogs, and support in-memory or *SQLite storage*.
   2. Imported games can be inspected and compared against local adjudication results.
   3. `src.io.training` + Python code in `neural/` support the ML workflow found within the `agents` package.
      1. *Note: `io.training` likely does not belong in `io`, and will likely be relocated in the future.*


8. The `ui` package provides local browser interfaces for board positions:
   1. e.g. opening catalogs, game histories, tactical findings, & AI proposals
   2. Java servers supply structured data to the HTML, CSS, and JavaScript assets in `src/resources/ui`, with `_app` classes serving as launchers for the individual tools.


9. The `agents` package provides the machinery for CPU players
   1. Including: legal-order generation, candidate-plan construction, learned movement policies, search, simulation, and position evaluation.
   2. These components can be combined to run and record complete no-press games, or used independently in controlled experiments.
   3. See '7. Parsing & IO' for note re: coupled package `io.training`
   4. Python machinery resides in `neural/` (above `src`)

---

## Notable Classes & Infrastructure

- ### `_app.*`
    ***Run entry points from the repository root.***
    - `TestCaseManager` — loads and runs DATC test cases & parser/catalog checks, using bundled fixtures
    - `DBNGameCatalogApp` — imports & compares game(s) hosted on <u>[diplobn.com](https://diplobn.com/)</u> to local adjudication results; requires a catalog database or import manifest (network access for imports)
    - `DBNChecker` — compares a single game's movement outcomes to local adjudication results, in detail; requires a game URL & network access
    - `DBNGameScraper` — populates the games database with games scraped from diplobn; requires a GameID range & network access
    - `WebDiplomacyGameScraper` — acquires public, completed Classic games; an explicit database path can point to a shared catalog
    - `DBNTacticsApp` — inspects structural tactical patterns in a single DiploBN game; requires a game URL & network access (does not establish that a tactic succeeded)
    - `BoardViewerApp` — entry point for the graphical board viewer; starts with the standard Spring 1901 position, without a database or trained models
    - `OpeningBrowserApp` — entry point for graphical openings catalog; requires an existing catalog database
    - `GameBrowserApp` — entry point for read-only game-history browsing & tactical inspection; requires an existing catalog database
    - `AgentGameApp` — runs a bounded seven-agent game & opens its recorded history in the browser; model-free by default, no database required. Learned generation requires a linear anchor & exported GRU model
    - `AgentGameConsoleApp` — console counterpart: prints agent decisions, submitted orders, phase results, & final scores; no database, trained models, or browser assets required
    - `ProposalViewerApp` — entry point for graphical AI-proposal inspection & comparison; requires trained linear & exported GRU movement models (separate anchor model optional)
    - `SelfPlayGameApp` — runs & persistently records a bounded no-press game; requires a linear anchor, exported GRU model, run settings, & a new output directory. A value model is optional; `baseline` skips only the value model

- ### `adjudication.*`
    - `Adjudicator` — *interface of* rules-based orders resolvers; updates working orders with adjudicated outcomes
        - `Judge` — resolves a collection of orders by tracing dependencies & applying movement rules
        - `Justice` *(ext. Judge)* — runs shuffled adjudications for potentially order-sensitive components & selects a result when raw results differ
        - `SzykmanJustice` *(ext. Justice)* — applies Szykman convoy-paradox rules where conflicting convoy outcomes require, & consults `Jury` if necessary
    - `Resolver` — *interface of* non-mutating orders analysis; inquiry rather than committing a game result
        - `Jury` — uses known facts to establish order outcomes without guesses or paradox overrides; returns an immutable `OrdinaryResolution` containing confirmed outcomes & unresolved dependencies

- ### `phase.*`
    - `Processor<PhaseInput, PhaseResult>` — *interface of* phase processors: starting position & submitted orders ==> phase result
        - `movement.MovementProcessor` — normalizes movement submissions & delegates resolution to the configured adjudicator
        - `retreats.RetreatProcessor` — processes retreats, collisions, & destruction of units that cannot remain
        - `adjustments.AdjustmentProcessor` — processes builds, waives, disbands, & automatic removals
    - `UnitId` — persistent unit identity; its creation province is not necessarily its current location

- ### `game.*`
    - `Game` — primary orchestrator / internal representation of a Dip game: stores board & time information, invokes related *Processor*s, and advances phases
    - `BoardState` — immutable representation of a Board State - i.e. unit locations & supply-center ownership
    - `GameState` — *interface of* phase-specific position snapshots, including information needed to continue retreats or adjustments
    - `record` package: `GameRecord` and `GameRecordBuilder` — immutable game history & record-builder; archives submitted orders, phase results, and boards before/after resolution
    - `record.GameHistory` — read-only historical positions using `BoardState`, `GameMoment`, and `OrderForm`; adapters preserve source results and mark unavailable data
    - ~~`press` package: `PressMessage` and `PressLedger` for basic storage~~ *DEPRECATED*

- ### `analysis.*`
    - `openings` package: `Catalog` and `Classifier` — catalogs & classifies opening-order combinations
    - `UnitRoutes` — counts observed unit-route continuations, optionally restricted to a particular opening
    - `tactics` package: `Detective` and `DetectiveAgency` — recognizes structural tactical patterns & reports investigation coverage; not proof of success or player intent
    - `tactics.outcome` / `tactics.assessment` packages — investigates resolved movement events & assesses supported claims against adjudication evidence

- ### `agents.*`
    - `generation` package: `LegalOrderGenerator` and `ActionGenerator` — constructs legal order options & complete candidate actions for a nation
    - `policy` package: `MovementPolicy` — *interface of* movement-order probability models; implemented by `LinearMovementPolicy` & `GruMovementPolicy`
    - `search` package: `PolicySearch` — *interface of* action-selection searches; implemented by `BestResponseSearch` & `DiLPiKLSearch`
    - `simulation.Simulator` — resolves joint actions; supports speculative advances followed by restoration of the starting position
    - `evaluation` package: `Evaluator` & related models — position scoring, rollout evaluation, & neural value prediction
    - `execution.GameRunner` — coordinates independent agent decisions, advances the actual game, & records its history

- ### `parsing.*` / `io.*`
    - `parsing.datc` / `parsing.diplobn` — parsers for DATC fixtures & externally sourced DiploBN games
    - `io.catalog.GameCatalog` — manages imported game identities, metadata, source payloads, & comparison results
    - `io.catalog.CatalogStore` — *interface of* catalog storage; implemented by `persistence.InMemoryCatalogStore` & `persistence.SQLiteCatalogStore`
    - `io.training` — dataset readers/exporters, numeric caches, linear-policy training/evaluation, & persistent self-play archives; Python neural training/export lives separately in `neural/`

- ### `domain.*` / `contracts.*`
    - `domain` package — shared map & game vocabulary: nations, provinces, coasts, unit/order types, seasons, & constants
    - `OrderForm` — *interface of* shared read-only order / unit properties
    - `OrderTranslator` — *interface of* conversions between order representations
    - `Snapshot` — *interface of* checkpointing & in-place rollback

- ### `ui.*`
    - `BoardViewerServer` — loopback-only HTTP endpoints at `GET /api/board` & `GET /api/history` for current board state & supplied game history
    - `GameBrowserServer` — read-only browser for DiploBN, webDiplomacy Classic, and internal snapshot archives in one catalog; recorded-game mode also works without a database
    - `ProposalViewerServer` — local browser interface for candidate orders, policy scores, & optional search diagnostics
    - `BoardSnapshotMapper` / `BoardSnapshotJsonWriter` — dependency-free DTO mapping and JSON serialization for viewer clients

- ### `misc.*`
    - `scoring.ScoringSystem` — enum of tournament scoring rules: e.g. Draw-Size, Sum-of-Squares, C-Diplo 100, Open Mind the Gap, & Duct Tape V2; calculates points from final supply-center counts & provides fixed-scale rewards for agent evaluation

---

## Shared game catalog and viewer

Both scrapers accept an optional database path. To populate a shared catalog,
pass the same path (for example `data/games.sqlite`) to `DBNGameScraper` and
`WebDiplomacyGameScraper`. Existing per-provider defaults are unchanged.
Entries are identified by source plus external ID; matching IDs from different
providers coexist. Imports do not deduplicate the same real-world game across
providers.

Run `_app.GameBrowserApp --database data/games.sqlite` to browse the catalog.
`_app.AgentGameApp --database data/games.sqlite` saves the generated game's
historical snapshots before opening its existing in-memory viewer. SQLite JDBC
is required when using a database. Run applications from the repository root.

The common `GameHistory` model uses existing board, calendar, and order contracts.
`DiploBNGame.history()` and `WebDiplomacyParser.parse(rawBundle)` produce source
snapshots; `GameHistory.from(game)` captures a live `Game` with unavailable
submissions. For recorded games, `GameHistory.from(record, finalState, status)`
retains before-resolution boards, submitted orders, outcomes, and a terminal
position. `GameCatalog.catalog(record, finalState, title, status)` stores that
projection using the versioned `GameHistoryJson` format.

Snapshot archives retain internal unit UUIDs and their creation provinces.
They do not serialize processor provenance, private press, or agent-run scoring
metadata, and cannot be used to resume a game. Imported unit identities are
snapshot-local; cross-turn unit tracking requires additional source evidence.
Historical annotations remain separate from detective findings and local
adjudication. The existing DiploBN local-analysis behavior is preserved;
webDiplomacy and internal snapshots do not run a replacement adjudicator.

webDiplomacy decoding covers Classic country/territory IDs, exact coasts,
movement, retreats (including explicit disbands), builds, destroys, waits,
source outcomes, convoy flags, and the final `Finished` position. The original
bundle and its document fingerprints are retained. Unsupported schemas, maps,
orders, IDs, and malformed fields produce decoding errors. Other variants and
live webDiplomacy acquisition remain outside this adapter's scope.

Missing orders are never filled with holds. Availability flags distinguish
absent data from empty arrays. DiploBN retains the existing parser's limitations:
dislodgement details are unavailable and explicit retreat disbands are omitted
from its typed submissions. Source result annotations are still retained.
Training exporters retain their existing source restrictions; browser support
does not establish training eligibility or adjudication equivalence.

Focused checks: `testing.selfcheck.WebDiplomacyParserSelfCheck` and
`testing.selfcheck.AgentGameBrowserSelfCheck --catalog` (the latter needs SQLite
JDBC). Compile with JDK 25+ and the project's JetBrains annotations dependency.

---

## Limitations & Idiosyncrasies

1. *JReferee* allows convoy kidnapping.

It is fun!
Some adjudicators (like <a href="https://www.backstabbr.com/">Backstabbr</a>) allow it, and some do not.

2. *JReferee* does not natively support a `via convoy` flag.

The existence of a convoy operation is an implied result of the convoying fleet 'succeeding', the move succeeding, and the move itself existing.

---

## Tests

Run `TestCaseManager`.

The program parses prints each test result and gives a final DATC-compliance score.

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

*The adjudication (re: `Judge` & subclasses) test cases are read from:*
* SUITE 1 (Raw adjudication): \[\[`src/resources/testgames/*` & `/src/resources/testgames_solutions/*`\]\]
* SUITE 2 (Board state, Retreats & adjustments): \[\[`src/resources/testgames_phase/*`\]\]

*There are a handful of additional misc. test cases in `testing`; implementations of `testing.TestCase`.*

<u><b>See </b></u>`testing.selfcheck`<u><b> for unit tests.</b></u>

---

## Credits

- The late Allan B. Calhamer, who created Diplomacy

- Lucas B. Kruijswijk et al's adjudication work - [Diplomacy Adjudicator Test Cases (DATC)](https://petermc.net/diplomacy/datc_v3_3.html) provide rule interpretations, edge cases, & reference scenarios

- Diplodocus (precursor to Meta AI's *Cicero*) project - especially [this research paper](https://arxiv.org/abs/2210.05492)

- The Diplomacy Broadcast Network (DiploBN) - https://diplobn.com/

- Inspiration: [DipTool](https://www.hagenah.de/diptool/) and [ChessBase](https://chessbase.com)

---