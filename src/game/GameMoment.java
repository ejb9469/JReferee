package game;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * <p>Unlike {@link Moment}, a GameMoment has no wall-clock timestamp. Use it
 * for replay navigation, board-snapshot lookup, resolved-phase identity, and
 * database indexing.</p>
 */
public record GameMoment(int year, GamePhase gamePhase)
        implements Comparable<GameMoment> {

    public GameMoment {
        Objects.requireNonNull(gamePhase, "gamePhase");
    }

    /**
     * Orders game moments by year, then by standard Diplomacy phase order.
     */
    @Override
    public int compareTo(@NotNull GameMoment other) {

        Objects.requireNonNull(other, "other");

        // break 1st by year
        int yearComparison = Integer.compare(this.year, other.year);
        if (yearComparison != 0)
            return yearComparison;

        // break 2nd by season
        return Integer.compare(
                this.gamePhase.ordinal(),
                other.gamePhase.ordinal()
        );

    }

}