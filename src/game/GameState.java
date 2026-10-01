package game;

import java.util.Objects;

// currently [ @ 10-26-26 ] unused, but will keep in compilation anyway
// --> low overhead, may be useful later
public record GameState(GameMoment gMoment, BoardState boardState) {

    public GameState {
        Objects.requireNonNull(gMoment);
        Objects.requireNonNull(boardState);
    }

}