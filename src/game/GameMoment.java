package game;

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
    public int compareTo(GameMoment other) {

        Objects.requireNonNull(other, "other");

        int yearComparison = Integer.compare(year, other.year);

        if (yearComparison != 0)
            return yearComparison;

        return Integer.compare(
                gamePhase.ordinal(),
                other.gamePhase.ordinal());

    }

}