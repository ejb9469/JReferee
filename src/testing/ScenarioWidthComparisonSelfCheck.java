package testing;

import analysis.ScenarioCoverageReport;
import analysis.ScenarioWidthComparison;
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


public class ScenarioWidthComparisonSelfCheck {

    private static final String RULESET = "scenario-width-test";


    private ScenarioWidthComparisonSelfCheck() {  }


    public static void main(String[] args) {

        GameRecord holding = fixture(false);
        GameRecord moving = fixture(true);
        GameRecord validation = fixture(true);

        List<GameRecord> trainingGames = List.of(holding, moving);
        List<GameRecord> validationGames = List.of(validation);

        ScenarioWidthComparison comparison =
                new ScenarioWidthComparison(4, 2, 4, 1);

        NavigableMap<Integer, ScenarioCoverageReport> reports = comparison.compare(
                trainingGames, validationGames, 8, 1, 2);

        require(new ArrayList<>(reports.keySet()).equals(List.of(1, 2, 8)),
                "Widths were not returned in ascending order");

        long nations = Nation.values().length;

        ScenarioCoverageReport narrow = reports.get(1);
        ScenarioCoverageReport wide = reports.get(2);
        ScenarioCoverageReport wider = reports.get(8);

        require(narrow.generatedScenarios() == nations,
                "Width one should retain one scenario per perspective");

        require(wide.generatedScenarios() == 1 + 2 * (nations - 1),
                "Width two should retain both London alternatives for opposing nations");

        require(wide.scenarioHits() == nations,
                "Width two should cover every actual opponent scenario");

        require(narrow.scenarioHits() <= wide.scenarioHits(),
                "Unexpected recall ordering in the fixture");

        require(wide.equals(wider),
                "Extra width should not invent alternatives");

        require(reports.equals(comparison.compare(
                        List.of(moving, holding), validationGames, 1, 2, 8)),
                "Training order changed the results");

        ScenarioWidthComparison sparseComparison =
                new ScenarioWidthComparison(4, 2, 4, 3);

        NavigableMap<Integer, ScenarioCoverageReport> sparse = sparseComparison.compare(
                trainingGames, validationGames, 1, 8);

        for (ScenarioCoverageReport report : sparse.values()) {
            require(report.generatedPerspectives() == 0,
                    "Insufficient evidence should prevent generation");
            require(report.generatedScenarios() == 0,
                    "Width must not manufacture evidence");
            require(report.insufficientEvidence() == nations,
                    "Incorrect missing-evidence count");
        }

        require(rejects(() -> comparison.compare(
                        trainingGames, validationGames, 1, 1)),
                "Duplicate widths were accepted");

        require(rejects(() -> comparison.compare(
                        trainingGames, validationGames, 0)),
                "Zero width was accepted");

        require(rejects(() -> comparison.compare(
                        trainingGames, validationGames)),
                "Empty width selection was accepted");

        require(rejects(() -> comparison.compare(
                        trainingGames, List.of(holding), 1)),
                "Training/validation overlap was accepted");

        comparison.printReport(reports, System.out);
        System.out.println();
        System.out.println("ScenarioWidthComparison checks passed.");

    }


    private static GameRecord fixture(boolean moveLondon) {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        List<Order> orders = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry : game.board().locations().entrySet()) {

            UnitId unit = entry.getKey();

            orders.add(moveLondon && entry.getValue() == Province.Lon
                    ? Order.move(unit, Province.NTH)
                    : Order.hold(unit));

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