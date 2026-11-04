package game;

import java.util.Objects;

public record GameState_Movement(
        GameMoment moment,
        BoardState board
) implements GameState {


    public GameState_Movement {

        // precondition 1: all fields cannot be null
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");

        // precondition 2: movement-phase Moment
        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException("GameState_Movement requires a movement-phase `Moment`");

    }


}
