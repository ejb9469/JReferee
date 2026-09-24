package parsing.diplobn;

import game.Game;
import game.GamePhase;

import java.util.List;
import java.util.Objects;


/**
 * A complete DiploBN game represented as JReferee-compatible snapshots.
 *
 * <p>DiploBN stores board snapshots and submitted orders; it does not store
 * JReferee's internal adjudication provenance. Consequently, this class exposes
 * parsed phase inputs rather than manufacturing a {@code GameRecord}.</p>
 */
public final class DiploBNGame {


    private final String competition;
    private final String gameLabel;
    private final String sourceUrl;

    private final List<DiploBNPhase> phases;


    DiploBNGame(
            String competition,
            String gameLabel,
            String sourceUrl,
            List<DiploBNPhase> phases
    ) {

        this.competition = competition;
        this.gameLabel = gameLabel;
        this.sourceUrl = sourceUrl;

        this.phases = List.copyOf(
                Objects.requireNonNull(phases, "phases"));

        if (this.phases.isEmpty())
            throw new IllegalArgumentException(
                    "A DiploBN game must contain at least one phase");

    }


    public String competition() {
        return competition;
    }

    public String gameLabel() {
        return gameLabel;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public List<DiploBNPhase> phases() {
        return phases;
    }

    /**
     * Creates a live JReferee Game at the first imported board position.
     *
     * <p>The first DiploBN snapshot must be Spring movement because
     * {@link Game} deliberately starts only at Spring movement.</p>
     */
    public Game newGame() {

        DiploBNPhase firstPhase = phases.getFirst();

        if (firstPhase.gamePhase() != GamePhase.SPRING_MOVEMENT)
            throw new IllegalStateException(
                    "The first imported phase is not Spring movement: "
                            + firstPhase.gamePhase());

        int year = firstPhase.sourcePhase() / 10;

        return new Game(year, firstPhase.board());

    }

}