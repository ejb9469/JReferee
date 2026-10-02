package analysis;

import analysis.openings.Classifier;
import domain.Nation;
import game.BoardState;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


public class ScenarioCoverageEvaluation {

    private final int maximumSuffix;
    private final int choicesPerUnit;
    private final int plansPerNation;
    private final int scenarioLimit;
    private final long minimumObservations;


    public ScenarioCoverageEvaluation() {
        this(4, 4, 8, 32, 3);
    }

    public ScenarioCoverageEvaluation(
            int maximumSuffix, int choicesPerUnit, int plansPerNation,
            int scenarioLimit, long minimumObservations) {

        if (maximumSuffix < 0)
            throw new IllegalArgumentException("Maximum suffix must not be negative");

        if (choicesPerUnit < 1 || plansPerNation < 1
                || scenarioLimit < 1 || minimumObservations < 1)
            throw new IllegalArgumentException("Evaluation limits must be positive");

        this.maximumSuffix = maximumSuffix;
        this.choicesPerUnit = choicesPerUnit;
        this.plansPerNation = plansPerNation;
        this.scenarioLimit = scenarioLimit;
        this.minimumObservations = minimumObservations;

    }


    public ScenarioCoverageReport evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> testGames) {

        List<GameRecord> training = List.copyOf(
                Objects.requireNonNull(trainingGames, "trainingGames"));
        List<GameRecord> testing = List.copyOf(
                Objects.requireNonNull(testGames, "testGames"));

        if (training.isEmpty() || testing.isEmpty())
            throw new IllegalArgumentException("Training and test collections must not be empty");

        requireSeparateGames(training, testing);

        RoutePreferences preferences = new RoutePreferences(null, maximumSuffix);

        for (GameRecord game : training)
            preferences.add(game);

        OpponentScenarios generator = new OpponentScenarios(
                choicesPerUnit, plansPerNation, scenarioLimit);

        long perspectives = 0;
        long incompleteLabels = 0;
        long eligiblePerspectives = 0;
        long generatedPerspectives = 0;
        long generatedScenarios = 0;
        long topOneHits = 0;
        long scenarioHits = 0;

        for (GameRecord game : testing) {

            // Extract test histories separately; never train on them.
            UnitRoutes testRoutes = new UnitRoutes();
            testRoutes.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

                if (!(phase instanceof MovementPhaseRecord movement))
                    continue;

                BoardState board = movement.boardBefore();

                Set<Nation> activeNations = EnumSet.noneOf(Nation.class);

                for (UnitId unit : board.locations().keySet())
                    activeNations.add(unit.owner());

                // No opponent prediction problem remains with fewer than two nations.
                if (activeNations.size() < 2)
                    continue;

                Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();

                for (UnitId unit : board.locations().keySet()) {

                    List<String> history = RoutePreferences.historyBefore(
                            testRoutes.route(game.id(), unit),
                            movement.gameMoment());

                    predictions.put(unit, preferences.predict(
                            game.rulesetId(),
                            movement.gameMoment(),
                            board,
                            unit,
                            history,
                            minimumObservations));

                }

                // Labels are read only after predictions have been made.
                Map<UnitId, Order> actual = submittedOrders(movement);

                for (Nation ownNation : activeNations) {

                    perspectives++;

                    List<UnitId> opponents = board.locations().keySet().stream()
                            .filter(unit -> unit.owner() != ownNation)
                            .toList();

                    if (!actual.keySet().containsAll(opponents)) {
                        incompleteLabels++;
                        continue;
                    }

                    eligiblePerspectives++;

                    boolean evidenceAvailable = opponents.stream().allMatch(unit ->
                            predictions.containsKey(unit)
                                    && !predictions.get(unit).counts().isEmpty());

                    if (!evidenceAvailable)
                        continue;

                    // Programming errors still propagate; do not catch every failure
                    // and misreport it as insufficient evidence.
                    List<OpponentScenario> scenarios =
                            generator.generate(board, ownNation, predictions);

                    if (scenarios.isEmpty())
                        throw new IllegalStateException(
                                "Complete opponent predictions produced no scenarios");

                    generatedPerspectives++;
                    generatedScenarios = Math.addExact(generatedScenarios, scenarios.size());

                    List<Order> actualOpponentOrders = opponents.stream()
                            .map(actual::get)
                            .toList();

                    String actualSignature =
                            Classifier.signature(board, actualOpponentOrders);

                    int matchingIndex = -1;

                    for (int index = 0; index < scenarios.size(); index++) {

                        String predictedSignature = Classifier.signature(
                                board, scenarios.get(index).orders());

                        if (predictedSignature.equals(actualSignature)) {
                            matchingIndex = index;
                            break;
                        }

                    }

                    if (matchingIndex >= 0)
                        scenarioHits++;

                    if (matchingIndex == 0)
                        topOneHits++;

                }

            }

        }

        return new ScenarioCoverageReport(
                training.size(),
                testing.size(),
                perspectives,
                incompleteLabels,
                eligiblePerspectives,
                generatedPerspectives,
                generatedScenarios,
                topOneHits,
                scenarioHits);

    }


    private static Map<UnitId, Order> submittedOrders(MovementPhaseRecord phase) {

        Map<UnitId, Order> actual = new LinkedHashMap<>();

        for (UnitId unit : phase.boardBefore().locations().keySet()) {

            MovementResult.Outcome outcome = phase.result().outcomes().get(unit);

            if (outcome == null)
                throw new IllegalArgumentException("Missing outcome for active unit");

            if (!outcome.submittedOrder().unit().equals(unit))
                throw new IllegalArgumentException("Outcome contains a different order issuer");

            if (outcome.submitted())
                actual.put(unit, outcome.submittedOrder());

        }

        return actual;

    }

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

}