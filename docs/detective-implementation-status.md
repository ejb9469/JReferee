# Tactical analysis: implementation status

Source review: `f09187e`.

## Current coverage

The catalogue contains **101 `TacticKind`s**:

- **36** registered by the structural `DetectiveAgency`.
- **1** registered by `OutcomeAgency`: `SUPPORTER_DISLODGEMENT`.
- **64** not implemented by either agency.
- Baseline assessors exist for `SUPPORT_TO_MOVE` and `SUPPORT_TO_HOLD`.
  These assess existing kinds; they are not additional detective categories.

Registration, applicability, and findings are separate. Each agency reports its
own coverage. Do not add their unsupported counts together.

### Evidence rules

- Unknown instructions remain unknown; analysis does not invent HOLDs.
- Structural findings describe submissions, not legality, success, intent, or
  strategic value, unless their definition explicitly requires more evidence.
- A complete local pattern does not imply a complete movement scenario.
- Resolved findings describe an identified local scenario and adjudication
  configuration. Planned or assumed instructions retain their provenance.
- DBN outcome annotations are not local adjudication evidence.
- Participants use current board locations, not `UnitId.origin` or UUID order.
  Focus territories are canonical; original coast-bearing submissions are retained.
- Missing evidence, unsupported categories, and evaluated-negative results are
  distinct. Filtering preserves original coverage.

Exact semantics, evidence requirements, and versions live in
`TacticDefinitionRegistry`. The tables below summarize implementation and blockers.

## Implemented kinds

### Structural and static detectives: 36

| Kinds | Recognition boundary |
| --- | --- |
| `SUPPORT_TO_MOVE`, `SUPPORT_TO_HOLD` | Compatible support references. Unknown active recipient instructions produce incomplete findings; contradictory known instructions do not match. |
| `MULTIPLE_SUPPORT_TO_MOVE`, `MULTIPLE_SUPPORT_TO_HOLD` | At least two distinct supporters for one compatible recipient/destination group. |
| `MUTUAL_HOLD_SUPPORT` | Reciprocal known hold-support submissions. |
| `SUPPORT_NETWORK` | Maximal weakly connected component containing at least two complete compatible support edges. |
| `SUPPORTING_A_SUPPORTER`, `SUPPORTING_A_CONVOY_FLEET` | Hold support for a known supporter or convoying fleet. |
| `CROSS_POWER_SUPPORT` | Compatible support and known recipient instruction from different powers. |
| `MULTINATIONAL_SUPPORTED_ATTACK` | Matching supporters from at least two powers; the mover's nationality alone does not satisfy the threshold. |
| `SELF_BOUNCE` | At least two same-power moves to one territory; not proof of an actual bounce. |
| `CROSS_POWER_CONTEST`, `MULTIWAY_CONTEST` | Incoming moves from at least two or three distinct powers, respectively. |
| `BELEAGUERED_GARRISON` | At least two distinct supported incoming movers target one occupied territory. Unsupported movers are excluded; extra supporters do not count as extra attacks. |
| `HEAD_TO_HEAD_CANDIDATE` | Reciprocal moves. May overlap convoy-swap candidates; does not establish an actual head-to-head battle. |
| `CIRCULAR_MOVEMENT` | Closed movement cycle of at least three units; excludes incoming feeders. |
| `FOLLOW_THE_LEADER`, `VACATE_AND_REPLACE` | Incoming move targets a known departing occupant. Their structural overlap is intentional. |
| `CHAIN_ADVANCE` | Maximal open movement paths; excludes cycles and paths feeding cycles. |
| `FRIENDLY_OCCUPANT_COLLISION` | Move into a same-power occupant with a known stationary instruction. |
| `ATTACK_ON_SUPPORTER`, `ATTACK_ON_CONVOY_FLEET` | Move targets a known supporter or convoying fleet; no cut or disruption claim. |
| `ATTACK_FROM_SUPPORTED_DESTINATION` | Outgoing move originates at a supported incoming move's destination; need not attack the supporter. |
| `CONVOYED_MOVE`, `MULTI_FLEET_CONVOY`, `MULTINATIONAL_CONVOY` | Matching army/fleet submissions; respectively no fleet-count threshold beyond one, at least two fleets, or at least two fleet powers. Matching does not prove connectivity. |
| `FOREIGN_CONVOY` | Foreign fleet's convoy reference to an active army; the army's instruction may be unknown or contradictory. |
| `SUPPORTED_CONVOY_LANDING` | Matching support for the convoyed army, not an unrelated mover to the same destination. |
| `MULTI_ROUTE_CONVOY` | At least two distinct simple candidate sea paths. Paths may share fleets; distinct does not mean redundant. |
| `CONVOY_SWAP` | Reciprocal army moves, each with a connected candidate sea route. |
| `ADJACENT_PROVINCE_CONVOY` | Convoy reference between adjacent territories; no matching army move or usable route required. |
| `UNMATCHED_CONVOY_ORDER` | No matching known army move. An active army with unknown instructions produces an incomplete diagnostic. |
| `INCOMPLETE_CONVOY_CHAIN` | Matching known sea fleets provide no connected route for a known coastal army move. Describes the known graph, not inevitable failure. |
| `SUPPORT_ORDER_MISMATCH` | Statically accepted support references an absent unit, itself, or a contradictory known instruction. Unknown instructions are not contradictions. |
| `BOGUS_MOVES` | Submission rejected by the existing static validity helper. Dynamic failure is not automatically bogus. |
| `FOREIGN_COOPERATION_DEPENDENCY` | Candidate foreign support, convoy, or vacancy relationships—not proven necessity, agreement, or alliance. |

