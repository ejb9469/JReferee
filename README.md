# JReferee v1.1

A DATC-compliant* Diplomacy adjudicator, written in base Java (natively: OpenJDK 25).

---

At present, the program (i.e. its only entry point) reads the [DATC test cases](https://petermc.net/diplomacy/datc_v3_3.html) from disk, and compares them to  the adjudicator's results. 

These test cases include:
* Illegal orders & invalid orders
* Basic movement - i.e. 'moves, holds, supports, & convoys'
* Advanced tactics - e.g. cyclical movement, head-to-head battles, beleaguered garrisons, convoy swaps, etc.
* "Simple" convoy paradoxes
* Multi-layer "complex" convoy paradoxes
* Butterfly effect & "broken" paradoxes
* Retreats & Adjustments

\*Several DATC cases are incompatible / adjusted with the program - see: *Limitations and Idiosyncrasies* below, and <u>`misc/testcase_alterations.txt`</u>.
<br>The DATC allows an adjudicator to be called compliant when every test passes or deviations are made consciously. (<a href=https://petermc.net/diplomacy/datc_v3_3.html>petermc.net</a>)

---

## Notable Classes & Infrastructure

- ### `src._app.*`
  - `TestCaseManager.java` *(Entry Point)* — loads and runs DATC test cases

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

---

## Tests

Run `TestCaseManager`.

The test cases are read from:
* SUITE 1 (Raw adjudication): \[\[`src/resources/testgames/*` & `/src/resources/testgames_solutions/*`\]\]
* SUITE 2 (Board state, Retreats & adjustments): \[\[`src/resources/testgames_phase/*`\]\]

The program prints each test result and gives a final DATC-compliance score.

---

## Limitations & Idiosyncrasies

1. *JReferee* allows convoy kidnapping.

It is fun!
Some adjudicators (like <a href="https://www.backstabbr.com/">Backstabbr</a>) allow it, and some do not.

2. *JReferee* does not natively support a `via convoy` flag.

The existence of a convoy operation is an implied result of the convoying fleet 'succeeding', the move succeeding, and the move itself existing.