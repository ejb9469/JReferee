# Tactical detective implementation and verification

## Scope and evidence boundary

This inventory concerns the default `DetectiveAgency`, not every concept that
could theoretically be recognized by a different investigator. The catalogue
contains 101 identifiers in `TacticKind`; `TacticDefinitionRegistry` defines each.
The starting checkout registered 16 kinds. The executable
`DefaultDetectivesSelfCheck` derives catalogue, registered, and deferred totals
from the enum and actual agency, rather than fixing an unsupported-count constant.
“Deferred” below means **not implemented**, not a test that passed.

The agency supplies only `MOVEMENT_POSITION`, `KNOWN_MOVEMENT_ORDERS`, and
`MAP_ADJACENCY`. Its immutable context accepts movement phases only. An absent
submission is unknown, never an implicit hold. Known provenance is retained;
recognition does not turn planned or assumed submissions into observed facts.
All board lookups use current locations, not `UnitId.origin` or UUID ordering.
Focus is canonical territory; exact submitted origins/destinations and fleet
coasts remain in the source context and browser participant text.

**Structural** findings describe references in submissions, including potentially
illegal or unsuccessful submissions unless an explicit static check is required.
**Static diagnostics** describe only the named check, not exhaustive legality.
**Adjudicated, causal, planning, and agreement** concepts remain distinct. DBN
historical result annotations are not trusted local adjudication evidence.
Retreat/adjustment snapshots produce `NOT_APPLICABLE`, not coerced movement
positions. Foreign cooperation is candidate evidence, not alliance inference.
Alliance inference is deferred and outside this work.

Soundness and completeness are acceptance goals **relative to each contract
below**, not a guarantee of globally zero false positives/negatives. Bounded
reference comparisons and finite regression tests cannot prove universal
perfection, adjudication correctness, or player intent.

## Common finding rules

Unless a row says otherwise:

* Require known submissions for all order-dependent participants; exclude
  absent, contradictory, and self-referential recipients.
* A canonical-reference match does not establish exact adjudicator matching or
  support effectiveness. No nationality restriction applies unless stated.
* Return every qualifying occurrence, not just the first; unrelated orders
  cannot suppress occurrences. Grouped findings include all qualifying units.
* A complete finding means its **local** prerequisites are known, not that all
  scenario orders are known or that the tactic succeeds.
* Results, participants, missing-order sets, and contexts are immutable;
  deterministic sorting uses focus, role, and actual location. One unit may
  have distinct roles, but the same `(role, unit)` cannot be duplicated.

Verification references below denote standalone sources under
`src/testing/selfcheck/`. `DetectiveSelfCheck` covers the original support and
self-bounce boundary; `BeleagueredGarrisonSelfCheck` covers distinct supported
attackers. Additional implementation-specific checks and exact run results are
listed in the verification section.

## Implemented-existing catalogue entries

