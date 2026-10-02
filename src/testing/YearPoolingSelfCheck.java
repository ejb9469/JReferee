package testing;

import analysis.RoutePrediction;
import analysis.RoutePreferences;
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


public final class YearPoolingSelfCheck {

    private static final String RULESET = "year-pooling-self-check";


    private YearPoolingSelfCheck() {  }


    public static void main(String[] args) {

        RoutePreferences baseline = new RoutePreferences(null, 4);
        RoutePreferences pooled = new RoutePreferences(null, 4, true);

        GameRecord first = fixture();

        for (GameRecord record : List.of(first, fixture(), fixture())) {
            require(baseline.add(record), "Baseline training record was not added");
            require(pooled.add(record), "Pooled training record was not added");
        }

        int gamesBeforeDuplicate = pooled.gameCount();
        boolean duplicateRejected = false;

        try {
            pooled.add(first);
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }

        require(duplicateRejected,
                "Duplicate training game should be rejected");

        require(pooled.gameCount() == gamesBeforeDuplicate,
                "Rejected duplicate changed the indexed game count");

        BoardState board = StandardGameFactory.create1901().board();
        UnitId london = unitAt(board, Province.Lon);

        GameMoment spring1902 =
                new GameMoment(1902, GamePhase.SPRING_MOVEMENT);

        RoutePrediction absent = baseline.predict(
                RULESET, spring1902, board, london, List.of(), 3);

        require(absent.counts().isEmpty(),
                "Baseline unexpectedly generalized across years");

        RoutePrediction recovered = pooled.predict(
                RULESET, spring1902, board, london, List.of(), 3);

        require(recovered.basis().equals("POSITION_YEAR_POOLED"),
                "Expected pooled-position fallback");

        require(recovered.observations() == 3,
                "Pooled evidence was duplicated or lost");

        require(recovered.counts().equals(Map.of(Order.hold(london), 3L)),
                "Pooled instruction was not rebound to the query unit");

        GameMoment spring1901 =
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

        List<String> initialHistory = List.of(
                "RULESET|" + RULESET,
                "1901|SPRING_MOVEMENT|BIRTH|INITIAL");

        RoutePrediction original = baseline.predict(
                RULESET, spring1901, board, london, initialHistory, 3);

        RoutePrediction unchanged = pooled.predict(
                RULESET, spring1901, board, london, initialHistory, 3);

        require(original.equals(unchanged),
                "Pooling changed an existing qualifying prediction");

        require(pooled.predict(
                                RULESET, spring1902, board, london, List.of(), 4)
                        .counts().isEmpty(),
                "Pooling bypassed the observation threshold");

        require(pooled.predict(
                                RULESET,
                                new GameMoment(1902, GamePhase.FALL_MOVEMENT),
                                board, london, List.of(), 3)
                        .counts().isEmpty(),
                "Pooling incorrectly combined Spring and Fall");

        require(pooled.predict(
                                "different-ruleset",
                                spring1902, board, london, List.of(), 3)
                        .counts().isEmpty(),
                "Pooling incorrectly combined rulesets");

        Map<UnitId, Province> changedLocations =
                new LinkedHashMap<>(board.locations());

        // Yorkshire neighbors London. This changes the query neighborhood.
        changedLocations.put(unitAt(board, Province.Lvp), Province.Yor);

        BoardState changedNeighborhood =
                new BoardState(changedLocations, board.owners());

        require(pooled.predict(
                                RULESET, spring1902, changedNeighborhood,
                                london, List.of(), 3)
                        .counts().isEmpty(),
                "Pooling incorrectly discarded neighborhood context");

        System.out.println("YearPooling checks passed.");

    }


    private static GameRecord fixture() {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        List<Order> orders = game.board().locations().keySet().stream()
                .map(Order::hold)
                .toList();

        archive.resolveMovement(
                orders,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

    }

    private static UnitId unitAt(BoardState board, Province province) {

        return board.locations().entrySet().stream()
                .filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new AssertionError(message);

    }

}