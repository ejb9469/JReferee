package parsing.diplobn;

import contracts.OrderTranslator;
import game.BoardState;
import phase.Order;

import java.util.Objects;


/**
 * Translates one decoded DiploBN movement order into JReferee's immutable
 * movement-phase order representation.
 */
public final class DiploBNOrderTranslator
        implements OrderTranslator<DiploBNOrder, Order> {


    @Override
    public Order translate(
            DiploBNOrder source,
            BoardState board
    ) {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(board, "board");

        requireUnitOnBoard(source, board);

        return switch (source.type()) {
            case HOLD -> Order.hold(source.unit());
            case MOVE -> Order.move(
                    source.unit(),
                    source.target());
            case SUPPORT -> translateSupport(source);
            case CONVOY -> new Order(
                    // Preserve illegal historical submissions for adjudication.
                    // The convenience factory rejects non-fleet issuers too early.
                    source.unit(),
                    source.type(),
                    source.target(),
                    source.auxiliaryTarget());
            default -> throw new IllegalStateException(
                    "Unsupported DiploBN movement type: "
                            + source.type());
        };

    }


    private static Order translateSupport(
            DiploBNOrder source
    ) {

        if (source.auxiliaryTarget() == null)
            return Order.supportHold(
                    source.unit(),
                    source.target());

        return Order.supportMove(
                source.unit(),
                source.target(),
                source.auxiliaryTarget());

    }

    private static void requireUnitOnBoard(
            DiploBNOrder source,
            BoardState board
    ) {

        if (board.locationOf(source.unit()) != null)
            return;

        throw new IllegalArgumentException(
                "DiploBN order names a unit absent from source board: "
                        + source.unit());

    }

}