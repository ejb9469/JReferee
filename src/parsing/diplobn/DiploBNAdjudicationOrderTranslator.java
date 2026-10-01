package parsing.diplobn;

import adjudication.util.Orders;
import contracts.OrderTranslator;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;

import java.util.Objects;


/**
 * Translates one decoded DiploBN movement order into JReferee's mutable
 * adjudication work-order representation.
 *
 * <p>Every translated order is a new mutable object suitable for use in one
 * isolated adjudication run.</p>
 */
public final class DiploBNAdjudicationOrderTranslator
        implements OrderTranslator<DiploBNOrder, adjudication.Order> {


    private final boolean inferUniqueFleetDestinationCoast;


    public DiploBNAdjudicationOrderTranslator() {
        this(false);
    }

    /**
     * @param inferUniqueFleetDestinationCoast Whether to interpret an
     * unspecified fleet destination coast when exactly one coast is legally
     * reachable. Explicit coasts and source orders are left unchanged.
     */
    public DiploBNAdjudicationOrderTranslator(
            boolean inferUniqueFleetDestinationCoast
    ) {
        this.inferUniqueFleetDestinationCoast =
                inferUniqueFleetDestinationCoast;
    }


    @Override
    public adjudication.Order translate(
            DiploBNOrder source,
            BoardState board
    ) {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(board, "board");

        Province currentLocation = board.locationOf(source.unit());

        if (currentLocation == null)
            throw new IllegalArgumentException(
                    "DiploBN order names a unit absent from source board: "
                            + source.unit());

        adjudication.Order translated = new adjudication.Order(
                source.unit().owner(),
                source.unit().unitType(),
                currentLocation,
                source.type(),
                source.target(),
                source.auxiliaryTarget());

        if (inferUniqueFleetDestinationCoast)
            inferDestinationCoast(translated);

        return translated;

    }

    private static void inferDestinationCoast(
            adjudication.Order order
    ) {

        if (order.orderType != OrderType.MOVE
                || order.unitType != UnitType.FLEET
                || order.pos1 == null
                || order.pos1.parent != null) {
            return;
        }

        Province destination = order.pos1;
        Province uniqueCoast = null;

        for (Province coast : Province.values()) {

            if (coast.parent != destination)
                continue;

            adjudication.Order candidate = new adjudication.Order(order);
            candidate.pos1 = coast;

            if (!Orders.orderIsValid(candidate)
                    || !candidate.pos0.isAdjacentTo(coast)
                    || !Province.adjacentBySea(candidate.pos0, coast)) {
                continue;
            }

            // Multiple reachable coasts: do not guess.
            if (uniqueCoast != null)
                return;

            uniqueCoast = coast;

        }

        if (uniqueCoast != null)
            order.pos1 = uniqueCoast;

    }

}