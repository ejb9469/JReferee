package parsing.diplobn;

import contracts.OrderForms;
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

        if (inferUniqueFleetDestinationCoast
                && translated.orderType() == OrderType.MOVE
                && translated.unitType() == UnitType.FLEET)
            translated.pos1 = OrderForms.inferDestinationCoast(
                    currentLocation, translated.target());

        return translated;

    }

}