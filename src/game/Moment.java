package game;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

// intended to record when a phase was resolved, or when press was sent/received
public record Moment(int year, GamePhase gamePhase, Instant instant)
        implements Comparable<Moment> {

    public Moment {
        Objects.requireNonNull(gamePhase, "gamePhase");
        Objects.requireNonNull(instant, "instant");
    }

    // out-of-class `GameMoment` factory method
    public GameMoment gameMoment() {
        return ( new GameMoment(year, gamePhase) );
    }

    public boolean samePhaseAs(Moment other) {
        // will throw NPE immediately, so no need for `Objects.requireNonNull(...)`
        return gameMoment().equals(other.gameMoment());
    }

    @Override
    public int compareTo(@NotNull Moment other) {

        Objects.requireNonNull(other, "other");

        // break 1st by year
        int yearComparison = Integer.compare(this.year, other.year);
        if (yearComparison != 0)
            return yearComparison;

        // break 2nd by season
        int seasonComparison = Integer.compare(this.gamePhase.ordinal(), other.gamePhase.ordinal());
        if (seasonComparison != 0)
            return seasonComparison;

        // break 3rd by timestamp
        return ( this.instant.compareTo(other.instant) );

    }

}
