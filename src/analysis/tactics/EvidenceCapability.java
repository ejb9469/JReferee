package analysis.tactics;


/**
 * Kind of evidence an investigator would need to support a tactic definition.
 */
public enum EvidenceCapability {

    MOVEMENT_POSITION,
    KNOWN_MOVEMENT_ORDERS,
    ADJUDICATION_OUTCOME,
    COUNTERFACTUAL_RESOLUTION,
    RULESET_POLICY,
    MAP_ADJACENCY,
    RETREAT_ORDERS,
    SUPPLY_CENTER_STATE,
    ADJUSTMENT_STATE,
    MULTI_PHASE_HISTORY,
    OBJECTIVE_STATE,
    AGREEMENT_RECORD,
    INTENT_EVIDENCE,
    SEARCH_HORIZON

}
