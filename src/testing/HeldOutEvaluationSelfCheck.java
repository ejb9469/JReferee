package testing;

import analysis.HeldOutEvaluation;
import analysis.HeldOutReport;
import domain.Nation;
import domain.Province;
import game.Game;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.UnitId;

import java.time.Instant;
import java.util.*;


public class HeldOutEvaluationSelfCheck {

    private static final String RULESET = "held-out-test";


    private HeldOutEvaluationSelfCheck() {  }


    public static void main(String[] args) {

        GameRecord training = fixture(false, false);
        GameRecord testing = fixture(true, false);

        long unitCount = testing.initialBoard().locations().size();
        long nationCount = Nation.values().length;

        HeldOutEvaluation evaluation = new HeldOutEvaluation(4, 3, 4, 32, 1);

        HeldOutReport report = evaluation.evaluate(
                List.of(training), List.of(testing));

        require(report.trainingGames() == 1, "Wrong training count");
        require(report.testGames() == 1, "Wrong test count");
        require(report.submittedDecisions() == unitCount, "Wrong decision count");
        require(report.predictedDecisions() == unitCount, "Expected full coverage");

        require(report.topOneHits() == unitCount - 1, "Only London's order should differ");
        require(report.topKHits() == unitCount - 1, "Unseen London move should not be predicted");

        require(report.exactPredictions() == unitCount, "Expected exact starting histories");
        require(report.suffixPredictions() == 0, "Unexpected suffix fallback");
        require(report.positionPredictions() == 0, "Unexpected position fallback");

        require(report.eligibleNationalTurns() == nationCount, "Wrong national-turn count");
        require(report.generatedNationalTurns() == nationCount, "Expected plans for every nation");
        require(report.nationalPlanHits() == nationCount - 1, "Only England should miss");

        HeldOutReport sparse = new HeldOutEvaluation(4, 3, 4, 32, 2).evaluate(
                List.of(training), List.of(testing));

        require(sparse.predictedDecisions() == 0, "Insufficient evidence should give no coverage");
        require(sparse.topOneHits() == 0, "No predictions should mean no hits");
        require(sparse.generatedNationalTurns() == 0, "No evidence should mean no plans");
        require(sparse.eligibleNationalTurns() == nationCount,
                "Eligibility must not disappear when predictions are absent");

        GameRecord missingSubmission = fixture(false, true);

        HeldOutReport missing = evaluation.evaluate(
                List.of(training), List.of(missingSubmission));

        require(missing.submittedDecisions() == unitCount - 1,
                "Synthesized hold was counted as a human decision");
        require(missing.eligibleNationalTurns() == nationCount - 1,
                "England's incomplete submissions should not form a joint label");

        require(report.equals(evaluation.evaluate(List.of(training), List.of(testing))),
                "Repeated evaluation changed the metrics");

        require(rejects(() -> evaluation.evaluate(List.of(training), List.of(training))),
                "Train/test overlap was accepted");

        require(rejects(() -> evaluation.evaluate(
                        List.of(training, training), List.of(testing))),
                "Duplicate training game was accepted");

        require(rejects(() -> evaluation.evaluate(List.of(), List.of(testing))),
                "Empty training set was accepted");

        System.out.println(report);
        System.out.println("HeldOutEvaluation checks passed.");

    }


    private static GameRecord fixture(boolean moveLondon, boolean omitLondon) {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        List<Order> orders = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry : game.board().locations().entrySet()) {

            UnitId unit = entry.getKey();

            if (entry.getValue() == Province.Lon) {

                if (omitLondon)
                    continue;

                orders.add(moveLondon
                        ? Order.move(unit, Province.NTH)
                        : Order.hold(unit));

            } else {
                orders.add(Order.hold(unit));
            }

        }

        archive.resolveMovement(
                orders,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

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