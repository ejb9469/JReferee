package analysis.tactics;


/**
 * Tactical concepts and diagnostic categories.
 *
 * <p>Grouped by implementation status and outstanding prerequisites.
 * These groups document availability; the agencies remain authoritative
 * for runtime registration and applicability.</p>
 *
 * <p>A category alone does not establish legality, success, intent,
 * or strategic value. Exact semantics and evidence requirements belong
 * to TacticDefinitionRegistry.</p>
 *
 * <p>Enum names are identifiers. Declaration order and ordinals are not
 * persistent IDs.</p>
 */
public enum TacticKind {


    // Implemented: structural and static detectives (36) \\

    // Support relationships
    SUPPORT_TO_MOVE,
    SUPPORT_TO_HOLD,
    MULTIPLE_SUPPORT_TO_MOVE,
    MULTIPLE_SUPPORT_TO_HOLD,
    MUTUAL_HOLD_SUPPORT,
    SUPPORT_NETWORK,
    SUPPORTING_A_SUPPORTER,
    SUPPORTING_A_CONVOY_FLEET,
    CROSS_POWER_SUPPORT,
    MULTINATIONAL_SUPPORTED_ATTACK,

    // Competition and movement
    SELF_BOUNCE,
    CROSS_POWER_CONTEST,
    MULTIWAY_CONTEST,
    BELEAGUERED_GARRISON,
    HEAD_TO_HEAD_CANDIDATE,
    CIRCULAR_MOVEMENT,
    FOLLOW_THE_LEADER,
    CHAIN_ADVANCE,
    FRIENDLY_OCCUPANT_COLLISION,
    VACATE_AND_REPLACE,

    // Attacks on coordination
    ATTACK_ON_SUPPORTER,
    ATTACK_ON_CONVOY_FLEET,
    ATTACK_FROM_SUPPORTED_DESTINATION,

    // Convoy references and candidate routes
    CONVOYED_MOVE,
    MULTI_FLEET_CONVOY,
    MULTINATIONAL_CONVOY,
    FOREIGN_CONVOY,
    SUPPORTED_CONVOY_LANDING,
    MULTI_ROUTE_CONVOY,
    CONVOY_SWAP,
    ADJACENT_PROVINCE_CONVOY,

    // Diagnostics and candidate dependencies
    UNMATCHED_CONVOY_ORDER,
    INCOMPLETE_CONVOY_CHAIN,
    SUPPORT_ORDER_MISMATCH,
    BOGUS_MOVES,
    FOREIGN_COOPERATION_DEPENDENCY,


    // Implemented: resolved-event detectives (1) \\

    SUPPORTER_DISLODGEMENT,


    // Not implemented: feasible next work (13) \\

    /*
     * Existing domain data or resolver infrastructure can support these.
     * They still require explicit contracts, integration, and verification;
     * they are not drop-in structural detectives.
     */

    // Retreat context and shared legality evaluation
    RETREAT_DESTINATION_OCCUPATION,
    RETREAT_DESTINATION_STANDOFF,
    RETREAT_TRAP,
    FORCED_RETREAT_DESTINATION,
    RETREAT_INTO_SUPPLY_CENTER,
    RETREAT_COLLISION,

    // Adjustment context and entitlement evaluation
    BUILD_SITE_BLOCKADE,
    FORCED_DISBAND_PRESSURE,

    // Defined baseline/counterfactual comparisons
    CRITICAL_SUPPORT,
    REDUNDANT_SUPPORT,
    SUPPORT_FORCES_VACANCY,
    SELF_BOUNCE_UNBALANCED_BY_SUPPORT,

    // Candidate-route and resolved-loss interpretation
    CONVOY_DISRUPTION,


    // Not implemented: outcome detail or semantic clarification (17) \\

    // Explicit support-cut evidence
    SUPPORT_CUT,
    SUPPORTED_ATTACK_WITH_SUPPORT_CUT,
    MULTIPLE_SUPPORT_CUTS,

    // Effective contribution and prevention criteria
    SUPPORT_CUTTER_INTERDICTION,
    SUPPORT_NETWORK_BREAK,
    SUPPORTED_DISLODGEMENT,
    DEFENSIVE_SELF_BOUNCE,

    // Ambiguous participant or assistance semantics
    SUPPORT_AGAINST_OWN_UNIT,
    ASSISTED_THIRD_PARTY_ATTACK,
    CONVOYED_SUPPORT_CUT,

    // Convoy interventions, intent, and policy evidence
    CRITICAL_CONVOY_FLEET,
    REDUNDANT_CONVOY_ROUTE,
    CONVOY_FLEET_PROTECTION,
    CONVOY_KIDNAPPING,
    CONVOY_PARADOX_DEPENDENCY,
    PARADOX_POLICY_SENSITIVE_PLAN,

    // Causal connection between movement and retreat denial
    SELF_BOUNCE_CREATES_RETREAT_DENIAL,


    // Not implemented: agreements and intent (12) \\

    HOSTILE_SUPPORT_BREAKS_SELF_BOUNCE,
    HOSTILE_SUPPORT_PRESERVES_GARRISON,
    SUPPORT_FORCES_UNWANTED_ADVANCE,
    UNREQUESTED_FOREIGN_SUPPORT,
    VOLUNTARY_DISBAND_INSTEAD_OF_RETREAT,
    COOPERATIVE_DISLODGEMENT,
    ARRANGED_BOUNCE,
    FAKE_ARRANGED_BOUNCE,
    DEMILITARIZED_ZONE_VIOLATION,
    PROMISED_SUPPORT_WITHHELD,
    PROMISED_CONVOY_WITHHELD,
    COORDINATED_STAB,


    // Not implemented: objectives, history, and planning (22) \\

    // Represented lines and spatial objectives
    CONVOY_BEHIND_DEFENSIVE_LINE,
    RETREAT_BEHIND_ENEMY_LINE,

    // Supply-center and adjustment plans
    FALL_CENTER_CAPTURE,
    FALL_CENTER_DEFENSE,
    CENTER_EXCHANGE,
    CENTER_RAID,
    BUILD_SITE_VACATION,
    BUILD_DENIAL,
    DISBAND_AND_REBUILD_REDEPLOYMENT,
    UNIT_TYPE_REBALANCING,

    // Threats, capacity, and intended redirection
    MULTIPLE_OBJECTIVE_THREAT,
    DEFENDER_OVERLOAD,
    DEFLECTION,
    DIVERSIONARY_ATTACK,
    SACRIFICIAL_SUPPORT_CUT,

    // Multi-phase positioning and adversarial evaluation
    BREAKTHROUGH,
    FLANKING_REDEPLOYMENT,
    DEFENSIVE_LINE_FORMATION,
    STALEMATE_LINE_HOLD,
    STALEMATE_LINE_BREACH,

    // Plan feasibility and causal dependencies
    MUTUALLY_INCOMPATIBLE_ORDER_BUNDLE,
    TACTICAL_SINGLE_POINT_OF_FAILURE


}