### Resolved-event detective: 1

| Kind | Implementation |
| --- | --- |
| `SUPPORTER_DISLODGEMENT` | `SupporterDislodgementDetective` consumes `ResolvedMovementContext`. One finding per dislodged unit whose original instruction was SUPPORT, including its dislodger. The support need not have been valid or effective. |

### Baseline assessments

| Kind | Implemented claims |
| --- | --- |
| `SUPPORT_TO_MOVE` | Support accepted by the adjudicator; supported move succeeded. |
| `SUPPORT_TO_HOLD` | Support accepted by the adjudicator; recipient remained on the board after movement. |

Accepted support is not necessarily decisive support. Rejected support does not
identify its failure reason. A dislodged recipient may still retreat.

`SELF_BOUNCE` and `BELEAGUERED_GARRISON` have registered claim contracts, but no
concrete assessors. A claim contract is not an implementation.

## Not implemented: feasible next work

These categories can build on existing domain data or the configured resolver.
They still need contracts, implementations, registration, and tests. None is a
drop-in addition to the current structural agency.

| Kinds | Required work |
| --- | --- |
| `RETREAT_DESTINATION_OCCUPATION`, `RETREAT_DESTINATION_STANDOFF` | Add a retreat-analysis context using movement final positions, dislodgements, and standoffs. Specify which candidate destinations are examined. |
| `RETREAT_TRAP`, `FORCED_RETREAT_DESTINATION`, `RETREAT_INTO_SUPPLY_CENTER` | Reuse/extract retreat legality from `RetreatProcessor`; enumerate exact legal destinations, including coast and convoy-arrival rules. A forced destination means one legal retreat destination, not a requirement to choose it. |
| `RETREAT_COLLISION` | Add retreat submissions/results to the analysis context. Distinguish shared submitted destinations from adjudicated legal-retreat collisions. |
| `BUILD_SITE_BLOCKADE`, `FORCED_DISBAND_PRESSURE` | Adapt existing adjustment inputs, ownership, home-center rules, and entitlement calculation. Define the catalogue's planning interpretation explicitly; movement counts alone are insufficient. |
| `CRITICAL_SUPPORT`, `REDUNDANT_SUPPORT` | Define the result being compared and the intervention, then run policy-identical baseline/counterfactual resolutions. |
| `SUPPORT_FORCES_VACANCY`, `SELF_BOUNCE_UNBALANCED_BY_SUPPORT` | Define the baseline and changed instructions; compare occupancy or move success. Retain both scenarios and computation limits. |
| `CONVOY_DISRUPTION` | Combine known candidate routes with resolved fleet losses. Specify route disruption versus failure of all routes, including adjacent-convoy and paradox behavior. |

Retreat and adjustment processing already exist, but the analysis contexts and
browser investigations for those phases do not. Resolver access enables
counterfactual work; it does not supply a finished counterfactual protocol.

## Not currently supportable without additional evidence or clarified semantics

“Not currently supportable” is not “impossible forever.” Do not substitute
heuristics or weaker structural definitions to mark these implemented.

### Outcome detail or semantic clarification

