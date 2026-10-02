package analysis;

import analysis.openings.Classifier;
import domain.Nation;
import game.BoardState;
import game.GameMoment;
import phase.Order;
import phase.UnitId;

import java.util.*;


public class OpponentScenarios {

    private final JointOrders nationalSearch;
    private final int scenarioLimit;


    public OpponentScenarios() {
        this(4, 8, 32);
    }

    public OpponentScenarios(int choicesPerUnit, int plansPerNation, int scenarioLimit) {

        if (scenarioLimit < 1)
            throw new IllegalArgumentException("Scenario limit must be positive");

        this.nationalSearch = new JointOrders(choicesPerUnit, plansPerNation);
        this.scenarioLimit = scenarioLimit;

    }


    // No current-turn orders are accepted by this prediction entry point. \\

    public List<OpponentScenario> predict(
            RoutePreferences preferences,
            String ruleset,
            GameMoment moment,
            BoardState board,
            Nation ownNation,
            Map<UnitId, List<String>> histories,
            long minimumObservations) {

        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(ownNation, "ownNation");
        Objects.requireNonNull(histories, "histories");

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException("Opponent scenarios support movement phases only");

        if (minimumObservations < 1)
            throw new IllegalArgumentException("Minimum observations must be positive");

        Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();

        for (UnitId unit : opponentUnits(board, ownNation)) {

            // An absent history explicitly requests position-based prediction.
            List<String> history = histories.containsKey(unit)
                    ? Objects.requireNonNull(histories.get(unit), "unit history")
                    : List.of();

            RoutePrediction prediction = preferences.predict(
                    ruleset,
                    moment,
                    board,
                    unit,
                    history,
                    minimumObservations);

            predictions.put(unit, prediction);

        }

        return generate(board, ownNation, predictions);

    }


    // Also accepts precomputed predictions for testing and reuse. \\

    public List<OpponentScenario> generate(
            BoardState board,
            Nation ownNation,
            Map<UnitId, RoutePrediction> predictions) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(ownNation, "ownNation");
        Objects.requireNonNull(predictions, "predictions");

        List<UnitId> opponents = opponentUnits(board, ownNation);
        List<String> missing = new ArrayList<>();

        for (UnitId unit : opponents) {

            RoutePrediction prediction = predictions.get(unit);

            if (prediction == null || prediction.counts().isEmpty())
                missing.add(unit.owner().name() + " "
                        + unit.unitType().name() + " "
                        + board.locationOf(unit).name());

        }

        if (!missing.isEmpty())
            throw new IllegalStateException(
                    "Insufficient opponent evidence for: " + String.join(", ", missing));

        Comparator<OpponentScenario> ranking = Comparator
                .comparingDouble(OpponentScenario::logPreference).reversed()
                .thenComparing(scenario ->
                        Classifier.signature(board, scenario.orders()));

        List<OpponentScenario> beam =
                List.of(new OpponentScenario(List.of(), 0));

        for (Nation nation : Nation.values()) {

            if (nation == ownNation)
                continue;

            boolean active = opponents.stream().anyMatch(unit -> unit.owner() == nation);

            if (!active)
                continue;

            List<OrderPlan> plans = nationalSearch.generate(board, nation, predictions);

            if (plans.isEmpty())
                throw new IllegalStateException("No complete opponent plans for " + nation);

            Map<String, OpponentScenario> expanded = new TreeMap<>();

            for (OpponentScenario partial : beam) {

                for (OrderPlan plan : plans) {

                    List<Order> orders = new ArrayList<>(partial.orders());
                    orders.addAll(plan.orders());

                    OpponentScenario candidate = new OpponentScenario(
                            orders,
                            partial.logPreference() + plan.logPreference());

                    String signature = Classifier.signature(board, orders);
                    OpponentScenario previous = expanded.get(signature);

                    if (previous == null
                            || candidate.logPreference() > previous.logPreference())
                        expanded.put(signature, candidate);

                }

            }

            List<OpponentScenario> ranked = new ArrayList<>(expanded.values());
            ranked.sort(ranking);

            beam = List.copyOf(
                    ranked.subList(0, Math.min(scenarioLimit, ranked.size())));

        }

        for (OpponentScenario scenario : beam)
            requireComplete(board, ownNation, scenario);

        return beam;

    }


    // Adapter for the existing equally weighted OutcomeEvaluator. \\

    public static Map<String, List<Order>> asOrders(
            BoardState board,
            Nation ownNation,
            Collection<OpponentScenario> scenarios) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(ownNation, "ownNation");
        Objects.requireNonNull(scenarios, "scenarios");

        Map<String, List<Order>> result = new LinkedHashMap<>();
        Set<String> signatures = new HashSet<>();

        for (OpponentScenario scenario : scenarios) {

            Objects.requireNonNull(scenario, "scenario");
            requireComplete(board, ownNation, scenario);

            String signature = Classifier.signature(board, scenario.orders());

            // Duplicate scenarios must not receive extra evaluation weight.
            if (signatures.add(signature))
                result.put("Scenario " + (result.size() + 1), scenario.orders());

        }

        return Collections.unmodifiableMap(result);

    }


    private static List<UnitId> opponentUnits(BoardState board, Nation ownNation) {

        List<UnitId> units = new ArrayList<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() != ownNation)
                units.add(unit);

        units.sort(
                Comparator.comparing(UnitId::owner)
                        .thenComparing(unit -> board.locationOf(unit).name())
                        .thenComparing(UnitId::unitType));

        return units;

    }

    private static void requireComplete(
            BoardState board, Nation ownNation, OpponentScenario scenario) {

        Set<UnitId> expected = new HashSet<>(opponentUnits(board, ownNation));
        Set<UnitId> actual = new HashSet<>();

        for (Order order : scenario.orders()) {

            if (order.owner() == ownNation)
                throw new IllegalArgumentException(
                        "Opponent scenario contains an order for " + ownNation);

            if (!actual.add(order.unit()))
                throw new IllegalArgumentException("Duplicate scenario issuer");

        }

        if (!actual.equals(expected))
            throw new IllegalArgumentException(
                    "Scenario must cover exactly all active opponent units");

        // Checks issuers and order structure, not tactical success or legality.
        Classifier.signature(board, scenario.orders());

    }

}