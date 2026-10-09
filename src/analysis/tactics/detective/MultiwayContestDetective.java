package analysis.tactics.detective;

import analysis.tactics.TacticKind;


/**
 * Maximal destination groups containing movers from at least three powers.
 */
public final class MultiwayContestDetective extends PowerContestDetective {


    // Construction \\

    public MultiwayContestDetective() {
        super("multiway-contest-v1", 3);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MULTIWAY_CONTEST;
    }


}
