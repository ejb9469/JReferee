package analysis.tactics.detective;

import analysis.tactics.TacticKind;


/**
 * A known mover follows a different known departing mover's original territory.
 */
public final class FollowTheLeaderDetective extends VacancyRelationshipDetective {


    // Construction \\

    public FollowTheLeaderDetective() {
        super("follow-the-leader-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.FOLLOW_THE_LEADER;
    }


}
