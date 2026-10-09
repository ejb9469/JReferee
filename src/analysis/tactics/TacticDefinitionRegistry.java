package analysis.tactics;

import java.util.*;


/**
 * Immutable catalogue of tactical concepts and diagnostic contracts.
 *
 * <p>Definition availability does not imply an implemented detective.
 * Definition versions and detective versions are independent.</p>
 */
public final class TacticDefinitionRegistry {


    // Registry \\

    private static final String V1 = "tactic-definition-v1";
    private static final String V2 = "tactic-definition-v2";
    private static final String V3 = "tactic-definition-v3";

    private static final Map<TacticKind, TacticDefinition> DEFINITIONS =
            createDefinitions();


    // Construction \\

    private TacticDefinitionRegistry() {  }


    // Queries \\

    public static Map<TacticKind, TacticDefinition> definitions() {
        return DEFINITIONS;
    }

    public static TacticDefinition require(TacticKind kind) {

        Objects.requireNonNull(kind, "kind");

        TacticDefinition definition = DEFINITIONS.get(kind);

        if (definition == null)
            throw new IllegalArgumentException(
                    "No tactic definition registered for " + kind);

        return definition;

    }


    // Catalogue construction \\

    private static Map<TacticKind, TacticDefinition> createDefinitions() {

        Map<TacticKind, TacticDefinition> definitions =
                new EnumMap<>(TacticKind.class);


        // Support and cooperation \\

        add(definitions, TacticFamily.SUPPORT_AND_COOPERATION,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(),
                "SUPPORT_TO_MOVE", "A known support order names a known move to the same destination.",
                "SUPPORT_TO_HOLD", "A known support order names a known HOLD, SUPPORT, or CONVOY order.",
                "MUTUAL_HOLD_SUPPORT", "Two units are each known to support the other's stationary order.",
                "SUPPORT_NETWORK", "Known support orders form a connected chain or group through supported units.",
                "SUPPORTING_A_SUPPORTER", "A known support order names a unit that is itself issuing support.",
                "SUPPORTING_A_CONVOY_FLEET", "A known support order names a fleet issuing a convoy order.",
                "CROSS_POWER_SUPPORT", "A known support order and the supported order are issued by different powers.",
                "MULTINATIONAL_SUPPORTED_ATTACK", "A move is supported by units belonging to at least two powers.");

        addVersioned(definitions, TacticFamily.SUPPORT_AND_COOPERATION,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(), V2,
                "MULTIPLE_SUPPORT_TO_MOVE",
                "At least two distinct known supporters reference the same active "
                        + "recipient and canonical move destination. A compatible "
                        + "known MOVE completes the group; an unknown recipient order "
                        + "produces an incomplete group. Contradictory known orders "
                        + "are excluded. One finding contains all matching supporters. "
                        + "Legality and effective strength are not assessed.",
                "MULTIPLE_SUPPORT_TO_HOLD",
                "At least two distinct known hold supporters reference the same "
                        + "active recipient. A known HOLD, SUPPORT, or CONVOY completes "
                        + "the group; an unknown recipient order produces an incomplete "
                        + "group. Known MOVE recipients are excluded. One finding "
                        + "contains all matching supporters; effectiveness is not assessed.");


        // Competition and movement \\

        add(definitions, TacticFamily.COMPETITION_AND_MOVEMENT,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(),
                "SELF_BOUNCE", "At least two same-power moves target the same territory.",
                "CROSS_POWER_CONTEST", "Moves by different powers target the same territory.",
                "MULTIWAY_CONTEST", "Moves by three or more distinct powers target the same territory.",
                "FOLLOW_THE_LEADER", "A move targets the territory another known move vacates.",
                "CHAIN_ADVANCE", "Two or more linked moves target the next unit's current territory.",
                "VACATE_AND_REPLACE", "An occupant moves while another known move targets its current territory.");

        addVersioned(definitions, TacticFamily.COMPETITION_AND_MOVEMENT,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(), V2,
                "HEAD_TO_HEAD_CANDIDATE",
                "Two distinct units have known reciprocal MOVE orders between their "
                        + "current canonical territories. One finding per pair uses "
                        + "the lexicographically first canonical origin as focus. "
                        + "Convoy exceptions, legality, and outcomes are not resolved.",
                "CIRCULAR_MOVEMENT",
                "At least three units form a closed cycle of known MOVE references "
                        + "to one another's initial territories. One finding per cycle "
                        + "excludes incoming feeders and uses the lexicographically "
                        + "first canonical origin as focus. Unknown orders terminate "
                        + "the chain. Two-unit cycles and self-moves are excluded.",
                "FRIENDLY_OCCUPANT_COLLISION",
                "A known MOVE targets a different same-power occupant with a known "
                        + "HOLD, SUPPORT, or CONVOY order. Unknown occupant orders and "
                        + "known departures are excluded. One finding per incoming "
                        + "mover; movement legality and eventual outcome are not assessed.",
                "BELEAGUERED_GARRISON",
                "At least two distinct incoming moves target one occupied canonical "
                        + "territory, each with matching known support-to-move. "
                        + "Unsupported movers are excluded. Participants include "
                        + "the occupant, qualifying attackers, and all matching "
                        + "supporters. The occupant may move or have an unknown order. "
                        + "This does not establish effective support or survival.");


        // Coordination disruption \\

        addVersioned(definitions, TacticFamily.COORDINATION_DISRUPTION,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(), V2,
                "ATTACK_ON_SUPPORTER",
                "One finding per known MOVE targeting a different active unit with "
                        + "a known SUPPORT order. All nationalities are included. "
                        + "Unknown target orders are excluded. No support cut, "
                        + "dislodgement, route legality, or hostile intent is inferred.");

        add(definitions, TacticFamily.COORDINATION_DISRUPTION,
                TacticInterpretation.ASSESSMENT_OUTCOME, adjudicatedMovement(),
                "SUPPORT_CUT", "Adjudication establishes that an attack invalidates a support order.",
                "SUPPORTED_ATTACK_WITH_SUPPORT_CUT", "A supported attack is adjudicated while at least one support is cut.",
                "MULTIPLE_SUPPORT_CUTS", "Adjudication establishes that two or more supports for one attack are cut.",
                "SUPPORTER_DISLODGEMENT", "Adjudication establishes that the unit issuing support is dislodged.",
                "SUPPORT_CUTTER_INTERDICTION", "Adjudication establishes that a would-be support cutter is prevented from cutting support.",
                "SUPPORT_NETWORK_BREAK", "Adjudication establishes that a disruption disconnects a support network.",
                "REDUNDANT_SUPPORT", "A support is shown unnecessary to the assessed result because other support suffices.",
                "CRITICAL_SUPPORT", "A counterfactual comparison shows that removing one support changes the result.");

        add(definitions, TacticFamily.COORDINATION_DISRUPTION,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(),
                "ATTACK_FROM_SUPPORTED_DESTINATION", "A move originates in a territory targeted by a supported move; no cut outcome is implied.");


        // Self-bounce and assistance \\

        add(definitions, TacticFamily.SELF_BOUNCE_AND_ASSISTANCE,
                TacticInterpretation.ASSESSMENT_OUTCOME, adjudicatedMovement(),
                "DEFENSIVE_SELF_BOUNCE", "A same-power contest prevents an enemy move from dislodging the defended unit.",
                "HOSTILE_SUPPORT_BREAKS_SELF_BOUNCE", "Adjudication shows hostile support changes a same-power bounce outcome.",
                "SELF_BOUNCE_UNBALANCED_BY_SUPPORT", "Adjudication shows unequal support changes which same-power move succeeds.",
                "SELF_BOUNCE_CREATES_RETREAT_DENIAL", "A same-power bounce result leaves a retreat destination unavailable.",
                "SUPPORT_FORCES_VACANCY", "A counterfactual comparison shows support changes an occupied destination to vacant.",
                "SUPPORT_FORCES_UNWANTED_ADVANCE", "A counterfactual comparison shows support causes an otherwise unsuccessful move to advance.",
                "HOSTILE_SUPPORT_PRESERVES_GARRISON", "Adjudication shows hostile support preserves a garrison against an attack.",
                "SUPPORT_AGAINST_OWN_UNIT", "A support order names a same-power unit as the beneficiary of an attack.",
                "UNREQUESTED_FOREIGN_SUPPORT", "A foreign-power support order exists without evidence of an agreement or request.",
                "ASSISTED_THIRD_PARTY_ATTACK", "A third-party unit supports an attack between two other powers.");

        requireCapabilities(definitions, Set.of(
                        TacticKind.SUPPORT_FORCES_VACANCY,
                        TacticKind.SUPPORT_FORCES_UNWANTED_ADVANCE,
                        TacticKind.HOSTILE_SUPPORT_PRESERVES_GARRISON),
                with(adjudicatedMovement(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION));

        requireCapabilities(definitions, Set.of(
                        TacticKind.HOSTILE_SUPPORT_BREAKS_SELF_BOUNCE,
                        TacticKind.SUPPORT_AGAINST_OWN_UNIT),
                with(adjudicatedMovement(), EvidenceCapability.RULESET_POLICY));

        requireCapabilities(definitions, Set.of(TacticKind.UNREQUESTED_FOREIGN_SUPPORT),
                with(movement(), EvidenceCapability.AGREEMENT_RECORD,
                        EvidenceCapability.INTENT_EVIDENCE));

        requireCapabilities(definitions, Set.of(
                        TacticKind.SELF_BOUNCE_UNBALANCED_BY_SUPPORT,
                        TacticKind.HOSTILE_SUPPORT_PRESERVES_GARRISON),
                with(adjudicatedMovement(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION));

        requireCapabilities(definitions, Set.of(TacticKind.REDUNDANT_SUPPORT),
                with(adjudicatedMovement(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION));

        requireCapabilities(definitions, Set.of(TacticKind.SELF_BOUNCE_CREATES_RETREAT_DENIAL),
                with(adjudicatedMovement(), EvidenceCapability.RETREAT_ORDERS));


        // Convoy construction \\

        add(definitions, TacticFamily.CONVOY_CONSTRUCTION,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(),
                "CONVOYED_MOVE", "An army move and a fleet convoy order name the same army and destination.",
                "MULTI_FLEET_CONVOY", "Two or more fleet convoy orders name the same army move.",
                "MULTI_ROUTE_CONVOY", "Known convoying fleets provide distinct candidate routes for one army move.",
                "FOREIGN_CONVOY", "A foreign-power fleet issues a convoy order for an army.",
                "MULTINATIONAL_CONVOY", "Fleet convoy orders from multiple powers name the same army move.",
                "SUPPORTED_CONVOY_LANDING", "An army's convoyed destination is also the target of a support order.",
                "CONVOYED_SUPPORT_CUT", "A convoyed move targets the territory occupied by a support cutter.",
                "CONVOY_SWAP", "Two army moves and their convoy orders form a reciprocal candidate convoy exchange.",
                "ADJACENT_PROVINCE_CONVOY", "A convoy order describes an army move between adjacent provinces.",
                "CONVOY_BEHIND_DEFENSIVE_LINE", "A candidate convoyed destination lies beyond a specified defensive line.");

        requireCapabilities(definitions, Set.of(
                        TacticKind.MULTI_ROUTE_CONVOY,
                        TacticKind.CONVOY_SWAP,
                        TacticKind.ADJACENT_PROVINCE_CONVOY,
                        TacticKind.CONVOY_BEHIND_DEFENSIVE_LINE),
                convoyRoutes());

        requireCapabilities(definitions, Set.of(TacticKind.CONVOY_BEHIND_DEFENSIVE_LINE),
                with(convoyRoutes(), EvidenceCapability.OBJECTIVE_STATE));


        // Convoy interference \\

        add(definitions, TacticFamily.CONVOY_INTERFERENCE,
                TacticInterpretation.ASSESSMENT_OUTCOME, adjudicatedConvoy(),
                "CONVOY_DISRUPTION", "Adjudication establishes that a convoy route is disrupted.",
                "CRITICAL_CONVOY_FLEET", "A counterfactual comparison shows that losing one fleet disrupts all routes.",
                "REDUNDANT_CONVOY_ROUTE", "A counterfactual comparison shows another valid route survives one route's loss.",
                "CONVOY_FLEET_PROTECTION", "A counterfactual comparison shows an action protects a required convoy fleet.",
                "CONVOY_KIDNAPPING", "A foreign army's move matches convoy orders under the active ruleset's kidnapping policy.",
                "CONVOY_PARADOX_DEPENDENCY", "Adjudicated convoy and move outcomes form a cyclic dependency.");

        addVersioned(definitions, TacticFamily.CONVOY_INTERFERENCE,
                TacticInterpretation.STRUCTURAL_PATTERN, movement(), V2,
                "ATTACK_ON_CONVOY_FLEET",
                "One finding per known MOVE targeting a different active fleet "
                        + "with a known CONVOY submission. Unknown target orders and "
                        + "non-fleet recipients are excluded. An attack is not proof "
                        + "of convoy disruption, fleet loss, or valid route membership.");

        addVersioned(definitions, TacticFamily.CONVOY_INTERFERENCE,
                TacticInterpretation.DIAGNOSTIC, movement(), V2,
                "UNMATCHED_CONVOY_ORDER",
                "One finding per known CONVOY without a matching known army MOVE "
                        + "to its canonical destination. Absent or non-army recipients "
                        + "and contradictory known orders produce complete diagnostics. "
                        + "An existing army with an unknown order produces an "
                        + "incomplete finding. Route connectivity is not assessed.");

        addVersioned(definitions, TacticFamily.CONVOY_INTERFERENCE,
                TacticInterpretation.DIAGNOSTIC, convoyRoutes(), V2,
                "INCOMPLETE_CONVOY_CHAIN",
                "A known army MOVE between distinct coastal territories has matching "
                        + "sea-fleet convoy submissions but no connected route through "
                        + "those known fleets. A complete candidate route suppresses "
                        + "the finding. No finding is produced without matching "
                        + "sea-fleet submissions or a known army move. This describes "
                        + "the known graph, not inevitable failure under unknown orders.");

        add(definitions, TacticFamily.CONVOY_INTERFERENCE,
                TacticInterpretation.PLANNING,
                with(adjudicatedConvoy(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION,
                        EvidenceCapability.SEARCH_HORIZON),
                "PARADOX_POLICY_SENSITIVE_PLAN", "A plan's result varies with the configured convoy-paradox resolution policy.");

        requireCapabilities(definitions, Set.of(
                        TacticKind.CRITICAL_CONVOY_FLEET,
                        TacticKind.REDUNDANT_CONVOY_ROUTE,
                        TacticKind.CONVOY_FLEET_PROTECTION),
                with(adjudicatedConvoy(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION));

        requireCapabilities(definitions, Set.of(TacticKind.CONVOY_KIDNAPPING),
                with(adjudicatedConvoy(), EvidenceCapability.RULESET_POLICY));

        requireCapabilities(definitions, Set.of(TacticKind.CONVOY_PARADOX_DEPENDENCY),
                with(adjudicatedConvoy(), EvidenceCapability.RULESET_POLICY));

        requireCapabilities(definitions, Set.of(TacticKind.PARADOX_POLICY_SENSITIVE_PLAN),
                with(adjudicatedConvoy(), EvidenceCapability.RULESET_POLICY,
                        EvidenceCapability.COUNTERFACTUAL_RESOLUTION,
                        EvidenceCapability.SEARCH_HORIZON));


        // Dislodgement and retreat \\

        add(definitions, TacticFamily.DISLODGEMENT_AND_RETREAT,
                TacticInterpretation.ASSESSMENT_OUTCOME, retreatEvidence(),
                "SUPPORTED_DISLODGEMENT", "Adjudication establishes that an attack dislodges a unit with support.",
                "RETREAT_TRAP", "A dislodged unit has no legal unoccupied retreat destination.",
                "FORCED_RETREAT_DESTINATION", "Position and retreat rules leave one legal retreat destination.",
                "RETREAT_DESTINATION_OCCUPATION", "A candidate retreat destination is occupied in the retreat position.",
                "RETREAT_DESTINATION_STANDOFF", "A candidate retreat destination is unavailable because of a standoff.",
                "RETREAT_COLLISION", "Two dislodged units select the same retreat destination.",
                "RETREAT_INTO_SUPPLY_CENTER", "A legal retreat destination is a supply center.",
                "RETREAT_BEHIND_ENEMY_LINE", "A legal retreat destination lies behind a defined enemy line.",
                "VOLUNTARY_DISBAND_INSTEAD_OF_RETREAT", "An adjustment choice disbands a dislodged unit instead of retreating.",
                "COOPERATIVE_DISLODGEMENT", "An agreed action contributes to dislodging a unit.");

        requireCapabilities(definitions, Set.of(TacticKind.RETREAT_INTO_SUPPLY_CENTER),
                with(retreatEvidence(), EvidenceCapability.SUPPLY_CENTER_STATE));

        requireCapabilities(definitions, Set.of(TacticKind.RETREAT_BEHIND_ENEMY_LINE),
                with(retreatEvidence(), EvidenceCapability.OBJECTIVE_STATE));

        requireCapabilities(definitions, Set.of(TacticKind.FORCED_RETREAT_DESTINATION),
                with(retreatEvidence(), EvidenceCapability.MAP_ADJACENCY));

        requireCapabilities(definitions, Set.of(TacticKind.VOLUNTARY_DISBAND_INSTEAD_OF_RETREAT),
                with(retreatEvidence(), EvidenceCapability.ADJUSTMENT_STATE));

        requireCapabilities(definitions, Set.of(TacticKind.COOPERATIVE_DISLODGEMENT),
                with(retreatEvidence(), EvidenceCapability.AGREEMENT_RECORD));


        // Centers and winter \\

        add(definitions, TacticFamily.CENTERS_AND_WINTER,
                TacticInterpretation.PLANNING, centerPlanning(),
                "FALL_CENTER_CAPTURE", "A fall movement plan targets a supply center that can be captured.",
                "FALL_CENTER_DEFENSE", "A fall movement plan defends an owned supply center from capture.",
                "CENTER_EXCHANGE", "A plan trades control of one supply center for another.",
                "CENTER_RAID", "A move threatens temporary control of an opponent's supply center.",
                "BUILD_SITE_VACATION", "A unit's planned move vacates a home center used as a build site.",
                "BUILD_SITE_BLOCKADE", "A unit occupies a home center where a build is otherwise available.",
                "BUILD_DENIAL", "A plan removes a legal home-center build opportunity.",
                "FORCED_DISBAND_PRESSURE", "A unit count exceeds controlled supply centers and requires a disband.",
                "DISBAND_AND_REBUILD_REDEPLOYMENT", "A plan disbands a unit to enable a later build in another location.",
                "UNIT_TYPE_REBALANCING", "A build plan changes the army-to-fleet composition of a power.");

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.FALL_CENTER_CAPTURE, TacticKind.FALL_CENTER_DEFENSE,
                        TacticKind.CENTER_EXCHANGE, TacticKind.CENTER_RAID,
                        TacticKind.BUILD_SITE_VACATION),
                with(centerPlanning(), EvidenceCapability.MULTI_PHASE_HISTORY));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.BUILD_SITE_VACATION, TacticKind.BUILD_SITE_BLOCKADE,
                        TacticKind.BUILD_DENIAL, TacticKind.FORCED_DISBAND_PRESSURE,
                        TacticKind.DISBAND_AND_REBUILD_REDEPLOYMENT,
                        TacticKind.UNIT_TYPE_REBALANCING),
                with(centerPlanning(), EvidenceCapability.ADJUSTMENT_STATE));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.DISBAND_AND_REBUILD_REDEPLOYMENT,
                        TacticKind.UNIT_TYPE_REBALANCING),
                with(centerPlanning(), EvidenceCapability.ADJUSTMENT_STATE,
                        EvidenceCapability.MULTI_PHASE_HISTORY));


