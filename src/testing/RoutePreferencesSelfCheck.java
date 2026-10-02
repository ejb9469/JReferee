package testing;

import analysis.RoutePrediction;
import analysis.RoutePreferences;
import analysis.UnitRoutes;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GameMoment;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.UnitId;

import java.time.Instant;
import java.util.*;


public class RoutePreferencesSelfCheck {

    private static final String RULESET = "route-preferences-test";
    private static final GameMoment SPRING =
            new GameMoment(1901, GamePhase.SPRING_MOVEMENT);
    private static final GameMoment FALL =
            new GameMoment(1901, GamePhase.FALL_MOVEMENT);


    private RoutePreferencesSelfCheck() {  }


    public static void main(String[] args) {

        GameRecord first = fixture(false, true);
        GameRecord second = fixture(false, true);

        RoutePreferences preferences = new RoutePreferences(null, 4);

        require(preferences.add(first), "First game was not indexed");
        require(preferences.add(second), "Second game was not indexed");

        UnitId london = unitAt(first.initialBoard(), Province.Lon);

        BoardState fallBoard = first.resolvedPhases().get(1).boardBefore();
        List<String> history = preferences.historyBefore(first.id(), london, FALL);

        RoutePrediction exact = preferences.predict(
                RULESET, FALL, fallBoard, london, history, 2);

        require(exact.basis().equals("EXACT"), "Expected exact matching");
        require(exact.observations() == 2, "Expected two observed decisions");
        require(exact.mostCommon().orElseThrow().target() == Province.ENG,
                "Expected London to English Channel");
        require(exact.probabilities().get(exact.mostCommon().orElseThrow()) == 1.0,
                "Expected one observed action");

        // Different spring instruction, same resulting board.
        GameRecord alternative = fixture(true, true);
        UnitRoutes alternativeRoutes = new UnitRoutes();
        alternativeRoutes.add(alternative);

        UnitId alternativeLondon = unitAt(alternative.initialBoard(), Province.Lon);
        List<String> alternativeHistory = RoutePreferences.historyBefore(
                alternativeRoutes.route(alternative.id(), alternativeLondon), FALL);

        require(!history.equals(alternativeHistory),
                "Suffix fixture must have different full histories");

        require(history.getLast().equals(alternativeHistory.getLast()),
                "Suffix fixture must share the final result event:\n"
                        + "Training: " + history.getLast() + "\n"
                        + "Alternative: " + alternativeHistory.getLast());

        RoutePrediction suffix = preferences.predict(
                RULESET, FALL, fallBoard, london, alternativeHistory, 2);

        require(suffix.basis().equals("SUFFIX"), "Expected recent-history fallback");
        require(suffix.suffixLength() == 1, "Expected the matching result event");
        require(suffix.observations() == 2, "Fallback double-counted observations");

        RoutePrediction position = preferences.predict(
                RULESET, FALL, fallBoard, london, List.of(), 2);

        require(position.basis().equals("POSITION"), "Expected position fallback");
        require(position.observations() == 2, "Position evidence is incorrect");

        RoutePrediction sparse = preferences.predict(
                RULESET, FALL, fallBoard, london, history, 3);

        require(sparse.basis().equals("NONE"), "Insufficient evidence should return NONE");
        require(sparse.counts().isEmpty(), "Sparse prediction should be empty");

        UnitId replacement = new UnitId(
                UUID.randomUUID(), london.owner(), london.unitType(), london.origin());

        Map<UnitId, Province> locations = new LinkedHashMap<>(fallBoard.locations());
        locations.remove(london);
        locations.put(replacement, Province.Lon);

        BoardState reboundBoard = new BoardState(locations, fallBoard.owners());

        RoutePrediction rebound = preferences.predict(
                RULESET, FALL, reboundBoard, replacement, history, 2);

        require(rebound.mostCommon().orElseThrow().unit().equals(replacement),
                "Predicted order was not rebound to the query unit");

        RoutePreferences missing = new RoutePreferences();
        missing.add(fixture(false, false));

        RoutePrediction absent = missing.predict(
                RULESET, FALL, fallBoard, london, List.of(), 1);

        require(absent.counts().isEmpty(), "Synthesized hold became a human preference");

        require(rejects(() -> preferences.predict(
                        RULESET, FALL, fallBoard, london,
                        preferences.route(first.id(), london), 1)),
                "Current or future orders leaked into the query history");

        require(rejects(() -> preferences.add(first)),
                "Duplicate game was counted twice");

        require(rejects(() -> preferences.predict(
                        RULESET, SPRING, first.initialBoard(), london, List.of(), 0)),
                "Invalid evidence threshold was accepted");

        System.out.println("RoutePreferences checks passed.");

    }


    private static GameRecord fixture(boolean alternativeSpring, boolean submitFall) {

        Game game = StandardGameFactory.create1901();
        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        UnitId london = unitAt(game.board(), Province.Lon);
        List<Order> spring = new ArrayList<>();

        for (UnitId unit : game.board().locations().keySet()) {

            if (unit.equals(london)) {
                // Different instructions, same unsuccessful support outcome.
                Province supportedProvince = alternativeSpring ? Province.Yor : Province.Wal;
                spring.add(Order.supportHold(unit, supportedProvince));
            } else {
                spring.add(Order.hold(unit));
            }

        }

        archive.resolveMovement(
                spring,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        List<Order> fall = submitFall
                ? List.of(Order.move(london, Province.ENG))
                : List.of();

        archive.resolveMovement(
                fall,
                new Moment(1901, GamePhase.FALL_MOVEMENT, Instant.EPOCH.plusSeconds(1)));

        return archive.build();

    }

    private static UnitId unitAt(BoardState board, Province province) {

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (entry.getValue() == province)
                return entry.getKey();

        throw new IllegalArgumentException("No unit at " + province);

    }

    private static boolean rejects(Runnable action) {

        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return true;
        }

        return false;

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}