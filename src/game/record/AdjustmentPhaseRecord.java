package game.record;

import game.BoardState;
import game.Moment;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentResult;

import java.util.List;
import java.util.Objects;


public record AdjustmentPhaseRecord(
        Moment resolvedAt,
        BoardState boardBefore,
        List<AdjustmentOrder> submittedOrders,
        AdjustmentResult result,
        BoardState boardAfter
) implements ResolvedPhaseRecord {

    public AdjustmentPhaseRecord {

        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(boardBefore, "boardBefore");

        submittedOrders = List.copyOf(Objects.requireNonNull(submittedOrders, "submittedOrders"));
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(boardAfter, "boardAfter");

        if (!resolvedAt.gamePhase().isAdjustment())
            throw new IllegalArgumentException("Adjustment phase record requires an adjustment moment");

        BoardState expectedBoardAfter = boardBefore.after(result);

        if (!expectedBoardAfter.equals(boardAfter))
            throw new IllegalArgumentException("Adjustment boardAfter does not match its phase result");

    }

}