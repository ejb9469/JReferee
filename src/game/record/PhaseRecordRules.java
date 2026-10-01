package game.record;

import game.BoardState;
import game.GamePhase;
import game.Moment;
import phase.PhaseResult;
import phase.movement.MovementResult;

import java.util.Objects;


/**
 * Shared board-transition policy for archived phase records.
 */
public final class PhaseRecordRules {


    private PhaseRecordRules() {  }


    public static BoardState expectedBoardAfter(
            Moment resolvedAt,
            BoardState boardBefore,
            PhaseResult result
    ) {

        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(boardBefore, "boardBefore");
        Objects.requireNonNull(result, "result");

        GamePhase phase = resolvedAt.gamePhase();

        if (phase == GamePhase.FALL_RETREAT)
            return boardBefore.afterFallTurn(result);

        if (phase == GamePhase.FALL_MOVEMENT
                && result instanceof MovementResult movement
                && movement.dislodgements().isEmpty())
            return boardBefore.afterFallTurn(result);

        return boardBefore.after(result);

    }


}
