package game.record;

import game.BoardState;
import game.GamePhase;
import game.Moment;
import phase.Order;
import phase.movement.MovementResult;

import java.util.List;
import java.util.Objects;


public record MovementPhaseRecord(
        Moment resolvedAt,
        BoardState boardBefore,
        List<Order> submittedOrders,
        MovementResult result,
        BoardState boardAfter
) implements ResolvedPhaseRecord {

    public MovementPhaseRecord {

        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(boardBefore, "boardBefore");

        submittedOrders = List.copyOf(Objects.requireNonNull(submittedOrders, "submittedOrders"));
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(boardAfter, "boardAfter");

        if (!resolvedAt.gamePhase().isMovement())
            throw new IllegalArgumentException("Movement phase record requires a movement moment");

        BoardState expectedBoardAfter = boardBefore.after(result);
        if (resolvedAt.gamePhase() == GamePhase.FALL_MOVEMENT
                && result.dislodgements().isEmpty())
            expectedBoardAfter = boardBefore.afterFallTurn(result);
        else
            expectedBoardAfter = boardBefore.after(result);

        if (!expectedBoardAfter.equals(boardAfter))
            throw new IllegalArgumentException("Movement boardAfter does not match its phase result");

    }

}