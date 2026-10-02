package analysis;

import analysis.openings.Classifier;
import game.BoardState;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import domain.Nation;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


public class HeldOutEvaluation {

    private final int maximumSuffix;
    private final int topK;
    private final int choicesPerUnit;
    private final int beamWidth;
    private final long minimumObservations;


    public HeldOutEvaluation() {
        this(4, 3, 4, 32, 3);
    }

    public HeldOutEvaluation(int maximumSuffix, int topK,
                             int choicesPerUnit, int beamWidth,
                             long minimumObservations) {

        if (maximumSuffix < 0)
            throw new IllegalArgumentException("Maximum suffix must not be negative");

        if (topK < 1 || choicesPerUnit < 1 || beamWidth < 1 || minimumObservations < 1)
            throw new IllegalArgumentException("Evaluation limits must be positive");

        this.maximumSuffix = maximumSuffix;
        this.topK = topK;
        this.choicesPerUnit = choicesPerUnit;
        this.beamWidth = beamWidth;
        this.minimumObservations = minimumObservations;

    }


    public HeldOutReport evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> testGames) {

        List<GameRecord> training = List.copyOf(
                Objects.requireNonNull(trainingGames, "trainingGames"));
        List<GameRecord> testing = List.copyOf(
                Objects.requireNonNull(testGames, "testGames"));

        if (training.isEmpty() || testing.isEmpty())
            throw new IllegalArgumentException("Training and test collections must not be empty");

        requireSeparateGames(training, testing);

        // No opening filter: the held-out opening is something to predict.
        RoutePreferences preferences = new RoutePreferences(null, maximumSuffix);

        for (GameRecord game : training)
            preferences.add(game);

        JointOrders search = new JointOrders(choicesPerUnit, beamWidth);

        long submittedDecisions = 0;
        long predictedDecisions = 0;
        long topOneHits = 0;
        long topKHits = 0;

        Map<String, Long> bases = new HashMap<>();

        long eligibleNationalTurns = 0;
        long generatedNationalTurns = 0;
        long nationalPlanHits = 0;

        for (GameRecord game : testing) {

            // This extracts histories only. It never trains preferences.
            UnitRoutes testRoutes = new UnitRoutes();
            testRoutes.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

                if (!(phase instanceof MovementPhaseRecord movement))
                    continue;

                BoardState board = movement.boardBefore();

                Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();
                Map<UnitId, Order> actualOrders = new LinkedHashMap<>();

                for (UnitId unit : board.locations().keySet()) {

                    MovementResult.Outcome outcome = movement.result().outcomes().get(unit);

                    if (outcome == null)
                        throw new IllegalArgumentException("Missing movement outcome for active unit");

                    if (!outcome.submitted())
                        continue;

                    Order actual = outcome.submittedOrder();

                    if (!actual.unit().equals(unit))
                        throw new IllegalArgumentException("Actual order has the wrong issuer");

                    actualOrders.put(unit, actual);
                    submittedDecisions++;

                    List<String> history = RoutePreferences.historyBefore(
                            testRoutes.route(game.id(), unit),
                            movement.gameMoment());

                    RoutePrediction prediction = preferences.predict(
                            game.rulesetId(),
                            movement.gameMoment(),
                            board,
                            unit,
                            history,
                            minimumObservations);

                    predictions.put(unit, prediction);

                    if (prediction.counts().isEmpty())
                        continue;

                    if (!Set.of("EXACT", "SUFFIX", "POSITION").contains(prediction.basis()))
                        throw new IllegalStateException(
                                "Unexpected prediction basis: " + prediction.basis());

                    predictedDecisions++;
                    bases.merge(prediction.basis(), 1L, Math::addExact);

                    List<Order> ranked = rankedOrders(board, prediction);

                    if (ranked.getFirst().equals(actual))
                        topOneHits++;

                    if (ranked.subList(0, Math.min(topK, ranked.size())).contains(actual))
                        topKHits++;

                }

                for (Nation nation : Nation.values()) {

                    List<UnitId> units = board.locations().keySet().stream()
                            .filter(unit -> unit.owner() == nation)
                            .toList();

                    // No empty armies, missing submissions, or synthesized holds.
                    if (units.isEmpty() || !actualOrders.keySet().containsAll(units))
                        continue;

                    eligibleNationalTurns++;

                    List<OrderPlan> plans = search.generate(board, nation, predictions);

                    if (plans.isEmpty())
                        continue;

                    generatedNationalTurns++;

                    List<Order> actual = units.stream()
                            .map(actualOrders::get)
                            .toList();

                    String actualSignature = Classifier.signature(board, actual);

                    boolean found = plans.stream().anyMatch(plan ->
                            Classifier.signature(board, plan.orders()).equals(actualSignature));

                    if (found)
                        nationalPlanHits++;

                }

            }

        }

        return new HeldOutReport(
                training.size(),
                testing.size(),
                topK,
                submittedDecisions,
                predictedDecisions,
                topOneHits,
                topKHits,
                bases.getOrDefault("EXACT", 0L),
                bases.getOrDefault("SUFFIX", 0L),
                bases.getOrDefault("POSITION", 0L),
                eligibleNationalTurns,
                generatedNationalTurns,
                nationalPlanHits);

    }


    // Guard the split before constructing the training model. \\

    private static void requireSeparateGames(
            List<GameRecord> training, List<GameRecord> testing) {

        Set<UUID> ids = new HashSet<>();

        for (GameRecord game : training)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException(
                        "Repeated training game ID: " + game.id());

        for (GameRecord game : testing)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException(
                        "Repeated or overlapping test game ID: " + game.id());

    }

    private static List<Order> rankedOrders(
            BoardState board, RoutePrediction prediction) {

        return JointOrders.rankedOrders(board, prediction);

    }

}