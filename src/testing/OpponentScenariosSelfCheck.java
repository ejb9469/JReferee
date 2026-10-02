package testing;

import analysis.OpponentScenario;
import analysis.OpponentScenarios;
import analysis.RoutePrediction;
import analysis.RoutePreferences;
import analysis.openings.Classifier;
import domain.Nation;
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


public class OpponentScenariosSelfCheck {

    private static final String RULESET = "opponent-scenarios-test";


    private OpponentScenariosSelfCheck() {  }


    public static void main(String[] args) {

        BoardState board = StandardGameFactory.create1901().board();

        UnitId paris = unitAt(board, Province.Par);
        UnitId berlin = unitAt(board, Province.Ber);
        UnitId london = unitAt(board, Province.Lon);

        Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() != Nation.ENGLAND)
                predictions.put(unit, prediction(Map.of(Order.hold(unit), 4L)));

        predictions.put(paris, prediction(Map.of(
                Order.move(paris, Province.Bur), 3L,
                Order.hold(paris), 1L)));

        predictions.put(berlin, prediction(Map.of(
                Order.move(berlin, Province.Pru), 3L,
                Order.hold(berlin), 1L)));

        OpponentScenarios generator = new OpponentScenarios(2, 4, 8);

        List<OpponentScenario> scenarios =
                generator.generate(board, Nation.ENGLAND, predictions);

        require(scenarios.size() == 4, "Expected four opponent combinations");

        Set<UnitId> expected = new HashSet<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() != Nation.ENGLAND)
                expected.add(unit);

        Set<String> signatures = new HashSet<>();

        for (OpponentScenario scenario : scenarios) {

            Set<UnitId> actual = new HashSet<>();

            for (Order order : scenario.orders()) {
                require(order.owner() != Nation.ENGLAND, "Own order leaked into a scenario");
                require(actual.add(order.unit()), "Duplicate scenario issuer");
            }

            require(actual.equals(expected), "Scenario does not cover every opponent");
            require(signatures.add(Classifier.signature(board, scenario.orders())),
                    "Duplicate scenario was generated");

        }

        OpponentScenario best = scenarios.getFirst();

        require(orderFor(best, paris).target() == Province.Bur,
                "Expected the more frequent French order");
        require(orderFor(best, berlin).target() == Province.Pru,
                "Expected the more frequent German order");

        near(best.logPreference(), Math.log(0.75 * 0.75),
                "Incorrect combined preference score");

        for (int index = 1; index < scenarios.size(); index++)
            require(scenarios.get(index - 1).logPreference()
                            >= scenarios.get(index).logPreference(),
                    "Scenarios are not ranked");

        List<UnitId> reversedUnits = new ArrayList<>(predictions.keySet());
        Collections.reverse(reversedUnits);

        Map<UnitId, RoutePrediction> reversed = new LinkedHashMap<>();

        for (UnitId unit : reversedUnits)
            reversed.put(unit, predictions.get(unit));

        require(scenarios.equals(generator.generate(board, Nation.ENGLAND, reversed)),
                "Prediction map order changed the result");

        require(new OpponentScenarios(2, 4, 2)
                        .generate(board, Nation.ENGLAND, predictions).size() == 2,
                "Scenario limit was not respected");

        Map<UnitId, RoutePrediction> missing = new HashMap<>(predictions);
        missing.remove(paris);

        require(fails(IllegalStateException.class,
                        () -> generator.generate(board, Nation.ENGLAND, missing)),
                "Missing evidence silently became an opponent hold");

        Map<UnitId, RoutePrediction> wrongIssuer = new HashMap<>(predictions);
        wrongIssuer.put(paris, prediction(Map.of(Order.hold(berlin), 1L)));

        require(fails(IllegalArgumentException.class,
                        () -> generator.generate(board, Nation.ENGLAND, wrongIssuer)),
                "Wrong prediction issuer was accepted");

        Map<String, List<Order>> adapted =
                OpponentScenarios.asOrders(board, Nation.ENGLAND, scenarios);

        require(adapted.size() == 4, "Incorrect evaluator scenario count");

        List<OpponentScenario> duplicates = new ArrayList<>(scenarios);
        duplicates.add(best);

        require(OpponentScenarios.asOrders(board, Nation.ENGLAND, duplicates).size() == 4,
                "Duplicate scenario received additional evaluation weight");

        BoardState loneNation = new BoardState(
                Map.of(london, Province.Lon),
                Map.of(Province.Lon, Nation.ENGLAND));

        List<OpponentScenario> none =
                generator.generate(loneNation, Nation.ENGLAND, Map.of());

        require(none.size() == 1 && none.getFirst().orders().isEmpty(),
                "No opponents should produce one empty scenario");

        checkPreferenceIntegration(generator);

        System.out.println("OpponentScenarios checks passed.");

    }


    private static void checkPreferenceIntegration(OpponentScenarios generator) {

        GameRecord training = trainingGame();
        RoutePreferences preferences = new RoutePreferences();
        preferences.add(training);

        // A separate board supplies the query unit identities.
        BoardState queryBoard = StandardGameFactory.create1901().board();
        GameMoment moment = new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

        List<OpponentScenario> predicted = generator.predict(
                preferences,
                RULESET,
                moment,
                queryBoard,
                Nation.ENGLAND,
                Map.of(),
                1);

        require(predicted.size() == 1, "Expected one observed opponent plan");

        for (Order order : predicted.getFirst().orders())
            require(order.orderType() == domain.OrderType.HOLD,
                    "Expected only the explicitly trained holds");

        require(fails(IllegalStateException.class, () -> generator.predict(
                        preferences,
                        RULESET,
                        moment,
                        queryBoard,
                        Nation.ENGLAND,
                        Map.of(),
                        2)),
                "Insufficient observations should stop scenario generation");

        require(fails(IllegalArgumentException.class, () -> generator.predict(
                        preferences,
                        RULESET,
                        new GameMoment(1901, GamePhase.SPRING_RETREAT),
                        queryBoard,
                        Nation.ENGLAND,
                        Map.of(),
                        1)),
                "Retreat phase was accepted");

    }

    private static GameRecord trainingGame() {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        List<Order> orders = new ArrayList<>();

        for (UnitId unit : game.board().locations().keySet())
            orders.add(Order.hold(unit));

        archive.resolveMovement(
                orders,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

    }

    private static RoutePrediction prediction(Map<Order, Long> counts) {
        return new RoutePrediction("POSITION", 0, counts);
    }

    private static UnitId unitAt(BoardState board, Province province) {

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (entry.getValue() == province)
                return entry.getKey();

        throw new IllegalArgumentException("No unit at " + province);

    }

    private static Order orderFor(OpponentScenario scenario, UnitId unit) {

        for (Order order : scenario.orders())
            if (order.unit().equals(unit))
                return order;

        throw new IllegalArgumentException("Scenario has no order for unit");

    }

    private static boolean fails(Class<? extends RuntimeException> type, Runnable action) {

        try {
            action.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return true;
            throw exception;
        }

        return false;

    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-9,
                message + ": expected " + expected + ", found " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}