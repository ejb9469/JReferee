package game;

import java.util.Objects;

public record GamePosition(GameMoment gMoment, BoardState boardState) {

    public GamePosition {
        Objects.requireNonNull(gMoment);
        Objects.requireNonNull(boardState);
    }

}