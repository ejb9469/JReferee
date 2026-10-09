package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * A known fleet CONVOY references an active army at its actual canonical
 * territory and names an adjacent canonical destination.
 *
 * <p>One occurrence is produced per convoy submission. The army's own order
 * may be unknown or contradictory: this category describes the issued
 * reference, not an agreed move. Sea location, coastal endpoint geography,
 * connected routes, convoy intent, and actual transport are not assessed.</p>
 */
public final class AdjacentProvinceConvoyDetective extends Detective {

    // Constants \\

    private static final String VERSION = "adjacent-convoy-reference-v2";


    // Construction \\

    public AdjacentProvinceConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.ADJACENT_PROVINCE_CONVOY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order convoy = evidence.order();

            if (convoy.orderType() != OrderType.CONVOY
                    || convoy.unitType() != UnitType.FLEET)
                continue;

            Optional<UnitId> recipient = context.unitAt(convoy.target());

            if (recipient.isEmpty() || recipient.get().unitType() != UnitType.ARMY)
                continue;

            UnitId army = recipient.get();
            Province origin = Province.canonical(context.board().locationOf(army));
            Province destination = Province.canonical(convoy.auxiliaryTarget());

            if (origin == destination || !origin.isAdjacentTo(destination))
                continue;

            findings.add(new TacticMatch(kind(), version(), context, destination,
                    ConvoyRelationships.participants(army, List.of(convoy.unit())), Set.of()));

        }

        return findings;

    }

}
