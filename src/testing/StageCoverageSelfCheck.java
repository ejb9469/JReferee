package testing;

import analysis.StageCoverageEvaluation;
import domain.Province;
import game.Game;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.*;


public final class StageCoverageSelfCheck {

    private StageCoverageSelfCheck() {  }


    public static void main(String[] args) {

        GameRecord holds = fixture(false);
        GameRecord moves = fixture(true);
        GameRecord validation = fixture(true);

        long units = holds.initialBoard().locations().size();
        long nations = holds.initialBoard().locations().keySet().stream()
                .map(unit -> unit.owner()).distinct().count();

        StageCoverageEvaluation evaluation =
                new StageCoverageEvaluation(4, 4, 8, 32, 1);

        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {

            var report = evaluation.evaluate(
                    List.of(holds), List.of(validation), quiet);

            require(report.unitCounts().equals(
                            List.of(units, units, units - 1, units - 1)),
                    "Unexpected unit funnel");

            require(report.nationalCounts().equals(
                            List.of(nations, nations,
                                    nations - 1, nations - 1, nations - 1)),
                    "Unexpected national funnel");

            require(report.opponentCounts().equals(
                            List.of(nations, nations, 1L, 1L, 1L, 1L)),
                    "Only England should match all opponent orders");

            require(report.generatedScenarios() == nations,
                    "Expected one scenario per perspective");

            var alternatives = evaluation.evaluate(
                    List.of(holds, moves), List.of(validation), quiet);

            require(alternatives.unitCounts().equals(
                            List.of(units, units, units, units)),
                    "Both London choices should be represented");

            require(alternatives.nationalCounts().equals(
                            List.of(nations, nations, nations, nations, nations)),
                    "Actual national plans should all be retained");

            require(alternatives.opponentCounts().equals(
                            List.of(nations, nations, nations,
                                    nations, nations, nations)),
                    "Actual opponent scenarios should all be retained");

            require(alternatives.generatedScenarios() == 1 + 2 * (nations - 1),
                    "Wrong number of alternative scenarios");

            var sparse = new StageCoverageEvaluation(4, 4, 8, 32, 3)
                    .evaluate(List.of(holds, moves), List.of(validation), quiet);

            require(sparse.unitCounts().equals(
                            List.of(units, 0L, 0L, 0L)),
                    "Insufficient evidence should prevent prediction");

            require(sparse.noPositionContext() == 0,
                    "Contexts exist in this fixture");

            require(sparse.belowThreshold() == units,
                    "Every fixture context should be below the cutoff");

            boolean overlapRejected = false;

            try {
                evaluation.evaluate(List.of(holds), List.of(holds), quiet);
            } catch (IllegalArgumentException expected) {
                overlapRejected = true;
            }

            require(overlapRejected, "Train/validation overlap was accepted");

            report.print(System.out);

        }

        System.out.println();
        System.out.println("StageCoverage checks passed.");

    }


    private static GameRecord fixture(boolean moveLondon) {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), "stage-coverage-fixture", game);

        List<Order> orders = new ArrayList<>();

        for (var entry : game.board().locations().entrySet()) {

            orders.add(moveLondon && entry.getValue() == Province.Lon
                    ? Order.move(entry.getKey(), Province.NTH)
                    : Order.hold(entry.getKey()));

        }

        archive.resolveMovement(
                orders,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new AssertionError(message);

    }

}