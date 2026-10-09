package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.UnitType;
import phase.UnitId;

import java.util.*;


/**
 * One compatible hold-support edge to an active fleet with known CONVOY.
 * The submission is evidence, not proof of a valid convoy or sea location.
 */
public final class SupportingAConvoyFleetDetective extends Detective {


    // Construction \\

    public SupportingAConvoyFleetDetective() {
        super("supporting-a-convoy-fleet-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORTING_A_CONVOY_FLEET;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticMatch edge : StructuralSupportPatterns.complete(context, false)) {

            UnitId recipient = StructuralSupportPatterns.recipient(edge);

            if (recipient.unitType() == UnitType.FLEET
                    && context.orders().get(recipient).order().orderType() == OrderType.CONVOY)
                findings.add(new TacticMatch(kind(), version(), context,
                        edge.focus(), edge.participants(), Set.of()));

        }

        return findings;

    }


}