| Exact kind | Preconditions, grouping/focus/roles, missing policy and exclusions |
| --- | --- |
| `SUPPORT_TO_MOVE` | One finding per support-to-move referencing a different active unit and canonical destination. A matching known MOVE is complete; unknown recipient gives missing `SUPPORTED_UNIT`; contradictory known order is excluded. Focus destination; `SUPPORTER`, `SUPPORTED_UNIT`. No static legality filter. |
| `SUPPORT_TO_HOLD` | One finding per hold support to a different active recipient with HOLD, SUPPORT, or CONVOY; unknown recipient gives an incomplete finding; known MOVE excluded even if it might fail. Focus recipient; `SUPPORTER`, `SUPPORTED_UNIT`. |
| `SELF_BOUNCE` | At least two distinct same-power known MOVE issuers target one canonical territory. One finding per power/destination containing all its attackers; focus destination, `ATTACKER` roles. Unknown orders contribute nothing. A candidate contest, not an adjudicated bounce. |
| `BELEAGUERED_GARRISON` | Occupied canonical territory with at least two **distinct** incoming MOVE issuers, each having matching known support-to-move. Include defender, qualifying attackers, and all their supporters. Multiple supporters for one mover are not multiple attacks; unsupported attackers excluded. Defender may depart or have unknown order; no defender-order prerequisite. Focus garrison. No effective-strength or survival claim. |
| `MULTIPLE_SUPPORT_TO_MOVE` | At least two distinct supporters name the same active recipient/canonical destination. Group all matching supporters; focus destination, `SUPPORTER` and `SUPPORTED_UNIT`. Matching known MOVE complete, unknown recipient incomplete, contradictory recipient excluded. |
| `MULTIPLE_SUPPORT_TO_HOLD` | At least two distinct hold supporters name one active recipient. Group all supporters; focus recipient with `SUPPORTER`/`SUPPORTED_UNIT`. Known stationary order complete, unknown incomplete, known MOVE excluded. |
| `HEAD_TO_HEAD_CANDIDATE` | Two distinct known reciprocal MOVE issuers between their current canonical territories. One finding per unordered pair; lexicographically first canonical origin is focus, both `ATTACKER`. Unknown orders, self-moves excluded. Can overlap with a convoy swap; no actual head-to-head outcome asserted. |
| `CIRCULAR_MOVEMENT` | Closed directed cycle of at least three known MOVE issuers targeting one another's initial territories. One finding per cycle; exclude feeders, two-unit cycles, self-moves; unknown orders terminate traversal. First canonical origin is focus, cycle units `ATTACKER`. No simultaneous-success claim. |
| `FRIENDLY_OCCUPANT_COLLISION` | Known MOVE into a different same-power occupant with known HOLD, SUPPORT, or CONVOY. One finding per incoming mover; focus occupant, `ATTACKER`/`DEFENDER`. Unknown occupants and known departures excluded; no legality/outcome assertion. |
| `ATTACK_ON_SUPPORTER` | Known MOVE targets a different active unit issuing known SUPPORT. One per incoming mover; focus supporter, `ATTACKER`/`SUPPORTER`. Unknown target orders excluded; any nationality and support submission allowed. No cut or dislodgement inferred. |
| `ATTACK_ON_CONVOY_FLEET` | Known MOVE targets a different active fleet issuing known CONVOY. One per incoming mover; focus fleet, `ATTACKER`/`CONVOYING_UNIT`. Unknown and non-fleet recipients excluded. Does not assert that fleet is a usable route node or will be disrupted. |
| `UNMATCHED_CONVOY_ORDER` | One per known CONVOY with absent/non-army recipient or contradictory known army order; existing army with unknown order gives incomplete diagnostic. Matching known army MOVE to canonical destination excluded. Focus destination; `CONVOYING_UNIT`, active `REFERENCED_UNIT` where applicable, even if not an army. Static invalidity may overlap; no route/success assertion. |
| `INCOMPLETE_CONVOY_CHAIN` | Known army MOVE between distinct coastal territories and at least one matching sea fleet, but no connecting route in the known matching fleet graph. One per army/destination; focus destination, army and all matching sea fleets. Inland endpoints, coastal convoy fleets, non-fleets, unrelated submissions excluded. Unknown fleets never invented as bridges; local known-graph diagnostic is complete, not inevitable failure. |
| `SUPPORT_ORDER_MISMATCH` | One per SUPPORT accepted by the shared static validity helper but referencing an absent recipient, itself, or a known contradictory order. Unknown active recipient excluded. Focus destination (or hold recipient); `SUPPORTER` plus active `REFERENCED_UNIT`. A statically impossible support, including F Mar S NWG–Syr, belongs in `BOGUS_MOVES`, not mismatch. |
| `FOREIGN_COOPERATION_DEPENDENCY` | One per compatible cross-power support reference, matching foreign sea-fleet convoy reference between coastal endpoints, or move into a foreign occupant's known-departing territory. Support/convoy unknown active recipients give incomplete findings; contradictory known recipients excluded. Vacancy requires known departure. Focus assisted destination/vacated territory; source relationship roles retained. No agreement, necessity, hostility, alliance, usable route, or success inferred. |
| `BOGUS_MOVES` | One per known submission rejected by existing `Orders.orderIsValid` using actual issuing location, not creation origin. Focus issuer territory, `ISSUER`. Missing orders produce nothing. Not exhaustive dynamic legality; failures from bounce, support cut, unknown convoy route, or adjudication are not automatically bogus. |

Existing algorithms and their versions are retained; shared convoy graph
extraction must preserve the incomplete-chain contract. Regression verification
includes static-bogus versus mismatch separation, complete expected participant
sets, canonical/coast handling, partial evidence, and repeated investigations.

Per-kind verification references:

* `SUPPORT_TO_MOVE`, `SUPPORT_TO_HOLD`, `SELF_BOUNCE`:
  `DetectiveSelfCheck`, `DetectiveAgencySelfCheck`,
  `InvestigationReportSelfCheck`.
* `BELEAGUERED_GARRISON`: those checks plus
  `BeleagueredGarrisonSelfCheck` (supported-attack counting and unsupported
  attacker exclusions).
* Each of the other twelve existing rows:
  `RegisteredDetectivesSelfCheck` (explicit fixture signatures and independent
  bounded predicates, including overlap and incomplete-information boundaries).

## Implemented-new catalogue entries

The definitions are versioned to state the chosen grouping and exclusions
explicitly. No outcome concept has been renamed into a structural candidate.
The former network and chain wording did not specify occurrence boundaries:
V2 explicitly chooses multi-edge maximal components and open maximal paths.
Supported convoy landing explicitly requires support for the convoyed army,
not unrelated support for a different attacker to the same destination. These
are deliberate versioned structural clarifications, not claims that the old
one-line definitions already specified those exclusions.

