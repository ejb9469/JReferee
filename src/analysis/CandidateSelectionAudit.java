package analysis;

import game.BoardState;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import phase.Order;
import phase.UnitId;

import java.io.PrintStream;
import java.util.*;


public final class CandidateSelectionAudit {

    private static final long MINIMUM_OBSERVATIONS = 3;
    private static final int CHOICES = 4;


    private CandidateSelectionAudit() {  }


    public static void evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output) {

        List<GameRecord> training = List.copyOf(
                Objects.requireNonNull(trainingGames, "trainingGames"));

        List<GameRecord> validation = List.copyOf(
                Objects.requireNonNull(validationGames, "validationGames"));

        Objects.requireNonNull(output, "output");

        if (training.isEmpty() || validation.isEmpty())
            throw new IllegalArgumentException(
                    "Training and validation must not be empty");

        requireSeparateGames(training, validation);

        long started = System.nanoTime();

        // Experimental reference: original tiers + both fallback tiers.
        RoutePreferences preferences =
                new RoutePreferences(null, 4, true, true);

        output.println("Training candidate-audit reference model...");

        for (int index = 0; index < training.size(); index++) {

            if (!preferences.add(training.get(index)))
                throw new IllegalStateException("Training game was not added");

            if ((index + 1) % 100 == 0 || index + 1 == training.size())
                output.printf(
                        "Training: %d/%d games%n",
                        index + 1, training.size());

        }

        Stats overall = new Stats();
        Map<String, Stats> byBasis = new TreeMap<>();
        Map<Integer, Stats> byYear = new TreeMap<>();

        Map<String, AlternativeStats> alternatives = new TreeMap<>();

        long omittedSubmissions = 0;

        for (int index = 0; index < validation.size(); index++) {

            GameRecord game = validation.get(index);

            UnitRoutes histories = new UnitRoutes();
            histories.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

                if (!(phase instanceof MovementPhaseRecord movement))
                    continue;

                BoardState board = movement.boardBefore();

                for (UnitId unit : board.locations().keySet()) {

                    var outcome = movement.result().outcomes().get(unit);

                    if (outcome == null)
                        throw new IllegalArgumentException(
                                "Missing movement outcome for active unit");

                    if (!outcome.submitted()) {
                        omittedSubmissions++;
                        continue;
                    }

                    List<String> history = RoutePreferences.historyBefore(
                            histories.route(game.id(), unit),
                            movement.gameMoment());

                    // Prediction and transformation do not receive the actual order.
                    RoutePrediction selected = Objects.requireNonNull(
                            preferences.predict(
                                    game.rulesetId(),
                                    movement.gameMoment(),
                                    board,
                                    unit,
                                    history,
                                    MINIMUM_OBSERVATIONS),
                            "Missing prediction");

                    List<Order> ranked =
                            JointOrders.rankedOrders(board, selected);

                    for (Order order : ranked)
                        if (!order.unit().equals(unit))
                            throw new IllegalStateException(
                                    "Prediction has the wrong issuer");

                    List<Order> filtered = referenceCompatibleOrders(board, ranked);

                    Order actual = outcome.submittedOrder();

                    if (!actual.unit().equals(unit))
                        throw new IllegalArgumentException(
                                "Actual order has the wrong issuer");

                    Decision decision = new Decision(
                            !ranked.isEmpty(),
                            ranked.contains(actual),
                            topContains(ranked, actual),
                            !filtered.isEmpty(),
                            filtered.contains(actual),
                            topContains(filtered, actual),
                            referenceIssue(board, actual),
                            ranked.size() - filtered.size());

                    overall.observe(decision);

                    byBasis.computeIfAbsent(
                                    selected.basis(), ignored -> new Stats())
                            .observe(decision);

                    byYear.computeIfAbsent(
                                    movement.gameMoment().year(), ignored -> new Stats())
                            .observe(decision);

                    var evidence = preferences.positionEvidence(
                            game.rulesetId(),
                            movement.gameMoment(),
                            board,
                            unit);

                    /*
                     * Inspect only less-specific position tiers.
                     * This does not inspect every shorter suffix distribution.
                     */
                    switch (selected.basis()) {

                        case "EXACT", "SUFFIX" -> {
                            compareAlternative(
                                    alternatives, board, selected, evidence.yearSpecific(),
                                    actual, ranked);
                            compareAlternative(
                                    alternatives, board, selected, evidence.yearPooled(),
                                    actual, ranked);
                            compareAlternative(
                                    alternatives, board, selected, evidence.occupancyRelaxed(),
                                    actual, ranked);
                        }

                        case "POSITION" -> {
                            compareAlternative(
                                    alternatives, board, selected, evidence.yearPooled(),
                                    actual, ranked);
                            compareAlternative(
                                    alternatives, board, selected, evidence.occupancyRelaxed(),
                                    actual, ranked);
                        }

                        case "POSITION_YEAR_POOLED" ->
                                compareAlternative(
                                        alternatives, board, selected,
                                        evidence.occupancyRelaxed(), actual, ranked);

                        case "POSITION_OCCUPANCY_RELAXED", "NONE" -> {
                            // No broader tier is defined in this experiment.
                        }

                        default -> throw new IllegalStateException(
                                "Unexpected prediction basis: " + selected.basis());

                    }

                }

            }

            output.printf(
                    Locale.ROOT,
                    "Validation: %d/%d games; elapsed %.1f s%n",
                    index + 1,
                    validation.size(),
                    (System.nanoTime() - started) / 1_000_000_000.0);

            output.flush();

        }

        output.println();
        output.println("PAIRED CANDIDATE SELECTION AUDIT");
        output.printf(
                "Training=%d validation=%d minimum=%d top-K=%d%n",
                training.size(), validation.size(),
                MINIMUM_OBSERVATIONS, CHOICES);

        output.printf(
                "Active-unit decisions with omitted submissions excluded: %d%n",
                omittedSubmissions);

        printStats(output, "ALL", overall);

        output.println();
        output.println("BY SELECTED BASIS");

        for (var entry : byBasis.entrySet())
            printStats(output, entry.getKey(), entry.getValue());

        output.println();
        output.println("BY MOVEMENT YEAR");

        for (var entry : byYear.entrySet())
            printStats(output, Integer.toString(entry.getKey()), entry.getValue());

        output.println();
        output.println("PAIRED BROADER-DISTRIBUTION AUDIT");
        output.println(
                "Each row uses the same decisions for selected and alternative evidence.");
        output.println(
                "Alternatives must independently meet minimum observations.");
        output.println(
                "Rows overlap: a decision can be compared against several alternatives.");

        for (var entry : alternatives.entrySet()) {

            AlternativeStats stats = entry.getValue();

            output.println();
            output.println(entry.getKey());

            output.printf(
                    "  Decisions=%d; full-distribution hits: selected=%d alternative=%d%n",
                    stats.decisions, stats.selectedFull, stats.alternativeFull);

            output.printf(
                    "  Full-distribution gains=%d losses=%d%n",
                    stats.fullGains, stats.fullLosses);

            output.printf(
                    "  Top-%d hits: selected=%d alternative=%d; gains=%d losses=%d%n",
                    CHOICES,
                    stats.selectedTop, stats.alternativeTop,
                    stats.topGains, stats.topLosses);

        }

        output.println();
        output.println("Reference filtering is counterfactual and does not mutate the model.");
        output.println("All submitted labels remain eligible, including reference-defective labels.");
        output.println("No national or opponent beams were regenerated in this audit.");
        output.println("Candidate-reference checks do not establish geographical or tactical legality.");
        output.println("Reserved test games were not supplied to this audit.");

    }


    public enum ReferenceIssue {
        NONE,
        MISSING_UNIT,
        CONVOY_WITHOUT_ARMY
    }


    public static ReferenceIssue referenceIssue(
            BoardState board, Order order) {
        return ReferenceIssue.valueOf(
                ReferenceCompatibility.issue(board, order).name());

    }

    public static List<Order> referenceCompatibleOrders(
            BoardState board, List<Order> ranked) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(ranked, "ranked");

        // Preserve production ranking. Filter before any top-K truncation.
        return ReferenceCompatibility.compatibleOrders(board, ranked);

    }

    private static boolean topContains(List<Order> ranked, Order actual) {

        return ranked.subList(0, Math.min(CHOICES, ranked.size()))
                .contains(actual);

    }


    private static void compareAlternative(
            Map<String, AlternativeStats> comparisons,
            BoardState board,
            RoutePrediction selected,
            RoutePrediction alternative,
            Order actual,
            List<Order> selectedRanking) {

        if (alternative.observations() < MINIMUM_OBSERVATIONS)
            return;

        String key = selected.basis() + " -> " + alternative.basis();

        AlternativeStats stats =
                comparisons.computeIfAbsent(key, ignored -> new AlternativeStats());

        List<Order> alternativeRanking =
                JointOrders.rankedOrders(board, alternative);

        boolean selectedFull = selected.counts().containsKey(actual);
        boolean alternativeFull = alternative.counts().containsKey(actual);

        boolean selectedTop = topContains(selectedRanking, actual);
        boolean alternativeTop = topContains(alternativeRanking, actual);

        stats.decisions++;

        if (selectedFull) stats.selectedFull++;
        if (alternativeFull) stats.alternativeFull++;
        if (!selectedFull && alternativeFull) stats.fullGains++;
        if (selectedFull && !alternativeFull) stats.fullLosses++;

        if (selectedTop) stats.selectedTop++;
        if (alternativeTop) stats.alternativeTop++;
        if (!selectedTop && alternativeTop) stats.topGains++;
        if (selectedTop && !alternativeTop) stats.topLosses++;

    }


    private record Decision(
            boolean predicted,
            boolean full,
            boolean top,
            boolean filteredPredicted,
            boolean filteredFull,
            boolean filteredTop,
            ReferenceIssue actualIssue,
            long removedCandidates
    ) {  }


    private static final class Stats {

        private long decisions;
        private long predicted;
        private long full;
        private long top;

        private long filteredPredicted;
        private long filteredFull;
        private long filteredTop;

        private long gains;
        private long losses;
        private long newlyEmpty;
        private long removedActualLabels;
        private long removedCandidates;

        private long actualMissingUnit;
        private long actualConvoyWithoutArmy;

        private void observe(Decision decision) {

            if ((decision.full() && !decision.predicted())
                    || (decision.top() && !decision.full())
                    || (decision.filteredPredicted() && !decision.predicted())
                    || (decision.filteredFull() && !decision.full())
                    || (decision.filteredTop() && !decision.filteredFull()))
                throw new IllegalStateException("Inconsistent candidate audit");

            if (decision.top() && !decision.filteredTop()
                    && decision.actualIssue() == ReferenceIssue.NONE)
                throw new IllegalStateException(
                        "Reference-compatible top-K label was lost");

            decisions++;

            if (decision.predicted()) predicted++;
            if (decision.full()) full++;
            if (decision.top()) top++;

            if (decision.filteredPredicted()) filteredPredicted++;
            if (decision.filteredFull()) filteredFull++;
            if (decision.filteredTop()) filteredTop++;

            if (!decision.top() && decision.filteredTop()) gains++;
            if (decision.top() && !decision.filteredTop()) losses++;

            if (decision.predicted() && !decision.filteredPredicted()) newlyEmpty++;
            if (decision.full() && !decision.filteredFull()) removedActualLabels++;

            removedCandidates = Math.addExact(
                    removedCandidates, decision.removedCandidates());

            if (decision.actualIssue() == ReferenceIssue.MISSING_UNIT)
                actualMissingUnit++;

            if (decision.actualIssue() == ReferenceIssue.CONVOY_WITHOUT_ARMY)
                actualConvoyWithoutArmy++;

        }

    }

    private static final class AlternativeStats {

        private long decisions;

        private long selectedFull;
        private long alternativeFull;
        private long fullGains;
        private long fullLosses;

        private long selectedTop;
        private long alternativeTop;
        private long topGains;
        private long topLosses;

    }


    private static void printStats(
            PrintStream output, String label, Stats stats) {

        output.println();
        output.println(label);

        output.printf(
                "  Eligible=%d predicted=%d fullHits=%d topHits=%d%n",
                stats.decisions, stats.predicted, stats.full, stats.top);

        output.printf(
                "  Selected distribution misses among predictions=%d; "
                        + "top-K truncation losses=%d%n",
                stats.predicted - stats.full,
                stats.full - stats.top);

        output.printf(
                "  Filtered predicted=%d fullHits=%d topHits=%d%n",
                stats.filteredPredicted, stats.filteredFull, stats.filteredTop);

        output.printf(
                "  Paired top-K gains=%d losses=%d net=%+d; newlyEmpty=%d%n",
                stats.gains, stats.losses,
                stats.filteredTop - stats.top, stats.newlyEmpty);

        output.printf(
                "  Removed actual labels present in original distributions=%d%n",
                stats.removedActualLabels);

        output.printf(
                "  Reference-defective submitted labels: missing-unit=%d "
                        + "convoy-without-army=%d%n",
                stats.actualMissingUnit, stats.actualConvoyWithoutArmy);

        output.printf(
                "  Removed candidate occurrences across full distributions=%d%n",
                stats.removedCandidates);

    }

    private static void requireSeparateGames(
            List<GameRecord> training, List<GameRecord> validation) {

        Set<UUID> ids = new HashSet<>();

        for (GameRecord game : training)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException(
                        "Repeated training game ID: " + game.id());

        for (GameRecord game : validation)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException(
                        "Repeated or overlapping validation game ID: " + game.id());

    }

}