package testing;

import analysis.OutcomeEvaluator;
import analysis.OrderPlan;
import analysis.PlanEvaluation;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.util.*;


public class OutcomeEvaluatorSelfCheck {

    private OutcomeEvaluatorSelfCheck() {  }


    public static void main(String[] args) {

        UnitId french = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(
                UUID.randomUUID(), Nation.GERMANY, UnitType.ARMY, Province.Bur);

        BoardState board = new BoardState(
                Map.of(french, Province.Par, german, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE));

        GameMoment moment = new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

        OrderPlan advance = new OrderPlan(
                Nation.FRANCE, List.of(Order.move(french, Province.Pic)), Math.log(0.4));
        OrderPlan hold = new OrderPlan(
                Nation.FRANCE, List.of(Order.hold(french)), Math.log(0.6));

        Map<String, List<Order>> scenarios = new LinkedHashMap<>();
        scenarios.put("Germany holds", List.of(Order.hold(german)));
        scenarios.put("Germany contests Picardy", List.of(Order.move(german, Province.Pic)));

        Map<Province, Double> objectives = Map.of(Province.Pic, 2.0);

        OutcomeEvaluator evaluator = new OutcomeEvaluator(1, 3, 0.5);

        List<PlanEvaluation> ranked = evaluator.rank(
                board, moment, List.of(hold, advance), scenarios,
                objectives, new MovementProcessor());

        require(ranked.size() == 2, "Expected two evaluated plans");

        PlanEvaluation best = ranked.getFirst();

        require(best.plan().equals(advance), "Advance should rank first");
        near(best.mean(), 1, "Incorrect mean");
        near(best.worst(), 0, "Incorrect worst case");
        near(best.score(), 0.5, "Incorrect caution-adjusted score");

        near(best.scenarioScores().get("Germany holds"), 2,
                "Uncontested advance should reach the objective");
        near(best.scenarioScores().get("Germany contests Picardy"), 0,
                "Bounce should not reach the objective");

        near(ranked.getLast().score(), 0, "Holding should leave position value unchanged");

        List<PlanEvaluation> cautious = new OutcomeEvaluator(1, 3, 1).rank(
                board, moment, List.of(advance, hold), scenarios,
                objectives, new MovementProcessor());

        // Both plans have worst-case value zero; preference breaks the tie.
        require(cautious.getFirst().plan().equals(hold),
                "Equal outcome scores should use human preference as a tie-breaker");

        Map<String, List<Order>> reversed = new LinkedHashMap<>();
        reversed.put("Germany contests Picardy", scenarios.get("Germany contests Picardy"));
        reversed.put("Germany holds", scenarios.get("Germany holds"));

        List<PlanEvaluation> repeated = evaluator.rank(
                board, moment, List.of(advance, hold), reversed,
                objectives, new MovementProcessor());

        require(ranked.equals(repeated), "Input ordering changed the ranking");

        require(board.locationOf(french) == Province.Par,
                "Evaluation mutated the board");

        require(rejects(() -> new OutcomeEvaluator(1, 3, 1.1)),
                "Invalid caution was accepted");

        require(rejects(() -> evaluator.rank(
                        board, moment, List.of(advance), Map.of(),
                        objectives, new MovementProcessor())),
                "Empty scenario set was accepted");

        require(rejects(() -> evaluator.rank(
                        board, new GameMoment(1901, GamePhase.SPRING_RETREAT),
                        List.of(advance), scenarios, objectives, new MovementProcessor())),
                "Retreat phase was accepted");

        require(rejects(() -> evaluator.rank(
                        board, moment, List.of(advance),
                        Map.of("Missing opponent orders", List.of()),
                        objectives, new MovementProcessor())),
                "Incomplete scenario was accepted");

        System.out.println("OutcomeEvaluator checks passed.");

    }


    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-9,
                message + ": expected " + expected + ", found " + actual);
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