| Exact kind | Contract |
| --- | --- |
| `MUTUAL_HOLD_SUPPORT` | Two distinct known reciprocal support-to-hold issuers; both recipients are issuing SUPPORT. One per unordered pair; first canonical territory focus; both units retain supporter and supported-unit roles. Unknown or move-support references excluded. |
| `SUPPORT_NETWORK` | One maximal weakly connected component with at least two complete compatible support edges. Hold/move edges may mix; reciprocal and larger cycles qualify. Participants are the union of supporter and supported-unit roles; first canonical member location focus. Unknown/contradictory edges are excluded. A single support edge is not a network. No effective strength/resilience claim. |
| `SUPPORTING_A_SUPPORTER` | Known hold support names a different active unit issuing SUPPORT. One per relationship; focus recipient; `SUPPORTER`/`SUPPORTED_UNIT`. Unknown recipients and incompatible support-to-move excluded. Recipient's own support need not succeed. |
| `SUPPORTING_A_CONVOY_FLEET` | Known hold support names a different active fleet issuing CONVOY. One per relationship; focus recipient; `SUPPORTER`/`SUPPORTED_UNIT`. Non-fleet, unknown, or incompatible references excluded; no convoy-route/protection outcome claimed. |
| `CROSS_POWER_SUPPORT` | Compatible support and known supported order issued by different powers. One per support relationship; focus supported destination/territory, support roles. Unknown recipient excluded because the definition requires both orders; no agreement/necessity asserted. |
| `MULTINATIONAL_SUPPORTED_ATTACK` | Known non-self MOVE has matching known supporters belonging to at least two distinct powers (supporters' powers, not merely mover plus supporter). One per mover/destination with all matching supporters; focus destination, supporter/supported-unit roles. Unknown/contradictory mover excluded. |
| `CROSS_POWER_CONTEST` | Known MOVE issuers from at least two powers target one canonical territory. One per destination including all incoming movers; focus destination, `ATTACKER`. Unknown orders cannot count toward the threshold; no adjudicated contest outcome. |
| `MULTIWAY_CONTEST` | As above but at least three distinct powers, not merely three units. One group includes all incoming movers, including multiple units of one qualifying power. |
| `FOLLOW_THE_LEADER` | Known MOVE targets a different occupant's current canonical territory, and that occupant has a known MOVE away from its own territory. One per link; focus vacated territory; incoming/departing participant roles preserved. Unknown departure, hold, and self-move excluded. No successful following asserted. |
| `CHAIN_ADVANCE` | One maximal open path of at least two distinct known non-self movers, each targeting the next member's current territory. Branches yield separate maximal paths, not suffix findings. Final mover may target empty, unknown-order, or nonmoving occupancy; those occupants are not members. Cycles and cycle-feeding paths are excluded, distinguishing open chains from circular movement. First canonical member focus; all members `ISSUER`. No legal route/success claim. |
| `VACATE_AND_REPLACE` | Known departing occupant and another known incoming MOVE targeting its initial territory. One per incoming/departing pair, focus vacated territory. All nationalities; unknown, stationary, and self-departure excluded. Overlap with `FOLLOW_THE_LEADER` is intentional, not a causal replacement claim. |
| `ATTACK_FROM_SUPPORTED_DESTINATION` | Known outgoing non-self MOVE originates in a territory targeted by a different known supported non-self MOVE. Include the outgoing attacker, supported incoming mover, and all matching supporters. Focus shared territory. Does not require attacking the supporter and does not assert a support cut. |
| `CONVOYED_MOVE` | Matching known army MOVE and known fleet CONVOY reference. One grouped army/destination occurrence, convoy roles; focus destination. Matching submissions are candidates, not a route or successful transport. |
| `MULTI_FLEET_CONVOY` | At least two distinct matching known fleet convoy submissions name one known army MOVE. One army/destination group with all qualifying fleets. Does not turn disconnected matching submissions into a route. |
| `MULTI_ROUTE_CONVOY` | At least two distinct simple sea-fleet paths connect one known army MOVE's coastal endpoints in the matching sea-fleet graph. One army/destination group; exact route-related inclusion policy is specified in the definition. Paths may share nodes: “distinct” does not mean disjoint or redundant. No survival/counterfactual claim. |
| `FOREIGN_CONVOY` | One per known fleet CONVOY naming an active army of another power at its actual territory. Focus submitted destination, `CONVOYING_UNIT`/`CONVOYED_ARMY`. Army order may be unknown or contradictory: this is the issued convoy reference, not a matching move. Local finding is complete because no army-order prerequisite exists. No sea-route, agreement, or kidnapping claim. |
| `MULTINATIONAL_CONVOY` | Matching known fleet convoy submissions from at least two fleet powers name one known army MOVE. Army nationality alone cannot satisfy the threshold. Group all matching fleets; no route/success guarantee. |
| `SUPPORTED_CONVOY_LANDING` | One per matching known support-to-move for an army MOVE with matching known fleet convoy submissions. Include all matching fleets and that supporter; army retains `CONVOYED_ARMY` and `SUPPORTED_UNIT` roles. Self-support excluded. Focus landing. No usable transport route or effective landing support asserted. |
| `CONVOY_SWAP` | Reciprocal known army MOVE candidates, each with a connecting known sea-fleet route. One unordered pair, first canonical origin focus; both armies and qualifying fleet evidence. No actual exchange or head-to-head exception outcome asserted. |
| `ADJACENT_PROVINCE_CONVOY` | One per known fleet CONVOY naming an active army at its actual territory and a distinct adjacent canonical destination. Focus destination, `CONVOYED_ARMY`/`CONVOYING_UNIT`. Army order may be unknown or contradictory: issued reference is locally complete. No coastal-endpoint, sea-fleet, or connected-route requirement; inland/coastal-fleet references may overlap bogus diagnostics. No transport intent/success inferred. Detective version `adjacent-convoy-reference-v2` records the final submission-reference contract. |

All twelve new support/movement kinds (from `MUTUAL_HOLD_SUPPORT` through
`ATTACK_FROM_SUPPORTED_DESTINATION`) are verified individually by
`StructuralSupportMovementSelfCheck`. All eight new convoy kinds are verified
individually by `ConvoyConstructionSelfCheck`. Both compare **complete finding
signatures** (kind/focus/participant roles/actual locations/missing prerequisites)
against independently built reference sets, not merely nonempty output.
`DefaultDetectivesSelfCheck` verifies their actual default registration,
whole-catalogue coverage, definitions/evidence applicability, negative unknown
contexts, overlapping complete positive sets, and reuse.

The matching-only convoy categories (`CONVOYED_MOVE`, `MULTI_FLEET_CONVOY`,
`FOREIGN_CONVOY`, `MULTINATIONAL_CONVOY`, `SUPPORTED_CONVOY_LANDING`) do not
require sea-fleet positions, coastal endpoints, a non-self move, or a connected
route. They deliberately describe submitted correspondence, which can overlap
static bogus diagnostics. Route categories instead require distinct coastal
endpoints and matching fleets actually at sea; land/coastal fleets and unknown
orders never bridge the sea graph. Army endpoint contact considers every coast;
intermediate coastal provinces are not route nodes. All new support/movement
findings have empty missing sets because required unknown orders are excluded.
Foreign convoy references are locally complete even with unknown army orders,
since that contract requires only the issued reference. Adjacent-province
convoy references likewise do not require the army's order or a sea route;
only the actual referenced army, distinct endpoints, and map adjacency are
prerequisites.

`GameInvestigationIntegrationSelfCheck` exercises the public DBN parser and
browser JSON writer: exact all-kind coverage/versions, default availability,
unknown-order serialization, split-coast submissions, duplicate/invalid input,
retreat/adjustment `NOT_APPLICABLE`, and invariance under changed or absent DBN
historical outcome annotations. Test-only sentinels check applicability gating
for every catalogue definition and distinguish skipped from unsupported.

## Deferred catalogue entries

Every row states why it was passed over and what would be needed to implement
the original concept. Having an adjudicator elsewhere in the repository is not
the same as supplying trusted, policy-identified outcomes and counterfactuals
through this context. No empty placeholder detectives are registered.

| Exact kind | Reason / missing evidence | Concrete prerequisites |
| --- | --- | --- |
| `SUPPORT_CUT` | Submission references alone cannot establish a cut. | Trusted adjudicated support status, attack exceptions, convoy and ruleset identity. |
| `SUPPORTED_ATTACK_WITH_SUPPORT_CUT` | Requires actual support effectiveness and cut outcomes. | Per-order adjudicated status linked to supported attack and cut supports. |
| `MULTIPLE_SUPPORT_CUTS` | Several attacks on supporters do not prove several cuts. | Adjudicated cuts grouped by the same attack, distinct support identities. |
| `SUPPORTER_DISLODGEMENT` | Attack candidate is not dislodgement. | Trusted final position and dislodged-unit verdicts linked to support submissions. |
| `SUPPORT_CUTTER_INTERDICTION` | “Would-be cutter” and prevention are causal, not geometric. | Defined cutter counterfactual and trusted paired resolutions. |
| `SUPPORT_NETWORK_BREAK` | Structural graph change does not prove effective disruption. | Effective support graph before/after adjudication and a precise disconnect criterion. |
| `REDUNDANT_SUPPORT` | Necessity cannot be read from supporter count. | Reproducible resolution with/without each support and unchanged-result criterion. |
| `CRITICAL_SUPPORT` | One supporter is not proof of criticality. | Paired policy-identical counterfactual resolutions and target-result definition. |
| `DEFENSIVE_SELF_BOUNCE` | Requires preventing an enemy dislodgement, not same-power destinations. | Trusted adjudication plus represented defended unit and prevention comparison. |
| `HOSTILE_SUPPORT_BREAKS_SELF_BOUNCE` | Hostility and changed outcome unavailable. | Intent/agreement policy for “hostile,” ruleset identity, baseline and supported resolutions. |
| `SELF_BOUNCE_UNBALANCED_BY_SUPPORT` | Unequal support references do not establish changed success. | Counterfactual and actual resolved move outcomes under one policy. |
| `SELF_BOUNCE_CREATES_RETREAT_DENIAL` | Standoff and retreat denial are post-resolution facts. | Adjudicated standoff set, dislodgements, retreat legality and causal comparison. |
| `SUPPORT_FORCES_VACANCY` | Occupant departure does not prove support forced it. | Paired resolutions demonstrating support changes occupied to vacant. |
| `SUPPORT_FORCES_UNWANTED_ADVANCE` | “Unwanted” and support-caused advance are not submissions. | Intent evidence, a defined baseline, and trusted counterfactual resolution. |
| `HOSTILE_SUPPORT_PRESERVES_GARRISON` | Cannot infer hostility or causal preservation. | Defined hostile intent and paired trusted survival outcomes. |
| `SUPPORT_AGAINST_OWN_UNIT` | Existing assessment definition/policy has unresolved beneficiary versus defender semantics. | Clarify exact own-unit relation compatibly, identify self-dislodgement policy, supply adjudication. Do not silently substitute a nationality heuristic. |
| `UNREQUESTED_FOREIGN_SUPPORT` | Absence of a recorded request is not evidence that no request occurred. | Complete scoped agreement/request ledger and explicit intent/evidence completeness policy. |
| `ASSISTED_THIRD_PARTY_ATTACK` | Catalogue defines an assessed attack, not just three nationality labels. | Trusted effective-support/attack outcomes and precise “third-party” relation. |
| `CONVOYED_SUPPORT_CUT` | Current wording targets a **support cutter**, not merely a supporter; actual cutting is unavailable and semantics ambiguous. | Resolve cutter/cut interpretation, provide trusted cut outcomes and convoy participation. Do not alias `ATTACK_ON_SUPPORTER`. |
| `CONVOY_BEHIND_DEFENSIVE_LINE` | No represented defensive line or “behind” relation. | Explicit objective/line geometry, orientation and candidate-route definition. |
| `CONVOY_DISRUPTION` | Attack on convoy fleet does not prove route disruption. | Trusted fleet dislodgements and policy-resolved convoy outcomes. |
| `CRITICAL_CONVOY_FLEET` | A route node need not be causally necessary. | All applicable routes and paired removal/loss counterfactuals. |
| `REDUNDANT_CONVOY_ROUTE` | Distinct routes can share a critical fleet or fail together. | Route survival under specified losses and trusted counterfactual policy. |
| `CONVOY_FLEET_PROTECTION` | Support/attack references do not prove protection. | Defined protected fleet and paired adjudication showing the action prevents loss. |
| `CONVOY_KIDNAPPING` | Depends on ruleset choice and army transport intention. | Explicit kidnapping/adjacent-convoy policy, transport intent and resolved participation. |
| `CONVOY_PARADOX_DEPENDENCY` | Reference cycles are not adjudication dependency cycles. | Trusted adjudicator dependency trace and explicit paradox policy. |
| `PARADOX_POLICY_SENSITIVE_PLAN` | No policy comparisons or planning horizon. | Multiple trusted policy resolutions, counterfactual plan and search-domain definition. |
| `SUPPORTED_DISLODGEMENT` | Move plus support is not proof of dislodgement. | Trusted movement resolution, effective support and retreat/dislodged-unit state. |
| `RETREAT_TRAP` | Movement context has no dislodged-unit retreat position. | Retreat state with dislodger origins, standoffs, occupancy and legal-destination rules. |
| `FORCED_RETREAT_DESTINATION` | Cannot enumerate legal retreats from movement snapshot. | Complete retreat legality evidence and unique-destination enumeration. |
| `RETREAT_DESTINATION_OCCUPATION` | Requires the actual post-movement retreat position. | Phase-specific retreat context and occupied destination evidence. |
| `RETREAT_DESTINATION_STANDOFF` | Known competing submissions do not prove a standoff. | Trusted movement standoff set and retreat candidate identities. |
| `RETREAT_COLLISION` | Retreat orders are not movement orders. | Dedicated retreat-order context, distinct dislodged issuers and destination grouping. |
| `RETREAT_INTO_SUPPLY_CENTER` | Requires legal retreat and actual phase state. | Retreat legality plus canonical supply-center metadata/control as required by definition. |
| `RETREAT_BEHIND_ENEMY_LINE` | No legal retreat state or represented enemy line. | Retreat context, defined enemy-line geometry/orientation and objective evidence. |
| `VOLUNTARY_DISBAND_INSTEAD_OF_RETREAT` | No retreat/disband choice or voluntary intent evidence. | Phase-specific disband submissions, available legal retreat alternatives and intent policy. |
| `COOPERATIVE_DISLODGEMENT` | Requires an agreement contributing to actual dislodgement. | Scoped agreement ledger, adjudicated dislodgement and contribution contract. |
| `FALL_CENTER_CAPTURE` | Fall-target reference does not prove capturability/control transfer. | Trusted fall resolution, temporal center state and represented capture plan/search. |
| `FALL_CENTER_DEFENSE` | No defense objective or capture-prevention assessment. | Center-control history, explicit defended objective and planning/adjudication comparison. |
| `CENTER_EXCHANGE` | Two center-directed moves are not a planned exchange. | Temporal control changes and explicit exchange objective/plan. |
| `CENTER_RAID` | “Threatens temporary control” needs a horizon and objective. | Current control, future trajectory/search and temporary-control criterion. |
| `BUILD_SITE_VACATION` | Leaving a home center does not establish a winter build opportunity. | Fall-to-winter history, controlled home centers, build entitlements and plan. |
| `BUILD_SITE_BLOCKADE` | Occupancy alone does not establish otherwise-available build. | Winter adjustment state, current ownership, entitlement and legal-build rules. |
| `BUILD_DENIAL` | Build removal is a causal planning claim. | Before/after legal build opportunities and trusted plan resolution. |
| `FORCED_DISBAND_PRESSURE` | Movement board unit count alone is not winter obligation. | Adjustment phase, controlled-center totals, entitlements and obligation rules. |
| `DISBAND_AND_REBUILD_REDEPLOYMENT` | Multi-phase identity/intent unavailable. | Ordered disband/build history, resources and explicit redeployment plan. |
| `UNIT_TYPE_REBALANCING` | No build plan or intended composition change. | Adjustment submissions, pre/post unit composition and represented plan. |
| `MULTIPLE_OBJECTIVE_THREAT` | No independently represented objectives or search. | Objective model, threat feasibility and bounded planning domain. |
| `DEFENDER_OVERLOAD` | Number of incoming orders is not represented defensive capacity. | Objective/capacity model, simultaneous feasible threats and planning/search. |
| `DEFLECTION` | Cannot infer induced opponent redirection. | History, explicit objectives and counterfactual causal model/search. |
| `DIVERSIONARY_ATTACK` | Purpose is unavailable. | Represented diversion objective/intent and counterfactual resource-allocation assessment. |
| `SACRIFICIAL_SUPPORT_CUT` | Requires actual cut, loss exposure and purpose. | Trusted outcomes, risk/counterfactual model, horizon and intent/objective contract. |
| `BREAKTHROUGH` | No represented barrier or successful sequence. | Barrier geometry, history, outcomes and counterfactual multi-step search. |
| `FLANKING_REDEPLOYMENT` | One move is not a multi-phase route around a front. | Route history/search, front geometry and objective. |
| `DEFENSIVE_LINE_FORMATION` | Spatial proximity is not a defined defensive boundary plan. | Explicit line geometry, formation objective and multi-phase planning. |
| `STALEMATE_LINE_HOLD` | Static occupancy cannot certify a stalemate line. | Represented line, ruleset, adversarial horizon and verified counterfactual assessment. |
| `STALEMATE_LINE_BREACH` | Attack reference cannot certify a breach. | Defined line/objective and trusted multi-phase adversarial counterfactual search. |
| `ARRANGED_BOUNCE` | Contest is not evidence of arrangement or actual bounce. | Complete agreement record linked to submissions and trusted bounce outcome. |
| `FAKE_ARRANGED_BOUNCE` | Cannot invent an agreement contradicted by orders. | Recorded bounce commitment, scoped evidence completeness and outcome/violation policy. |
| `DEMILITARIZED_ZONE_VIOLATION` | No recorded DMZ commitment. | Agreement ledger with zone geometry, powers, times and violation rules. |
| `PROMISED_SUPPORT_WITHHELD` | Unknown order is not proof of withheld promise. | Recorded promise, complete relevant submissions and explicit fulfillment criterion. |
| `PROMISED_CONVOY_WITHHELD` | No promise/complete fulfillment evidence. | Recorded convoy commitment, relevant known submissions and fulfillment policy. |
| `COORDINATED_STAB` | Betrayal cannot be inferred from foreign attacks. | Cooperation agreement/history and defined betrayal/intent criterion. |
| `MUTUALLY_INCOMPATIBLE_ORDER_BUNDLE` | “Cannot all be satisfied” requires intended results and resolution. | Represented bundle objectives and trusted adjudication/feasibility assessment. |
| `TACTICAL_SINGLE_POINT_OF_FAILURE` | One reference dependency does not prove plan failure. | Explicit plan/result objective, horizon and paired removal counterfactuals. |

## Browser selection and lifecycle

The labeled native checkbox fieldset lists every registered coverage kind,
including zero-findings and skipped kinds. All are selected initially. Kinds
are OR'ed; country and local completeness filters are AND'ed. Clear selection
means **zero displayed findings**, with an explicit message; Select all restores
all registered kinds. Cards preserve every participant of a selected finding.
Safe `textContent` construction is retained.

Country/completeness changes preserve kind selection on the same report.
A new report identity, phase selection, game selection, reset, loading or error
clears stale output; each new evaluated snapshot starts with all kinds selected.
Non-movement/error reports disable the controls and show no stale cards.
`games.js` already aborts old game requests and checks a version before applying
success **and** failure responses. Filtering never invokes investigators,
mutates report data, or changes whole-snapshot coverage/counts.

Map fills, exact order hover text, retreat/build/disband markers, focus inspection,
loopback HTTP protections and read-only database behavior are unchanged.

## Verification record

Use the actual configured OpenJDK 25, not the environment's default Java 17.
The configured annotations `26.0.2` and SQLite JDBC `3.53.4.0` artifacts are used
without changing repository dependencies. Scratch classes and HTTP database
fixtures were removed after the checks; no build artifacts or new dependency
files are committed.

Baseline compile:

```sh
cd /home/runner/work/JReferee/JReferee
/usr/lib/jvm/temurin-25-jdk-amd64/bin/javac \
  -cp /tmp/jreferee-deps/annotations-26.0.2.jar \
  -d /tmp/jreferee-baseline $(find src -name '*.java')
```

Baseline results before implementation: `DetectiveSelfCheck` 2,100 assertions,
`TacticArchitectureSelfCheck` 444, and `BeleagueredGarrisonSelfCheck` 59 passed.
`DetectiveAgencySelfCheck` failed because its old default-registry fixture
expected only four kinds. `InvestigationReportSelfCheck` failed because it
expected only one finding for a participant despite already-registered overlaps.
Those legacy fixtures now select explicit registries matching their test domain;
their original exact-result assertions remain. Focused rerun: agency 74 checks,
report 45 checks passed. Expanded default registration is independently checked
by `DefaultDetectivesSelfCheck`, not omitted from testing.

Browser checks:

```sh
node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
GAMES_BROWSER_CDP=http://127.0.0.1:9222 \
  node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
node --check src/resources/ui/games-detectives.js
```

Results: 19 tests passed with the optional browser test skipped when CDP is
absent; with Chromium CDP, all 20 passed, including label click, Space toggling,
Tab focus, and Enter actions. Tests include multiple selected kinds, all/none,
zero findings, AND composition, unchanged full participants/coverage, skipped
and unsupported kinds, errors/non-movement, dynamic registration, report/game
transitions, immutable input, safe text, stale success/failure request handling,
and existing map presentation safeguards.

Final integrated Java commands (the transient output directory was removed):

```sh
cd /home/runner/work/JReferee/JReferee
J=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-1/x64/bin
D=/tmp/jreferee-deps/annotations-26.0.2.jar:/tmp/jreferee-deps/sqlite-jdbc-3.53.4.0.jar
mkdir -p .selfcheck-existing/classes
"$J/javac" -cp "$D" -d .selfcheck-existing/classes $(find src -name '*.java')
CP=.selfcheck-existing/classes:$D
for C in RegisteredDetectivesSelfCheck GameInvestigationIntegrationSelfCheck \
  DetectiveSelfCheck BeleagueredGarrisonSelfCheck DetectiveAgencySelfCheck \
  InvestigationReportSelfCheck TacticArchitectureSelfCheck DefaultDetectivesSelfCheck \
  StructuralSupportMovementSelfCheck ConvoyConstructionSelfCheck \
  ConvoyRoutePerformanceSelfCheck; do
  "$J/java" --enable-native-access=ALL-UNNAMED \
    -Dorg.sqlite.tmpdir=.selfcheck-existing -cp "$CP" testing.selfcheck.$C
done
"$J/java" -cp "$CP" phase.movement.NormalizedOrderSelfCheck
"$J/java" -cp "$CP" performance.PerformanceStatsSelfCheck
"$J/java" --enable-native-access=ALL-UNNAMED \
  -Dorg.sqlite.tmpdir=.selfcheck-existing -cp "$CP" _app.TestCaseManager
node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
rm -rf .selfcheck-existing
```

| Check | Final result / verification domain |
| --- | --- |
| All `src` Java sources | Compiled successfully with configured Java 25 and dependency jars. |
| `StructuralSupportMovementSelfCheck` | 202,013 assertions; 14,568 independently enumerated contexts (`23³` support states plus `7⁴` movement states), comparing all twelve new kinds. Golden/adversarial cases supplement the bounded domain. |
| `ConvoyConstructionSelfCheck` | 58,966 assertions; 2,304 independent route-reference scenarios: three six-sea domains × twelve directed endpoint pairs × 64 fleet subsets. Additional correspondence, unknown/contradictory orders, adjacent references, nationality and split-coast cases cover all eight new kinds. |
| `RegisteredDetectivesSelfCheck` | 157,560 assertions; 21,984 **kind/context comparisons**, not 21,984 distinct board states. Includes three-unit `2 owners × 2 unit types × 8³ submissions`, support `2 recipient owners × 6 states × 5³ supporter submissions`, eight endpoint pairs × two army-order states × 64 fleet subsets, and static/geographic/reference fixtures. Covers each of the twelve other existing kinds. |
| `GameInvestigationIntegrationSelfCheck` | 1,772 assertions: public parser → JSON → actual loopback HTTP server; all enum definitions/coverage, DBN outcome independence, unknown/coast evidence, invalid inputs, non-movement phases, capability gating, zero-finding availability. HTTP GET-only/Host/Origin/query safeguards and byte-for-byte unchanged SQLite database verified. Fixture removed in `finally`. |
| `DefaultDetectivesSelfCheck` | 1,085 assertions; actual inventory reports 101 catalogue kinds, 36 registered, 65 deferred. Exact overlapping positive finding set and unknown-only negative set checked; all-kind coverage and applicability checked. |
| Original tactical checks | Detective 2,100; garrison 59; agency 74; report 45; architecture 444 assertions passed. |
| `ConvoyRoutePerformanceSelfCheck` | All 1,722 coastal endpoint pairs passed; independent path-count/union checks and measured timing, not a timeout guarantee. |
| Existing normalization/performance checks | `NormalizedOrderSelfCheck`, `PerformanceStatsSelfCheck` passed. |
| Existing full `_app.TestCaseManager` | 132/132 adjudication cases, 677/677 orders; 34/34 phase-processor cases, 163/163 checks; 96/96 parser/catalog checks passed. |
| JavaScript/browser | 19 portable checks passed; optional Chromium run passed all 20. |

The original-kind reference comparison counts are:
`HEAD_TO_HEAD_CANDIDATE` 2,049; `CIRCULAR_MOVEMENT` 2,051;
`FRIENDLY_OCCUPANT_COLLISION` 2,049; `ATTACK_ON_SUPPORTER` 2,049;
`ATTACK_ON_CONVOY_FLEET` 2,048; `FOREIGN_COOPERATION_DEPENDENCY` 3,648;
`SUPPORT_ORDER_MISMATCH` 1,503; `MULTIPLE_SUPPORT_TO_MOVE` 1,501;
`MULTIPLE_SUPPORT_TO_HOLD` 1,501; `BOGUS_MOVES` 2,461;
`UNMATCHED_CONVOY_ORDER` 98; `INCOMPLETE_CONVOY_CHAIN` 1,026.

One **pre-existing unavailable check** is separate from these passing results:
`misc/BoardSnapshotSelfCheck.java` cannot compile because line 6 imports
nonexistent `game.BoardSnapshot` (the current DTO is in `ui`). No workaround,
test deletion, or unrelated source repair was made. The all-`src` compile and
actual full test manager do not include that miscellaneous source.

The attempted miscellaneous compile exited 1:

```sh
/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-1/x64/bin/javac \
  -cp .selfcheck-existing/classes:/tmp/jreferee-deps/annotations-26.0.2.jar:/tmp/jreferee-deps/sqlite-jdbc-3.53.4.0.jar \
  -d .selfcheck-existing/classes misc/BoardSnapshotSelfCheck.java
```

### Limits

Generated domains deliberately bound units, submissions, nationality combinations
and fleet subsets. Reference predicates are independent of detective algorithms;
integration comparisons may reuse outputs to validate transport/coverage, but
are not presented as independent semantic oracles. No dynamic outcome detective
is claimed verified from static inputs. Agreement, intent, missing-order
completion, arbitrary future phases, custom maps and all adjudication policies
are outside the structural verification domain.

The standard-map recursion depth is bounded by the 19 distinct sea nodes.
`ConvoyRoutePerformanceSelfCheck` examined all 42 canonical coastal endpoints,
1,722 directed distinct-endpoint pairs, and the complete sea graph (every fleet
subset can only remove simple paths). Its independent enumerator counted
24,842 total paths, at most 192 for Smy–Nwy. One run of the production matrix
took 258.867 ms; warmed worst-pair median 0.112 ms, p95 0.205 ms, maximum
0.218 ms. These are observed timings, not a performance guarantee. Route union
and count correctness are separately tested by bounded independent oracles.

The live remote DBN comparator requires external game data and is not a
deterministic local self-check. Browser smoke verifies the checklist in real
Chromium; it does not certify every browser or assistive technology.
