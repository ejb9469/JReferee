package analysis.tactics.detective;

import analysis.tactics.TacticKind;


/**
 * Maximal destination groups containing movers from at least two powers.
 */
public final class CrossPowerContestDetective extends PowerContestDetective {


    // Construction \\

    public CrossPowerContestDetective() {
        super("cross-power-contest-v1", 2);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CROSS_POWER_CONTEST;
    }


}
