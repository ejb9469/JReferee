package testing;

import analysis.FourPolicyEvaluation;
import analysis.PredictionPolicy;
import domain.Province;
import game.Game;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.*;


public final class FourPolicyEvaluationSelfCheck {

    private static final String RULESET = "four-policy-self-check";
    private static final List<Province> EMPTY_SUPPORT_TARGETS =
            List.of(Province.Gal, Province.Pic, Province.Pie, Province.Naf);

    private FourPolicyEvaluationSelfCheck() {  }


    public static void main(String[] args) {

        var board = StandardGameFactory.create1901().board();
        UnitId london = unitAt(board, Province.Lon);
        UnitId brest = unitAt(board, Province.Bre);
        List<GameRecord> training = new ArrayList<>();

        for (Province target : EMPTY_SUPPORT_TARGETS)
            for (int repeat = 0; repeat < 5; repeat++)
                training.add(trainingGame(board, london, brest,
                        Order.supportHold(london, target)));

        for (int repeat = 0; repeat < 3; repeat++)
            training.add(trainingGame(board, london, brest,
                    Order.hold(london)));

        GameRecord gain = validationGame(board, london, brest,
                Order.hold(london), true);
        GameRecord loss = validationGame(board, london, brest,
                Order.supportHold(london, Province.Gal), true);
        GameRecord omitted = validationGame(board, london, brest,
                Order.hold(london), false);

        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        FourPolicyEvaluation.Report report = FourPolicyEvaluation.evaluate(
                training, List.of(gain, loss, omitted),
                new PrintStream(printed));

        require(report.configuration().policies().equals(List.of(
                        PredictionPolicy.ORIGINAL,
                        PredictionPolicy.REFERENCE_FILTERED,
                        PredictionPolicy.YEAR_POOLED_SELECTION,
                        PredictionPolicy.YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED)),
                "The report does not name all four policies in factorial order");
        require(report.byYear().containsKey(1901),
                "The structured report omitted year-specific metrics");
        require(report.overall().comparisons().size() == 6,
                "Expected every pair of policy comparisons");

        FourPolicyEvaluation.PolicyMetrics original =
                report.overall().policies().get(PredictionPolicy.ORIGINAL);
        FourPolicyEvaluation.PolicyMetrics filtered =
                report.overall().policies().get(PredictionPolicy.REFERENCE_FILTERED);
        FourPolicyEvaluation.PolicyMetrics pooled =
                report.overall().policies().get(PredictionPolicy.YEAR_POOLED_SELECTION);
        FourPolicyEvaluation.PolicyMetrics combined =
                report.overall().policies().get(
                        PredictionPolicy.YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED);

        require(filtered.audit().newlyEmptyPredictions() == 3,
                "Filtering should expose the three all-defective Brest predictions: "
                        + filtered.audit());
        require(filtered.audit().predictionDenominator() == 66,
                "Newly-empty predictions have the wrong denominator");
        require(filtered.audit().removedRepresentedActualLabels() == 3,
                "Filtering losses must be counted against each policy's pre-filter evidence");
        require(filtered.audit().representedActualLabelsBeforeFiltering() == 65,
                "Represented-label denominator should exclude only the omitted submission");
        require(filtered.audit().defectiveSubmittedLabels() == 3,
                "Submitted support/convoy reference defects were not counted");
        require(filtered.audit().removedCandidateOccurrences() == 15,
                "Candidate removals must count policy-specific pre-filter entries");
        require(combined.audit().removedRepresentedActualLabels() == 3,
                "Combined-policy removals must compare D against C, not A");
        require(pooled.audit().selectionBasisTransitions()
                        .get("EXACT -> POSITION_YEAR_POOLED") == 66,
                "Year-pooled source transitions were not retained");

        FourPolicyEvaluation.ComparisonMetrics originalVsFiltered =
                report.overall().comparisons().get(new FourPolicyEvaluation.PolicyPair(
                        PredictionPolicy.ORIGINAL, PredictionPolicy.REFERENCE_FILTERED));
        require(originalVsFiltered.units().gains().get(2) > 0
                        && originalVsFiltered.units().losses().get(2) > 0,
                "Reference filtering fixture must contain unit-level gains and losses");
        require(originalVsFiltered.nations().gains().get(3) > 0
                        && originalVsFiltered.nations().losses().get(3) > 0,
                "Reference filtering fixture must contain national-plan gains and losses");
        require(originalVsFiltered.opponents().gains().get(4) > 0
                        && originalVsFiltered.opponents().losses().get(4) > 0,
                "Reference filtering fixture must contain scenario gains and losses");
        require(combined.generatedScenarios() < original.generatedScenarios(),
                "Filtering should suppress scenarios when an opponent becomes unsupported");

        for (var comparison : report.overall().comparisons().values()) {
            checkDeltas(comparison.units());
            checkDeltas(comparison.nations());
            checkDeltas(comparison.opponents());
        }

        String output = printed.toString();
        require(output.contains("YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED")
                        && output.contains("gains=")
                        && output.contains("losses="),
                "Printed output must expose policy names and paired gains/losses");

        require(rejects(() -> FourPolicyEvaluation.evaluate(
                        List.of(training.getFirst(), training.getFirst()),
                        List.of(gain), new PrintStream(new ByteArrayOutputStream()))),
                "Duplicate training IDs were accepted");
        require(rejects(() -> FourPolicyEvaluation.evaluate(
                        List.of(training.getFirst()), List.of(training.getFirst()),
                        new PrintStream(new ByteArrayOutputStream()))),
                "Overlapping train/validation IDs were accepted");

        System.out.println("FourPolicyEvaluation checks passed.");

    }


    private static void checkDeltas(FourPolicyEvaluation.StageComparison comparison) {
        for (int index = 0; index < comparison.stages().size(); index++)
            require(comparison.net().get(index)
                            == comparison.gains().get(index) - comparison.losses().get(index),
                    "Paired delta does not equal gains minus losses");
    }


    private static GameRecord trainingGame(
            game.BoardState board,
            UnitId london,
            UnitId brest,
            Order londonOrder) {

        List<Order> orders = board.locations().keySet().stream()
                .map(unit -> unit.equals(london) ? londonOrder
                        : unit.equals(brest) ? Order.convoy(brest, Province.Edi, Province.Nwy)
                        : Order.hold(unit))
                .toList();

        return record(board, orders);

    }


    private static GameRecord validationGame(
            game.BoardState board,
            UnitId london,
            UnitId brest,
            Order londonOrder,
            boolean submitBrest) {

        List<Order> orders = new ArrayList<>();

        for (UnitId unit : board.locations().keySet()) {
            if (unit.equals(london))
                orders.add(londonOrder);
            else if (unit.equals(brest)) {
                if (submitBrest)
                    orders.add(Order.convoy(brest, Province.Edi, Province.Nwy));
            } else {
                orders.add(Order.hold(unit));
            }
        }

        return record(board, orders);

    }


    private static GameRecord record(
            game.BoardState board,
            List<Order> orders) {

        Game game = new Game(
                1901, board, processor(),
                new RetreatProcessor(), new AdjustmentProcessor());
        GameRecordBuilder builder = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);
        builder.resolveMovement(
                orders,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));
        return builder.build();

    }


    private static UnitId unitAt(game.BoardState board, Province province) {
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
