package parsing.diplobn;

import game.Game;
import game.GamePhase;
import game.GameMoment;
import game.record.GameHistory;
import phase.adjustments.DisbandOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public GameHistory history() {
        List<GameHistory.Position> positions = new ArrayList<>();
        for (DiploBNPhase phase : phases) {
            List<GameHistory.Submission> orders = new ArrayList<>();
            phase.movementOrders().forEach(order -> orders.add(
                    GameHistory.Submission.from(order, phase.board().locationOf(order.unit()))));
            phase.adjustmentOrders().forEach(order -> orders.add(GameHistory.Submission.from(order,
                    order instanceof DisbandOrder disband
                            ? phase.board().locationOf(disband.unit()) : order.origin())));
            phase.retreatOrders().forEach(order -> orders.add(
                    GameHistory.Submission.from(order, phase.board().locationOf(order.unit()))));
            List<GameHistory.Annotation> annotations = new ArrayList<>();
            List<DiploBNOrderResolution> results = new ArrayList<>(phase.resolutions());
            results.addAll(phase.retreatResolutions());
            for (var result : results)
                annotations.add(new GameHistory.Annotation(result.nation(), result.issuingProvince(),
                        result.retreatOrder(), result.successful(), result.reason()));
            positions.add(new GameHistory.Position(phase.sourcePhase(), phase.sourceStatus(),
                    new GameMoment(phase.sourcePhase() / 10, phase.gamePhase()), phase.board(),
                    orders, annotations, Map.of(), false, phase.ordersAvailable(), false));
        }
        return new GameHistory(null, positions);
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