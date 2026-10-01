package parsing.diplobn;

import adjudication.util.Orders;
import contracts.OrderTranslator;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;

import java.util.Objects;


public final class DiploBNAdjudicationOrderTranslator
        implements OrderTranslator<DiploBNOrder, adjudication.Order> {

    private final boolean inferUniqueFleetDestinationCoast;


    public DiploBNAdjudicationOrderTranslator() {
        this(false);
    }

    public DiploBNAdjudicationOrderTranslator(boolean inferUniqueFleetDestinationCoast) {
        this.inferUniqueFleetDestinationCoast = inferUniqueFleetDestinationCoast;
    }


    @Override
    public adjudication.Order translate(DiploBNOrder source, BoardState board) {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(board, "board");

        Province currentLocation = board.locationOf(source.unit());

        if (currentLocation == null)
            throw new IllegalArgumentException(
                    "DiploBN order names a unit absent from source board: " + source.unit());

        adjudication.Order translated = new adjudication.Order(source, currentLocation);

        if (inferUniqueFleetDestinationCoast)
            inferDestinationCoast(translated);

        return translated;

    }

    private static void inferDestinationCoast(adjudication.Order order) {

        if (order.orderType() != OrderType.MOVE
                || order.unitType() != UnitType.FLEET
                || order.target() == null
                || order.target().parent != null)
            return;

        Province destination = order.target();
        Province uniqueCoast = null;

        for (Province coast : Province.values()) {

            if (coast.parent != destination)
                continue;

            adjudication.Order candidate = new adjudication.Order(order);
            candidate.pos1 = coast;

            if (!Orders.orderIsValid(candidate)
                    || !candidate.origin().isAdjacentTo(coast)
                    || !Province.adjacentBySea(candidate.origin(), coast))
                continue;

            // Multiple reachable coasts: do not guess.
            if (uniqueCoast != null)
                return;

            uniqueCoast = coast;

        }

        if (uniqueCoast != null)
            order.pos1 = uniqueCoast;

    }

}