| Kinds | Blocker |
| --- | --- |
| `SUPPORT_CUT`, `SUPPORTED_ATTACK_WITH_SUPPORT_CUT`, `MULTIPLE_SUPPORT_CUTS` | Need explicit support-cut evidence. A false support verdict may also reflect invalidity or mismatch. |
| `SUPPORT_CUTTER_INTERDICTION` | Needs a defined would-be-cutter counterfactual and prevention criterion. |
| `SUPPORT_NETWORK_BREAK` | Needs an effective-support graph and a precise disruption/disconnection criterion. |
| `SUPPORTED_DISLODGEMENT` | Needs an eligible-support contribution definition, not merely an accepted support verdict; current catalogue requirements also include retreat evidence. |
| `DEFENSIVE_SELF_BOUNCE` | Needs a represented defended unit and a comparison establishing prevention of dislodgement. |
| `SUPPORT_AGAINST_OWN_UNIT` | Clarify whether “own unit” is the recipient or defender; preserve self-dislodgement policy distinctions. |
| `ASSISTED_THIRD_PARTY_ATTACK` | Clarify the three-power relationship and submitted versus effective assistance. Current interpretation is an assessed outcome. |
| `CONVOYED_SUPPORT_CUT` | Resolve wording that refers to attacking a “support cutter,” then supply actual cut and convoy-participation evidence. |
| `CRITICAL_CONVOY_FLEET`, `REDUNDANT_CONVOY_ROUTE` | Need defined loss interventions and transport-success criteria; candidate path counts alone are insufficient. |
| `CONVOY_FLEET_PROTECTION` | Needs a protected-fleet objective and a counterfactual establishing protection. |
| `CONVOY_KIDNAPPING` | Needs transport-intent and explicit kidnapping-policy semantics; a foreign convoy reference is insufficient. |
| `CONVOY_PARADOX_DEPENDENCY` | Needs validated dependency-trace interpretation and policy evidence, not merely a reference cycle or recorded recursion cycle. |
| `PARADOX_POLICY_SENSITIVE_PLAN` | Needs comparable policy configurations, a plan/result criterion, and bounded comparisons. |
| `SELF_BOUNCE_CREATES_RETREAT_DENIAL` | Needs retreat legality and a causal comparison linking the self-bounce to destination denial. |

### Agreements and intent

| Kinds | Blocker |
| --- | --- |
| `HOSTILE_SUPPORT_BREAKS_SELF_BOUNCE`, `HOSTILE_SUPPORT_PRESERVES_GARRISON` | Need a definition/evidence of hostility plus outcome-changing counterfactuals. |
| `SUPPORT_FORCES_UNWANTED_ADVANCE` | Needs evidence of what was unwanted and a causal baseline. |
| `UNREQUESTED_FOREIGN_SUPPORT` | Needs scoped completeness of request/agreement evidence; no recorded request is not proof of no request. |
| `VOLUNTARY_DISBAND_INSTEAD_OF_RETREAT` | Needs an explicit voluntary choice and legal alternatives; a missing retreat submission is not sufficient. |
| `COOPERATIVE_DISLODGEMENT` | Needs an agreement linked to actual dislodgement and contribution. |
| `ARRANGED_BOUNCE`, `FAKE_ARRANGED_BOUNCE` | Need recorded bounce commitments and applicable outcome/violation criteria. |
| `DEMILITARIZED_ZONE_VIOLATION` | Needs a structured agreement identifying territory, powers, time, and prohibited actions. |
| `PROMISED_SUPPORT_WITHHELD`, `PROMISED_CONVOY_WITHHELD` | Need explicit commitments, complete relevant submissions, and fulfillment rules. |
| `COORDINATED_STAB` | Needs cooperation history/commitments and a defined betrayal criterion. |

`PressLedger` stores messages; it is not a structured agreement or intent model.

### Objectives, history, and planning

| Kinds | Blocker |
| --- | --- |
| `CONVOY_BEHIND_DEFENSIVE_LINE`, `RETREAT_BEHIND_ENEMY_LINE` | Need represented lines, orientation, and a definition of “behind,” plus applicable route/retreat evidence. |
| `FALL_CENTER_CAPTURE`, `FALL_CENTER_DEFENSE` | Need capture/defense plan semantics and appropriate phase/control evidence. Defense additionally needs a prevention criterion. |
| `CENTER_EXCHANGE`, `CENTER_RAID` | Need temporal control objectives; two center-directed moves do not establish an exchange or temporary raid. |
| `BUILD_SITE_VACATION`, `BUILD_DENIAL` | Need fall-to-winter opportunity evidence; denial additionally requires a causal comparison. |
| `DISBAND_AND_REBUILD_REDEPLOYMENT`, `UNIT_TYPE_REBALANCING` | Need represented adjustment plans and composition/redeployment criteria; redeployment requires linked multi-phase history. |
| `MULTIPLE_OBJECTIVE_THREAT`, `DEFENDER_OVERLOAD` | Need objective, threat-feasibility, and defensive-capacity models. |
| `DEFLECTION`, `DIVERSIONARY_ATTACK` | Need redirection/diversion objectives and a causal model, not merely attacks. |
| `SACRIFICIAL_SUPPORT_CUT` | Needs actual cutting, loss exposure, purpose, and a planning horizon. |
| `BREAKTHROUGH`, `FLANKING_REDEPLOYMENT`, `DEFENSIVE_LINE_FORMATION` | Need represented fronts/barriers and multi-phase objectives or routes. |
| `STALEMATE_LINE_HOLD`, `STALEMATE_LINE_BREACH` | Need explicit line definitions, ruleset, and bounded adversarial evaluation. |
| `MUTUALLY_INCOMPATIBLE_ORDER_BUNDLE` | Needs intended-result semantics and a feasibility criterion. |
| `TACTICAL_SINGLE_POINT_OF_FAILURE` | Needs a plan objective, failure criterion, and removal counterfactuals. |