        // Position and multi-phase planning \\

        add(definitions, TacticFamily.POSITION_AND_MULTI_PHASE,
                TacticInterpretation.PLANNING, planningEvidence(),
                "MULTIPLE_OBJECTIVE_THREAT", "A plan threatens two or more independently represented objectives.",
                "DEFENDER_OVERLOAD", "A defender faces more simultaneous threats than its represented capacity.",
                "DEFLECTION", "An attack induces an opponent to redirect a unit from another position.",
                "DIVERSIONARY_ATTACK", "An attack is planned to draw defensive resources away from another objective.",
                "SACRIFICIAL_SUPPORT_CUT", "A support cutter is exposed to loss in order to disrupt support.",
                "BREAKTHROUGH", "A sequence of moves opens passage through a represented defensive barrier.",
                "FLANKING_REDEPLOYMENT", "A multi-phase route repositions a unit around a represented defensive front.",
                "DEFENSIVE_LINE_FORMATION", "A plan places units along a represented defensive boundary.",
                "STALEMATE_LINE_HOLD", "A position is assessed as maintaining a represented stalemate line.",
                "STALEMATE_LINE_BREACH", "A plan is assessed as opening a represented stalemate line.");

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.DEFLECTION, TacticKind.DIVERSIONARY_ATTACK,
                        TacticKind.SACRIFICIAL_SUPPORT_CUT, TacticKind.BREAKTHROUGH,
                        TacticKind.FLANKING_REDEPLOYMENT, TacticKind.DEFENSIVE_LINE_FORMATION,
                        TacticKind.STALEMATE_LINE_HOLD, TacticKind.STALEMATE_LINE_BREACH),
                with(planningEvidence(), EvidenceCapability.MULTI_PHASE_HISTORY));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.MULTIPLE_OBJECTIVE_THREAT, TacticKind.DEFENDER_OVERLOAD,
                        TacticKind.DEFLECTION, TacticKind.DIVERSIONARY_ATTACK,
                        TacticKind.BREAKTHROUGH, TacticKind.FLANKING_REDEPLOYMENT,
                        TacticKind.STALEMATE_LINE_HOLD, TacticKind.STALEMATE_LINE_BREACH),
                with(planningEvidence(), EvidenceCapability.OBJECTIVE_STATE));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.DEFLECTION, TacticKind.DIVERSIONARY_ATTACK,
                        TacticKind.SACRIFICIAL_SUPPORT_CUT, TacticKind.BREAKTHROUGH,
                        TacticKind.STALEMATE_LINE_BREACH),
                with(planningEvidence(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.SACRIFICIAL_SUPPORT_CUT, TacticKind.BREAKTHROUGH),
                with(planningEvidence(), EvidenceCapability.ADJUDICATION_OUTCOME));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.BREAKTHROUGH, TacticKind.FLANKING_REDEPLOYMENT,
                        TacticKind.DEFENSIVE_LINE_FORMATION, TacticKind.STALEMATE_LINE_HOLD,
                        TacticKind.STALEMATE_LINE_BREACH),
                with(planningEvidence(), EvidenceCapability.MAP_ADJACENCY));

        requireCapabilities(definitions, EnumSet.of(
                        TacticKind.STALEMATE_LINE_HOLD, TacticKind.STALEMATE_LINE_BREACH),
                with(planningEvidence(), EvidenceCapability.RULESET_POLICY,
                        EvidenceCapability.COUNTERFACTUAL_RESOLUTION));


        // Agreements and diagnostics \\

        add(definitions, TacticFamily.AGREEMENTS_AND_DIAGNOSTICS,
                TacticInterpretation.AGREEMENT, agreementEvidence(),
                "ARRANGED_BOUNCE", "An agreement record coordinates opposing moves to produce a bounce.",
                "FAKE_ARRANGED_BOUNCE", "Observed orders conflict with a recorded agreement to arrange a bounce.",
                "DEMILITARIZED_ZONE_VIOLATION", "An order violates a recorded demilitarized-zone commitment.",
                "PROMISED_SUPPORT_WITHHELD", "A recorded support commitment lacks the promised support order.",
                "PROMISED_CONVOY_WITHHELD", "A recorded convoy commitment lacks the promised convoy order.",
                "COORDINATED_STAB", "A recorded cooperation plan is followed by an order that betrays that plan.");

        requireCapabilities(definitions, Set.of(
                        TacticKind.ARRANGED_BOUNCE, TacticKind.FAKE_ARRANGED_BOUNCE),
                with(agreementEvidence(), EvidenceCapability.ADJUDICATION_OUTCOME));

        addVersioned(definitions, TacticFamily.AGREEMENTS_AND_DIAGNOSTICS,
                TacticInterpretation.DIAGNOSTIC, convoyRoutes(), V3,
                "SUPPORT_ORDER_MISMATCH",
                "A SUPPORT accepted by the static validity helper references an "
                        + "absent unit, itself, or a known contradictory recipient "
                        + "order. Unknown recipient orders are not contradictions. "
                        + "Support rejected by the shared static check belongs to "
                        + "BOGUS_MOVES and is excluded. Full legality, effectiveness, "
                        + "intent, and adjudicated success are not established.");

        addVersioned(definitions, TacticFamily.AGREEMENTS_AND_DIAGNOSTICS,
                TacticInterpretation.DIAGNOSTIC, movement(), V2,
                "FOREIGN_COOPERATION_DEPENDENCY",
                "Candidate cross-power relationship, not proven causal dependency. "
                        + "Recognizes compatible foreign support, matching foreign "
                        + "sea-fleet convoy submissions for an army between coastal "
                        + "endpoints, and moves into a foreign occupant's territory "
                        + "when that occupant has a known departure. Unknown support "
                        + "or convoy recipients produce incomplete findings when "
                        + "the active recipient exists; contradictory known orders "
                        + "are excluded. Unknown vacancy orders are not guessed. "
                        + "Assistance may be optional or hostile. No agreement, "
                        + "necessity, usable convoy route, or success is inferred.");

        add(definitions, TacticFamily.AGREEMENTS_AND_DIAGNOSTICS,
                TacticInterpretation.DIAGNOSTIC, convoyRoutes(),
                "BOGUS_MOVES",
                "Known movement-phase submission rejected by the existing static "
                        + "Orders.orderIsValid helper using its actual board origin. "
                        + "The issuer and current territory identify the finding. "
                        + "This is not an exhaustive legality test; unknown routes, "
                        + "support cuts, bounces, and dynamic failure are not "
                        + "automatically bogus.");

        add(definitions, TacticFamily.AGREEMENTS_AND_DIAGNOSTICS,
                TacticInterpretation.DIAGNOSTIC, movement(),
                "MUTUALLY_INCOMPATIBLE_ORDER_BUNDLE", "Two or more orders in a proposed bundle cannot all be satisfied.",
                "TACTICAL_SINGLE_POINT_OF_FAILURE", "A counterfactual comparison identifies one required order whose loss defeats a plan.");

        requireCapabilities(definitions, Set.of(TacticKind.MUTUALLY_INCOMPATIBLE_ORDER_BUNDLE),
                with(movement(), EvidenceCapability.ADJUDICATION_OUTCOME,
                        EvidenceCapability.INTENT_EVIDENCE));

        requireCapabilities(definitions, Set.of(TacticKind.TACTICAL_SINGLE_POINT_OF_FAILURE),
                with(movement(), EvidenceCapability.COUNTERFACTUAL_RESOLUTION,
                        EvidenceCapability.SEARCH_HORIZON));

        if (!definitions.keySet().equals(EnumSet.allOf(TacticKind.class)))
            throw new ExceptionInInitializerError(
                    "Tactic definition catalogue is incomplete");

        return Collections.unmodifiableMap(definitions);

    }


    // Definition helpers \\

    private static void add(
            Map<TacticKind, TacticDefinition> definitions,
            TacticFamily family,
            TacticInterpretation interpretation,
            Set<EvidenceCapability> evidence,
            String... pairs
    ) {
        addVersioned(definitions, family, interpretation, evidence, V1, pairs);
    }

    private static void addVersioned(
            Map<TacticKind, TacticDefinition> definitions,
            TacticFamily family,
            TacticInterpretation interpretation,
            Set<EvidenceCapability> evidence,
            String version,
            String... pairs
    ) {

        Objects.requireNonNull(version, "version");

        if (version.isBlank() || pairs.length % 2 != 0)
            throw new ExceptionInInitializerError(
                    "Invalid definition version or name/semantics pairs");

        for (int index = 0; index < pairs.length; index += 2) {

            TacticKind kind = TacticKind.valueOf(pairs[index]);

            TacticDefinition definition = new TacticDefinition(
                    kind, family, interpretation, evidence,
                    version, pairs[index + 1]);

            if (definitions.putIfAbsent(kind, definition) != null)
                throw new ExceptionInInitializerError(
                        "Duplicate tactic definition: " + kind);

        }

    }

    /**
     * Replaces the complete capability set, preserving the existing
     * construction behavior of unrelated catalogue definitions.
     */
    private static void requireCapabilities(
            Map<TacticKind, TacticDefinition> definitions,
            Set<TacticKind> kinds,
            Set<EvidenceCapability> evidence
    ) {

        for (TacticKind kind : kinds) {

            TacticDefinition definition = definitions.get(kind);

            if (definition == null)
                throw new ExceptionInInitializerError(
                        "Missing definition: " + kind);

            definitions.put(kind, new TacticDefinition(
                    kind,
                    definition.family(),
                    definition.interpretation(),
                    evidence,
                    definition.semanticVersion(),
                    definition.semantics()));

        }

    }


    // Evidence groups \\

    private static Set<EvidenceCapability> movement() {
        return EnumSet.of(
                EvidenceCapability.MOVEMENT_POSITION,
                EvidenceCapability.KNOWN_MOVEMENT_ORDERS);
    }

    private static Set<EvidenceCapability> adjudicatedMovement() {
        return with(movement(), EvidenceCapability.ADJUDICATION_OUTCOME);
    }

    private static Set<EvidenceCapability> convoyRoutes() {
        return with(movement(), EvidenceCapability.MAP_ADJACENCY);
    }

    private static Set<EvidenceCapability> adjudicatedConvoy() {
        return with(convoyRoutes(), EvidenceCapability.ADJUDICATION_OUTCOME);
    }

    private static Set<EvidenceCapability> retreatEvidence() {
        return with(adjudicatedMovement(), EvidenceCapability.RETREAT_ORDERS);
    }

    private static Set<EvidenceCapability> centerPlanning() {
        return with(movement(), EvidenceCapability.SUPPLY_CENTER_STATE);
    }

    private static Set<EvidenceCapability> planningEvidence() {
        return with(movement(),
                EvidenceCapability.SEARCH_HORIZON,
                EvidenceCapability.OBJECTIVE_STATE);
    }

    private static Set<EvidenceCapability> agreementEvidence() {
        return with(movement(),
                EvidenceCapability.AGREEMENT_RECORD,
                EvidenceCapability.INTENT_EVIDENCE);
    }

    private static Set<EvidenceCapability> with(
            Set<EvidenceCapability> source,
            EvidenceCapability... additions
    ) {

        EnumSet<EvidenceCapability> result =
                EnumSet.noneOf(EvidenceCapability.class);

        result.addAll(source);
        result.addAll(Arrays.asList(additions));

        return result;

    }


}