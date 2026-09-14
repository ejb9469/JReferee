# JReferee

A DATC-compliant* Diplomacy adjudicator, written in base Java (natively: OpenJDK 25).

At present, the program (i.e. its only entry point) reads [DATC test cases](https://petermc.net/diplomacy/datc_v3_3.html) from disk, and compares them to  the adjudicator's results. 

These test cases include:
* Illegal orders & invalid orders
* Basic movement - i.e. 'moves, holds, supports, & convoys'
* Advanced tactics - e.g. cyclical movement, head-to-head battles, beleaguered garrisons, convoy swaps, etc.
* "Simple" convoy paradoxes
* Multi-layer "complex" convoy paradoxes
* Butterfly effect & "broken" paradoxes

\*Several DATC cases are incompatible / adjusted with the program - see: *Limitations and Idiosyncrasies* below, and `misc/testcase_alterations`.

---

## Notable classes

- `TestCaseManager.java` *("main")* — loads and runs DATC test cases
- `Adjudicator.java` — deterministic ('rules-based') orders resolvers
  - `Judge.java` *(impl. Adjudicator)* — resolves an *ordered set* of orders
  - `Justice.java` *(ext. Judge)* — runs multiple shuffled adjudications and selects a result when raw results differ
  - `SzykmanReferee.java` *(ext. Justice)* - applies Szykman convoy-paradox rules where conflicting convoy outcomes require, 
      & compares result with result of a Jury if necessary
- `Resolver.java` — non-deterministic ('assumptions-based') orders resolvers [i.e. no *adjudicate(...)*]
  - `Jury.java` - *(impl. Resolver)* determines whether a potential convoy-paradox has a complete "ordinary resolution", without guesses

---

## Tests

Run `TestCaseManager`.

The test cases are read from `src/resources/testgames/*` and `/src/resources/testgames_solutions/*`

The program prints each test result and gives a final DATC-compliance score.

---

## Limitations & Idiosyncrasies

1. *JReferee* allows convoy kidnapping.

It is fun!
Some adjudicators (like <a href="https://www.backstabbr.com/">Backstabbr</a>) allow it, and some do not.

2. *JReferee* does not natively support a `via convoy` flag.

The existence of a convoy operation is an implied result of the convoying fleet 'succeeding', the move succeeding, and the move itself existing.