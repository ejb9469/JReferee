package game;

import java.time.Instant;
import java.util.Objects;

public record Moment(int year, GamePhase gamePhase, Instant instant) {

    public Moment {
        Objects.requireNonNull(gamePhase, "gamePhase");
        Objects.requireNonNull(instant, "instant");
    }

    public boolean samePhaseAs(Moment other) {
        // will throw NPE immediately, so no need for `Objects.requireNonNull(...)`
        return ( year == other.year && gamePhase == other.gamePhase );
    }

}