These three unimplemented tables cover all remaining 64 kinds together with
the feasible-next-work table. Some concepts need assessors or phase-specific
investigators rather than subclasses of the movement-only `Detective`.

## Architecture and browser behavior

- `TacticInvestigator<C, F>` is the shared typed contract.
- `analysis.tactics.Detective` is the structural base;
  implementations live in `analysis.tactics.detective`.
- `OutcomeDetective` and its implementations use `ResolvedMovementContext`.
- `MovementResultValidation` centralizes result correspondence checks.
  The assessment compatibility facade requires explicit processor inputs;
  resolved contexts also allow explicitly confirmed defaulted HOLDs.
- `SupportAssessmentBatch` evaluates recognized support findings against one
  shared result. `ResolutionConfiguration` carries browser metadata.

The browser API separates:

| JSON location | Meaning |
| --- | --- |
| Root `findings` and `coverage` | Structural agency |
| `resolvedEvents` | Outcome agency |
| `localSupportAssessments` | Baseline support claims |

The current JavaScript displays structural findings and support assessments.
It does **not yet render `resolvedEvents`**.

Local resolution is opt-in. Add this JVM option to `GameBrowserApp`, using the
actual adjudicator build identifier:

```text
-Djreferee.assessment.engineRevision=YOUR_BUILD_ID
```

The browser uses `SZYKMAN_JUSTICE`, 32 trials, seed 0. Each complete movement
snapshot is resolved once and shared by outcome investigation and support
assessment. Missing instructions block local resolution. Failures remain
separate from structural findings. Non-movement snapshots are not investigated.

Detective checkboxes use OR across kinds and AND with country/completeness.
All kinds start selected; clearing selects none. Filters do not rerun analysis
or change whole-snapshot counts. New snapshots reset selections.

## Verification

After compiling with OpenJDK 25, run the existing standalone checks without `-ea`:

```sh
for check in \
  DetectiveSelfCheck DetectiveAgencySelfCheck TacticArchitectureSelfCheck \
  InvestigationReportSelfCheck DefaultDetectivesSelfCheck \
  BeleagueredGarrisonSelfCheck StructuralSupportMovementSelfCheck \
  ConvoyConstructionSelfCheck RegisteredDetectivesSelfCheck \
  ConvoyRoutePerformanceSelfCheck SupportAssessmentSelfCheck \
  OutcomeDetectiveSelfCheck
do
  java -cp "$CP" "testing.selfcheck.$check"
done

java -cp "$CP" testing.selfcheck.GameInvestigationIntegrationSelfCheck
java -cp "$CP" _app.TestCaseManager
node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
```

`CP` must include compiled classes and required dependencies. The HTTP/database
integration check needs SQLite JDBC. Optional live Chromium checks use
`GAMES_BROWSER_CDP`.

### Recorded results versus current verification

The earlier structural-detective implementation recorded:

- 14,568 bounded support/movement contexts.
- 2,304 convoy route-reference scenarios.
- 21,984 existing-kind/context comparisons.
- 132/132 adjudication cases, 34/34 phase cases, and 96/96 parser/catalog cases.
- 19 portable browser tests; 20 with the optional Chromium check.

Those are historical results, not a fresh pass of the later assessment,
outcome, and refactor changes. Current-source tests must be rerun before
claiming those changes verified. The additional refactor tests drafted
in-chat are not present in the source snapshot reviewed here.

Finite tests establish behavior within their domains—not universally zero
false positives/negatives, correctness of inferred intent, or equivalence
across adjudication policies. Existing browser test fixtures may need updates
for the expanded assessment UI.

`misc/BoardSnapshotSelfCheck.java` has a previously recorded compile failure
from importing `game.BoardSnapshot` instead of the current `ui` DTO. It is
outside the normal all-`src` compilation.