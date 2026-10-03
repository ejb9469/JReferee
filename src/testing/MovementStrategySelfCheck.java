package testing;

import analysis.JointOrders;
import analysis.MovementStrategy;
import analysis.OpponentScenarios;
import analysis.OutcomeEvaluator;
import analysis.PredictionPolicy;
import analysis.RoutePreferences;
import analysis.RoutePrediction;
import analysis.PlanEvaluation;
import analysis.CoordinationMode;
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
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;


public final class MovementStrategySelfCheck {

    private static final String RULESET = "movement-strategy-self-check";
    private static final GameMoment SPRING_1901 =
            new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

    private MovementStrategySelfCheck() {  }


    public static void main(String[] args) {

        List<GameRecord> training = trainingGames(
                StandardGameFactory.create1901().board(),
                unit -> Order.hold(unit),
                3);
        RoutePreferences preferences = new RoutePreferences();
        training.forEach(preferences::add);

        Game query = StandardGameFactory.create1901();
        BoardState board = query.board();
        Map<UnitId, Province> before = new LinkedHashMap<>(board.locations());
        int modelGames = preferences.gameCount();
        MovementStrategy.Configuration configuration = new MovementStrategy.Configuration(
                PredictionPolicy.ORIGINAL, 3, 4, 8, 32);
        OutcomeEvaluator evaluator = new OutcomeEvaluator(1.0, 3.0, 0.5);
        MovementProcessor processor = processor();

        MovementStrategy.Recommendation recommendation = MovementStrategy.recommend(
                preferences, RULESET, SPRING_1901, board, Nation.ENGLAND,
                Map.of(), Map.of(Province.NTH, 1.0),
                configuration, evaluator, processor);

        require(recommendation.status() == MovementStrategy.Status.RANKED,
                "Expected the trained synthetic fixture to be rankable");
        require(recommendation.policy() == PredictionPolicy.ORIGINAL,
                "The chosen policy was not reported");
        require(recommendation.candidatePlanCount() > 0
                        && recommendation.opponentScenarioCount() > 0,
                "Candidate/scenario counts were not exposed");
        require(recommendation.evidence().values().stream()
                        .allMatch(value -> value.selectedBasis().equals("POSITION")),
                "An absent history must explicitly request position evidence");

        Map<UnitId, RoutePrediction> directPredictions =
                predictions(preferences, board);
        List<analysis.OrderPlan> directCandidates =
                new JointOrders(4, 8).generate(
                        board, Nation.ENGLAND, directPredictions);
        List<analysis.OpponentScenario> directScenarios =
                new OpponentScenarios(4, 8, 32).generate(
                        board, Nation.ENGLAND, directPredictions);
        List<PlanEvaluation> directRanking = evaluator.rank(
                board, SPRING_1901, directCandidates,
                OpponentScenarios.asOrders(board, Nation.ENGLAND, directScenarios),
                Map.of(Province.NTH, 1.0), processor, directPredictions);

        require(recommendation.rankedPlans().equals(directRanking),
                "Orchestration differs from direct component invocation");
        require(board.locations().equals(before),
                "Recommendation mutated the caller's board");
        require(preferences.gameCount() == modelGames,
                "Recommendation mutated the trained model");
        require(query.year() == 1901 && query.phase() == GamePhase.SPRING_MOVEMENT,
                "Recommendation advanced the caller's game");

        checkExplicitAbstentions(training, preferences, board);
        checkEmptyAndInvalidInputs();
        checkCoordinationIntegration();

        System.out.println("MovementStrategy checks passed.");

    }

