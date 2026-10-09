package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;

import java.util.*;


/**
 * One compatible hold-support edge whose recipient has a known SUPPORT order.
 * The recipient's own support need not be compatible; no recursive prerequisite
 * or effectiveness is inferred.
 */
public final class SupportingASupporterDetective extends Detective {


    // Construction \\

    public SupportingASupporterDetective() {
        super("supporting-a-supporter-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORTING_A_SUPPORTER;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticMatch edge : StructuralSupportPatterns.complete(context, false))
            if (context.orders().get(StructuralSupportPatterns.recipient(edge))
                    .order().orderType() == OrderType.SUPPORT)
                findings.add(new TacticMatch(kind(), version(), context,
                        edge.focus(), edge.participants(), Set.of()));

        return findings;

    }


}
