package game;

import phase.retreats.RetreatResult;

import java.util.InputMismatchException;
import java.util.Objects;

public record GameState_Adjustments(
        GameMoment moment,
        BoardState board,
        RetreatResult fallRetreatResult
) implements GameState {


    public GameState_Adjustments {

        // precondition 1: all fields cannot be null
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(fallRetreatResult, "fallRetreatResult");

        // precondition 2: build-phase Moment
        if (!moment.gamePhase().isAdjustment())
            throw new IllegalArgumentException("GameState_Adjustments requires a adjustment-phase `Moment`");

        // precondition 3: results did not come from the board; mismatch
        if (!board.locations().equals(fallRetreatResult.finalLocations()))
            throw new InputMismatchException("Board locations do not match the F retreat result (`GameState_Adjustments`)");

    }


}
