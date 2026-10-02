package testing;

import analysis.ScenarioCoverageEvaluation;
import analysis.ScenarioCoverageReport;
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


public class ScenarioCoverageSelfCheck {

    private static final String RULESET = "scenario-coverage-test";


    private ScenarioCoverageSelfCheck() {  }


    public static void main(String[] args) {

        GameRecord holdingTraining = fixture(false, false);
        GameRecord movingTraining = fixture(true, false);
        GameRecord testing = fixture(true, false);

        long nations = Nation.values().length;

        ScenarioCoverageEvaluation evaluator =
                new ScenarioCoverageEvaluation(4, 2, 4, 8, 1);

        ScenarioCoverageReport report = evaluator.evaluate(
                List.of(holdingTraining), List.of(testing));

        require(report.trainingGames() == 1, "Wrong training count");
        require(report.testGames() == 1, "Wrong test count");
        require(report.perspectives() == nations, "Expected one perspective per nation");
        require(report.incompleteLabels() == 0, "Labels should be complete");
        require(report.eligiblePerspectives() == nations, "Wrong eligible count");
        require(report.generatedPerspectives() == nations, "Expected full scenario coverage");
        require(report.generatedScenarios() == nations, "Expected one scenario per perspective");

        require(report.scenarioHits() == 1,
                "Only England should correctly predict every opponent");
        require(report.topOneHits() == 1, "Expected one top-scenario hit");

        ScenarioCoverageReport alternatives = evaluator.evaluate(
                List.of(holdingTraining, movingTraining), List.of(testing));

        require(alternatives.scenarioHits() == nations,
                "Both observed London choices should cover every actual opponent scenario");

        require(alternatives.generatedScenarios() == 1 + 2 * (nations - 1),
                "England needs one opponent scenario; other nations need two");

        ScenarioCoverageReport reordered = evaluator.evaluate(
                List.of(movingTraining, holdingTraining), List.of(testing));

        require(alternatives.equals(reordered),
                "Training input order changed evaluation results");

        ScenarioCoverageReport narrow = new ScenarioCoverageEvaluation(4, 2, 4, 1, 1)
                .evaluate(List.of(holdingTraining, movingTraining), List.of(testing));

        require(narrow.generatedScenarios() == nations, "Scenario limit was not respected");
        require(narrow.scenarioHits() <= alternatives.scenarioHits(),
                "Narrowing the beam unexpectedly increased recall");

        ScenarioCoverageReport sparse = new ScenarioCoverageEvaluation(4, 2, 4, 8, 2)
                .evaluate(List.of(holdingTraining), List.of(testing));

        require(sparse.eligiblePerspectives() == nations,
                "Insufficient evidence must not change label eligibility");
        require(sparse.generatedPerspectives() == 0,
                "Insufficient evidence should prevent scenario generation");
        require(sparse.insufficientEvidence() == nations,
                "Missing-evidence count is incorrect");
        require(sparse.scenarioHits() == 0, "No scenarios should mean no hits");

        GameRecord missingLondon = fixture(false, true);

        ScenarioCoverageReport incomplete = evaluator.evaluate(
                List.of(holdingTraining), List.of(missingLondon));

        require(incomplete.perspectives() == nations, "Wrong perspective count");
        require(incomplete.incompleteLabels() == nations - 1,
                "Missing London affects every perspective except England's");
        require(incomplete.eligiblePerspectives() == 1,
                "England's opponent labels should remain complete");
        require(incomplete.scenarioHits() == 1,
                "England should still predict the explicit opponent holds");

        require(report.equals(evaluator.evaluate(
                        List.of(holdingTraining), List.of(testing))),
                "Repeated evaluation changed the report");

        require(rejects(() -> evaluator.evaluate(
                        List.of(holdingTraining), List.of(holdingTraining))),
                "Train/test overlap was accepted");

        require(rejects(() -> evaluator.evaluate(
                        List.of(holdingTraining, holdingTraining), List.of(testing))),
                "Duplicate training archive was accepted");

        require(rejects(() -> evaluator.evaluate(
                        List.of(holdingTraining), List.of(testing, testing))),
                "Duplicate test archive was accepted");

        require(rejects(() -> evaluator.evaluate(List.of(), List.of(testing))),
                "Empty training collection was accepted");

        System.out.println(report);
        System.out.println("ScenarioCoverage checks passed.");

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