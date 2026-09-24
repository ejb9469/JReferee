package parsing.diplobn;

import contracts.OrderTranslator;
import domain.Province;
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
        implements OrderTranslator<
        DiploBNOrder,
        adjudication.Order> {


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

        return new adjudication.Order(
                source.unit().owner(),
                source.unit().unitType(),
                currentLocation,
                source.type(),
                source.target(),
                source.auxiliaryTarget());

    }

}