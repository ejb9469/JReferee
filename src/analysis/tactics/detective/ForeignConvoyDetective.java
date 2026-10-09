package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * A known foreign fleet CONVOY names an active army at its actual territory.
 * The army's own order may be unknown or contradictory: the definition names
 * an issued convoy for an army, not agreement with its move or a sea route.
 */
public final class ForeignConvoyDetective extends Detective {

    // Constants \\

    private static final String VERSION = "foreign-convoy-reference-v1";


    // Construction \\

    public ForeignConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.FOREIGN_CONVOY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order convoy = evidence.order();

            if (convoy.orderType() != OrderType.CONVOY || convoy.unitType() != UnitType.FLEET)
                continue;

            Optional<UnitId> recipient = context.unitAt(convoy.target());

            if (recipient.isEmpty() || recipient.get().unitType() != UnitType.ARMY
                    || recipient.get().owner() == convoy.owner())
                continue;

            findings.add(new TacticMatch(kind(), version(), context, convoy.auxiliaryTarget(),
                    ConvoyRelationships.participants(recipient.get(), List.of(convoy.unit())),
                    Set.of()));

        }

        return findings;

    }

}
