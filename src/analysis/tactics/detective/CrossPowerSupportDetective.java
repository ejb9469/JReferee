package analysis.tactics.detective;

import analysis.tactics.*;

import java.util.*;


/**
 * One complete compatible hold/move support edge between different powers.
 * Unknown recipient orders do not establish the supported order.
 */
public final class CrossPowerSupportDetective extends Detective {


    // Construction \\

    public CrossPowerSupportDetective() {
        super("cross-power-support-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CROSS_POWER_SUPPORT;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticMatch edge : StructuralSupportPatterns.complete(context))
            if (StructuralSupportPatterns.supporter(edge).owner()
                    != StructuralSupportPatterns.recipient(edge).owner())
                findings.add(new TacticMatch(kind(), version(), context,
                        edge.focus(), edge.participants(), Set.of()));

        return findings;

    }


}
