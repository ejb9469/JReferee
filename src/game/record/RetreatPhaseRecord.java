package game.record;

import game.BoardState;
import game.GamePhase;
import game.Moment;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatResult;

import java.util.List;
import java.util.Objects;



public record RetreatPhaseRecord(
        Moment resolvedAt,
        BoardState boardBefore,
        List<RetreatOrder> submittedOrders,
        RetreatResult result,
        BoardState boardAfter
) implements ResolvedPhaseRecord {

    public RetreatPhaseRecord {

        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(boardBefore, "boardBefore");

        submittedOrders = List.copyOf(Objects.requireNonNull(submittedOrders, "submittedOrders"));
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(boardAfter, "boardAfter");

        if (!resolvedAt.gamePhase().isRetreat())
            throw new IllegalArgumentException("Retreat phase record requires a retreat moment");

        BoardState expectedBoardAfter;
        if (resolvedAt.gamePhase() == GamePhase.FALL_RETREAT)
            expectedBoardAfter = boardBefore.afterFallTurn(result);
        else
            expectedBoardAfter = boardBefore.after(result);

        if (!expectedBoardAfter.equals(boardAfter))
            throw new IllegalArgumentException("Retreat boardAfter does not match its phase result");

    }

}