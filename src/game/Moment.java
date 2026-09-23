package game;

import java.time.Instant;
import java.util.Objects;

// intended to record when a phase was resolved, or when press was sent/received
public record Moment(int year, GamePhase gamePhase, Instant instant) {

    public Moment {
        Objects.requireNonNull(gamePhase, "gamePhase");
        Objects.requireNonNull(instant, "instant");
    }

    public GameMoment gameMoment() {
        return ( new GameMoment(year, gamePhase) );
    }

    public boolean samePhaseAs(Moment other) {
        // will throw NPE immediately, so no need for `Objects.requireNonNull(...)`
        return gameMoment().equals(other.gameMoment());
    }

}