    private static void checkCoordinationIntegration() {
        BoardState board = StandardGameFactory.create1901().board();
        UnitId london = unitAt(board, Province.Lon);
        UnitId liverpool = unitAt(board, Province.Lvp);
        Map<UnitId, Province> before = new LinkedHashMap<>(board.locations());
        RoutePreferences model = new RoutePreferences();
        trainingGames(board, unit -> unit.equals(london)
                ? Order.supportMove(london, Province.Lvp, Province.Yor)
                : unit.equals(liverpool) ? Order.move(liverpool, Province.Wal)
                : Order.hold(unit), 3).forEach(model::add);

        MovementStrategy.Configuration strict = new MovementStrategy.Configuration(
                PredictionPolicy.ORIGINAL, 1, 2, 1, 1, CoordinationMode.STRICT);
        MovementStrategy.Recommendation rejected = recommend(model, board, strict);
        require(rejected.status() == MovementStrategy.Status.OWN_COORDINATION_REJECTED
                        && rejected.rankedPlans().isEmpty()
                        && rejected.coordination().rejectedCandidates() > 0,
                "Coherence rejection was confused with missing evidence or ranked");
        require(recommend(model, board, new MovementStrategy.Configuration(
                        PredictionPolicy.ORIGINAL, 1, 2, 1, 1)).hasRecommendation(),
                "Legacy strategy default no longer ranks raw historical combinations");

        trainingGames(board, unit -> unit.equals(london)
                ? Order.supportMove(london, Province.Lvp, Province.Yor)
                : unit.equals(liverpool) ? Order.move(liverpool, Province.Yor)
                : Order.hold(unit), 1).forEach(model::add);
        int games = model.gameCount();
        MovementStrategy.Recommendation limited = recommend(model, board,
                new MovementStrategy.Configuration(
                        PredictionPolicy.ORIGINAL, 1, 1, 1, 1, CoordinationMode.STRICT));
        require(limited.status() == MovementStrategy.Status.OWN_COORDINATION_LIMIT_REACHED
                        && limited.coordination().omittedChoices() == 1
                        && limited.diagnostic().contains("within current limits"),
                "Cutoff exhaustion made a false evidence or impossibility claim");
        MovementStrategy.Recommendation coherent = recommend(model, board, strict);
        require(coherent.hasRecommendation()
                        && coherent.coordination().mode() == CoordinationMode.STRICT
                        && coherent.opponentScenarioCount() == 1,
                "Coherent plan did not reach outcome ranking");
        require(coherent.rankedPlans().getFirst().plan().orders()
                        .contains(Order.move(liverpool, Province.Yor)),
                "Ranking returned an incoherent own plan");
        require(board.locations().equals(before) && model.gameCount() == games,
                "Strict recommendation mutated board or trained evidence");
        MovementStrategy.Recommendation missing = recommend(new RoutePreferences(), board, strict);
        require(missing.status() == MovementStrategy.Status.OWN_EVIDENCE_INSUFFICIENT
                        && missing.coordination().mode() == CoordinationMode.STRICT,
                "Missing evidence lost selected coordination mode");

        UnitId foreign = unitAt(board, Province.Bre);
        BoardState conditionalBoard = board;
        RoutePreferences conditionalModel = new RoutePreferences();
        trainingGames(conditionalBoard, unit -> unit.equals(london)
                ? Order.supportMove(london, Province.Bre, Province.ENG)
                : unit.equals(foreign) ? Order.move(foreign, Province.ENG)
                : Order.hold(unit), 1)
                .forEach(conditionalModel::add);
        require(recommend(conditionalModel, conditionalBoard, strict).status()
                        == MovementStrategy.Status.OWN_COORDINATION_REJECTED,
                "Strict orchestration silently assumed foreign support target's move");
        MovementStrategy.Recommendation conditional = recommend(conditionalModel, conditionalBoard,
                new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL,
                        1, 2, 1, 1, CoordinationMode.CONDITIONAL));
        require(conditional.hasRecommendation()
                        && !conditional.coordination().conditionalPlans().isEmpty(),
                "Conditional scenario ranking lost foreign assumptions");
    }

    private static MovementStrategy.Recommendation recommend(
            RoutePreferences model, BoardState board, MovementStrategy.Configuration configuration) {
        return MovementStrategy.recommend(model, RULESET, SPRING_1901, board,
                Nation.ENGLAND, Map.of(), Map.of(), configuration,
                new OutcomeEvaluator(1.0, 3.0, 0.5), processor());
    }


    private static void checkExplicitAbstentions(
            List<GameRecord> training,
            RoutePreferences preferences,
            BoardState board) {

        MovementStrategy.Configuration original = new MovementStrategy.Configuration(
                PredictionPolicy.ORIGINAL, 3, 4, 8, 32);
        OutcomeEvaluator evaluator = new OutcomeEvaluator(1.0, 3.0, 0.5);
        MovementProcessor processor = processor();

        MovementStrategy.Recommendation noEvidence = MovementStrategy.recommend(
                new RoutePreferences(), RULESET, SPRING_1901, board,
                Nation.ENGLAND, Map.of(), Map.of(), original,
                evaluator, processor);
        require(noEvidence.status()
                        == MovementStrategy.Status.OWN_EVIDENCE_INSUFFICIENT,
                "Missing own-unit evidence did not abstain explicitly");

        MovementStrategy.Recommendation noOpponentEvidence = MovementStrategy.recommend(
                onlyEnglandPreferences(board), RULESET, SPRING_1901, board,
                Nation.ENGLAND, Map.of(), Map.of(), original,
                evaluator, processor);
        require(noOpponentEvidence.status()
                        == MovementStrategy.Status.OPPONENT_EVIDENCE_INSUFFICIENT,
                "Missing opponent evidence did not abstain explicitly");
        require(!noOpponentEvidence.hasRecommendation(),
                "Missing opponent evidence produced an apparent recommendation");

        GameRecord first = training.getFirst();
        UnitId london = unitAt(board, Province.Lon);
        Map<UnitId, List<String>> currentTurnHistory = new HashMap<>();

        for (UnitId unit : board.locations().keySet())
            currentTurnHistory.put(unit, preferences.route(first.id(), unit));

        require(rejects(() -> MovementStrategy.recommend(
                        preferences, RULESET, SPRING_1901, board, Nation.ENGLAND,
                        currentTurnHistory, Map.of(), original, evaluator, processor)),
                "Current-turn route observations were accepted as history");

        currentTurnHistory.clear();
        currentTurnHistory.put(new UnitId(
                UUID.randomUUID(), Nation.FRANCE,
                domain.UnitType.ARMY, Province.Par), List.of());
        require(rejects(() -> MovementStrategy.recommend(
                        preferences, RULESET, SPRING_1901, board, Nation.ENGLAND,
                        currentTurnHistory, Map.of(), original, evaluator, processor)),
                "An unknown history issuer was accepted");

        require(london.owner() == Nation.ENGLAND,
                "Fixture unit identity changed unexpectedly");

    }


    private static void checkEmptyAndInvalidInputs() {

        BoardState standard = StandardGameFactory.create1901().board();
        Map<UnitId, Province> frenchUnits = new LinkedHashMap<>();
        standard.locations().forEach((unit, province) -> {
            if (unit.owner() == Nation.FRANCE)
                frenchUnits.put(unit, province);
        });
        BoardState loneNation = new BoardState(frenchUnits, standard.owners());
        List<GameRecord> records = trainingGames(
                standard, Order::hold, 3);
        RoutePreferences preferences = new RoutePreferences();
        records.forEach(preferences::add);

        MovementStrategy.Configuration configuration = new MovementStrategy.Configuration(
                PredictionPolicy.ORIGINAL, 3, 4, 8, 32, CoordinationMode.STRICT);
        MovementStrategy.Recommendation noOpponents = MovementStrategy.recommend(
                preferences, RULESET, SPRING_1901, loneNation, Nation.FRANCE,
                Map.of(), Map.of(), configuration,
                new OutcomeEvaluator(1.0, 3.0, 0.5), processor());

        require(noOpponents.status() == MovementStrategy.Status.RANKED
                        && noOpponents.opponentScenarioCount() == 1,
                "Zero opponents must use one complete empty-order scenario");

        MovementStrategy.Recommendation absentNation = MovementStrategy.recommend(
                preferences, RULESET, SPRING_1901, loneNation, Nation.ENGLAND,
                Map.of(), Map.of(), configuration,
                new OutcomeEvaluator(1.0, 3.0, 0.5), processor());

        require(absentNation.status() == MovementStrategy.Status.NATION_ABSENT,
                "A nation without active units must not receive a fabricated plan");

        require(rejects(() -> MovementStrategy.recommend(
                        preferences, RULESET,
                        new GameMoment(1901, GamePhase.SPRING_RETREAT),
                        loneNation, Nation.ENGLAND, Map.of(), Map.of(),
                        configuration, new OutcomeEvaluator(1.0, 3.0, 0.5), processor())),
                "A retreat phase was accepted");

        List<GameRecord> filteredTraining = trainingGames(
                StandardGameFactory.create1901().board(),
                unit -> unit.owner() == Nation.ENGLAND
                        && unit.origin() == Province.Lon
                        ? Order.supportHold(unit, Province.Gal)
                        : Order.hold(unit),
                3);
        RoutePreferences filteredModel = new RoutePreferences();
        filteredTraining.forEach(filteredModel::add);

        MovementStrategy.Recommendation filtered = MovementStrategy.recommend(
                filteredModel, RULESET, SPRING_1901,
                StandardGameFactory.create1901().board(), Nation.ENGLAND,
                Map.of(), Map.of(),
                new MovementStrategy.Configuration(
                        PredictionPolicy.REFERENCE_FILTERED, 3, 4, 8, 32, CoordinationMode.STRICT),
                new OutcomeEvaluator(1.0, 3.0, 0.5), processor());

        require(filtered.status()
                        == MovementStrategy.Status.OWN_CANDIDATES_FILTERED,
                "All-filtered own evidence must return explicit insufficiency");

    }


    private static RoutePreferences onlyEnglandPreferences(BoardState board) {
        RoutePreferences preferences = new RoutePreferences();
        trainingGames(board, unit -> unit.owner() == Nation.ENGLAND
                        ? Order.hold(unit) : null,
                3).forEach(preferences::add);
        return preferences;
    }


    private static Map<UnitId, RoutePrediction> predictions(
            RoutePreferences preferences,
            BoardState board) {

        Map<UnitId, RoutePrediction> result = new LinkedHashMap<>();
        for (UnitId unit : board.locations().keySet())
            result.put(unit, preferences.predict(
                    RULESET, SPRING_1901, board, unit, List.of(), 3));
        return result;

    }


    private static List<GameRecord> trainingGames(
            BoardState board,
            Function<UnitId, Order> orderFor,
            int count) {

        List<GameRecord> records = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            Game game = new Game(
                    1901, board,
                    processor(), new RetreatProcessor(), new AdjustmentProcessor());
            GameRecordBuilder archive = new GameRecordBuilder(
                    UUID.randomUUID(), RULESET, game);
            List<Order> orders = board.locations().keySet().stream()
                    .map(orderFor)
                    .filter(Objects::nonNull)
                    .toList();

            archive.resolveMovement(
                    orders,
                    new Moment(1901, GamePhase.SPRING_MOVEMENT,
                            Instant.ofEpochSecond(index)));
            records.add(archive.build());
        }

        return List.copyOf(records);

    }


    private static UnitId unitAt(BoardState board, Province province) {
        return board.locations().entrySet().stream()
                .filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }


    private static MovementProcessor processor() {
        return new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 1L);
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
