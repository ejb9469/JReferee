package game;

import phase.movement.MovementResult;

import java.util.InputMismatchException;
import java.util.Objects;

public record GameState_Retreats(
        Moment moment,
        BoardState board,
        MovementResult movementResult
) implements GameState {


    public GameState_Retreats {

        // precondition 1: all fields cannot be null
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(movementResult, "movementResult");

        // precondition 2: retreat-phase Moment
        if (!moment.gamePhase().isRetreat())
            throw new IllegalArgumentException("GameState_Retreats requires a retreat-phase `Moment`");

        // precondition 3: non-empty dislodged units count
        if (movementResult.dislodgements().isEmpty())
            throw new IllegalStateException("Retreat phases (`GameState_Retreats`) require at least 1 dislodged unit to exist");

        // precondition 4: results did not come from the board; mismatch
        if (!board.locations().equals(movementResult.finalLocations()))
            throw new InputMismatchException("Board locations do not match the movement result (`GameState_Retreats`)");

    }


}
