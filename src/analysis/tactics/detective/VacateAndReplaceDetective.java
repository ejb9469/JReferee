package analysis.tactics.detective;

import analysis.tactics.TacticKind;


/**
 * A known departing mover's original territory is targeted by another mover.
 * Structurally equivalent to following the leader, without outcome claims.
 */
public final class VacateAndReplaceDetective extends VacancyRelationshipDetective {


    // Construction \\

    public VacateAndReplaceDetective() {
        super("vacate-and-replace-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.VACATE_AND_REPLACE;
    }


}
