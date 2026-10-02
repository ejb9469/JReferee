package analysis;

import analysis.openings.Classifier;
import domain.Nation;
import game.BoardState;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import phase.Order;
import phase.UnitId;

import java.io.PrintStream;
import java.util.*;


public final class YearPooledSelectionEvaluation {

    private static final int MAXIMUM_SUFFIX = 4;
    private static final int CHOICES = 4;
    private static final int NATIONAL_PLANS = 8;
    private static final int SCENARIOS = 32;
    private static final long MINIMUM = 3;


    private YearPooledSelectionEvaluation() {  }


    public static void evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output) {

        evaluate(
                trainingGames, validationGames, output,
                Experiment.YEAR_POOLED_SELECTION);

    }

    public static void evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output,
            Experiment experiment) {

        Objects.requireNonNull(experiment, "experiment");

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

        RoutePreferences preferences =
                new RoutePreferences(null, MAXIMUM_SUFFIX, true, true);

        for (int index = 0; index < training.size(); index++) {

            if (!preferences.add(training.get(index)))
                throw new IllegalStateException("Training game was not added");

            if ((index + 1) % 100 == 0 || index + 1 == training.size())
                output.printf(
                        "Training: %d/%d games%n",
                        index + 1, training.size());

        }

        JointOrders nationalSearch =
                new JointOrders(CHOICES, NATIONAL_PLANS);

        OpponentScenarios opponentSearch =
                new OpponentScenarios(CHOICES, NATIONAL_PLANS, SCENARIOS);

        Group overall = new Group();
        NavigableMap<Integer, Group> years = new TreeMap<>();

        for (int index = 0; index < validation.size(); index++) {

            GameRecord game = validation.get(index);

            UnitRoutes histories = new UnitRoutes();
            histories.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

                if (!(phase instanceof MovementPhaseRecord movement))
                    continue;

                Group annual = years.computeIfAbsent(
                        movement.gameMoment().year(), ignored -> new Group());

                evaluateMovement(
                        game, movement, histories, preferences,
                        nationalSearch, opponentSearch,
                        overall, annual, experiment);

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
        output.println(experiment == Experiment.REFERENCE_FILTERING
                ? "REFERENCE-FILTERING EXPERIMENT"
                : "YEAR-POOLED SELECTION EXPERIMENT");

        output.printf(
                "Training=%d validation=%d minimum=%d choices=%d plans=%d scenarios=%d%n",
                training.size(), validation.size(),
                MINIMUM, CHOICES, NATIONAL_PLANS, SCENARIOS);

        output.println("Reference: current predictor with both fallback tiers.");

        if (experiment == Experiment.REFERENCE_FILTERING) {

            output.println(
                    "Experiment: filter the full selected distribution before top-four truncation.");
            output.println(
                    "No year-pooled selection override, blending, fallback retry, or synthetic holds.");
            output.println(
                    "Evidence threshold is applied before filtering; surviving counts are unchanged.");
            output.println(
                    "Experimental predictions may become empty; coverage losses are allowed.");

        } else {

            output.println(
                    "Experiment: replace EXACT/SUFFIX/POSITION with qualifying year-pooled evidence.");
            output.println(
                    "No reference filtering, blending, or changes to search widths.");

        }

        output.println("The 'Selected' column means the experimental policy.");

        printGroup(output, "ALL YEARS", overall);

        for (var entry : years.entrySet())
            printGroup(output, Integer.toString(entry.getKey()), entry.getValue());

        output.println();
        output.println("Gains and losses compare the same observations.");
        output.println("Percentages use each funnel's eligible observations.");
        output.println("Current-phase labels never select distributions or generate candidates.");
        output.println("Reserved test games were not supplied to this evaluator.");

    }


    private static void evaluateMovement(
            GameRecord game,
            MovementPhaseRecord movement,
            UnitRoutes histories,
            RoutePreferences preferences,
            JointOrders nationalSearch,
            OpponentScenarios opponentSearch,
            Group overall,
            Group annual,
            Experiment experiment) {

        BoardState board = movement.boardBefore();

        Map<UnitId, RoutePrediction> reference = new LinkedHashMap<>();
        Map<UnitId, RoutePrediction> experimental = new LinkedHashMap<>();
        Map<Nation, List<UnitId>> nations = new EnumMap<>(Nation.class);

        // Select both policies before reading current-phase submissions.
        // Select both policies before reading current-phase submissions.
        for (UnitId unit : board.locations().keySet()) {

            nations.computeIfAbsent(
                    unit.owner(), ignored -> new ArrayList<>()).add(unit);

            List<String> history = RoutePreferences.historyBefore(
                    histories.route(game.id(), unit),
                    movement.gameMoment());

            RoutePrediction original = Objects.requireNonNull(
                    preferences.predict(
                            game.rulesetId(), movement.gameMoment(),
                            board, unit, history, MINIMUM));

            RoutePrediction selected;

            if (experiment == Experiment.REFERENCE_FILTERING) {

                selected = ReferenceFilteredPrediction.apply(board, original);

                if (!original.counts().keySet().containsAll(
                        selected.counts().keySet()))
                    throw new IllegalStateException(
                            "Reference filtering introduced an order");

                for (var entry : selected.counts().entrySet()) {

                    if (!entry.getValue().equals(
                            original.counts().get(entry.getKey())))
                        throw new IllegalStateException(
                                "Reference filtering changed a surviving count");

                    if (CandidateSelectionAudit.referenceIssue(
                            board, entry.getKey())
                            != CandidateSelectionAudit.ReferenceIssue.NONE)
                        throw new IllegalStateException(
                                "Reference-defective order survived filtering");

                }

            } else {

                RoutePrediction pooled = preferences.positionEvidence(
                                game.rulesetId(), movement.gameMoment(), board, unit)
                        .yearPooled();

                selected = YearPooledSelection.select(original, pooled, MINIMUM);

                if (original.counts().isEmpty() != selected.counts().isEmpty())
                    throw new IllegalStateException(
                            "Selection changed individual prediction availability");

                if (!selected.counts().keySet().containsAll(
                        original.counts().keySet()))
                    throw new IllegalStateException(
                            "Broader evidence lost an order from the original distribution");

            }

            checkBound(unit, original);
            checkBound(unit, selected);

            reference.put(unit, original);
            experimental.put(unit, selected);

        }

        Map<UnitId, Order> actual = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet()) {

            var outcome = movement.result().outcomes().get(unit);

            if (outcome == null)
                throw new IllegalArgumentException(
                        "Missing outcome for an active unit");

            if (!outcome.submitted())
                continue;

            Order order = outcome.submittedOrder();

            if (!order.unit().equals(unit))
                throw new IllegalArgumentException("Actual order has wrong issuer");

            actual.put(unit, order);

        }

        Map<UnitId, boolean[]> referenceUnits =
                unitStages(board, reference, actual);

        Map<UnitId, boolean[]> experimentalUnits =
                unitStages(board, experimental, actual);

        for (UnitId unit : actual.keySet()) {

            boolean[] before = referenceUnits.get(unit);
            boolean[] after = experimentalUnits.get(unit);

            observe(overall.units, annual.units, before, after);

            RoutePrediction original = reference.get(unit);
            RoutePrediction selected = experimental.get(unit);

            if (experiment == Experiment.REFERENCE_FILTERING) {

                Order label = actual.get(unit);

                boolean defectiveLabel =
                        CandidateSelectionAudit.referenceIssue(board, label)
                                != CandidateSelectionAudit.ReferenceIssue.NONE;

                if (before[2] && !after[2] && !defectiveLabel)
                    throw new IllegalStateException(
                            "Reference-compatible top-four label was lost");

                long removed = original.counts().size() - selected.counts().size();

                overall.removedCandidates += removed;
                annual.removedCandidates += removed;

                if (before[0] && !after[0]) {
                    overall.newlyEmpty++;
                    annual.newlyEmpty++;
                }

                if (before[1] && !after[1]) {
                    overall.removedActualLabels++;
                    annual.removedActualLabels++;
                }

                if (defectiveLabel) {
                    overall.referenceDefectiveLabels++;
                    annual.referenceDefectiveLabels++;
                }

            } else if (
                    Set.of("EXACT", "SUFFIX", "POSITION").contains(original.basis())
                            && selected.basis().equals("POSITION_YEAR_POOLED")) {

                overall.replacements.merge(original.basis(), 1L, Math::addExact);
                annual.replacements.merge(original.basis(), 1L, Math::addExact);

            }

        }

        Map<Nation, boolean[]> referenceNations = nationalStages(
                board, nations, actual, reference, referenceUnits, nationalSearch);

        Map<Nation, boolean[]> experimentalNations = nationalStages(
                board, nations, actual, experimental, experimentalUnits, nationalSearch);

        for (Nation nation : nations.keySet()) {

            if (!actual.keySet().containsAll(nations.get(nation)))
                continue;

            observe(
                    overall.nations, annual.nations,
                    referenceNations.get(nation),
                    experimentalNations.get(nation));

        }

        if (nations.size() < 2)
            return;

        for (Nation ownNation : nations.keySet()) {

            List<UnitId> opponentUnits = board.locations().keySet().stream()
                    .filter(unit -> unit.owner() != ownNation)
                    .toList();

            if (!actual.keySet().containsAll(opponentUnits)) {
                overall.incompleteOpponentLabels++;
                annual.incompleteOpponentLabels++;
                continue;
            }

            ScenarioStages before = opponentStages(
                    board, ownNation, opponentUnits, nations.keySet(),
                    actual, reference, referenceUnits,
                    referenceNations, opponentSearch);

            ScenarioStages after = opponentStages(
                    board, ownNation, opponentUnits, nations.keySet(),
                    actual, experimental, experimentalUnits,
                    experimentalNations, opponentSearch);

            observe(
                    overall.opponents, annual.opponents,
                    before.stages(), after.stages());

            overall.referenceScenarios += before.scenarios();
            overall.experimentalScenarios += after.scenarios();

            annual.referenceScenarios += before.scenarios();
            annual.experimentalScenarios += after.scenarios();

        }

    }


    private static Map<UnitId, boolean[]> unitStages(
            BoardState board,
            Map<UnitId, RoutePrediction> predictions,
            Map<UnitId, Order> actual) {

        Map<UnitId, boolean[]> result = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet()) {

            RoutePrediction prediction = predictions.get(unit);
            Order label = actual.get(unit);

            List<Order> ranked = JointOrders.rankedOrders(board, prediction);

            result.put(unit, new boolean[] {
                    !ranked.isEmpty(),
                    label != null && prediction.counts().containsKey(label),
                    label != null && ranked.subList(
                            0, Math.min(CHOICES, ranked.size())).contains(label)
            });

        }

        return result;

    }

    private static Map<Nation, boolean[]> nationalStages(
            BoardState board,
            Map<Nation, List<UnitId>> nations,
            Map<UnitId, Order> actual,
            Map<UnitId, RoutePrediction> predictions,
            Map<UnitId, boolean[]> units,
            JointOrders search) {

        Map<Nation, boolean[]> result = new EnumMap<>(Nation.class);

        for (var entry : nations.entrySet()) {

            Nation nation = entry.getKey();
            List<UnitId> nationalUnits = entry.getValue();

            boolean predicted = allUnits(nationalUnits, units, 0);
            boolean full = allUnits(nationalUnits, units, 1);
            boolean retained = allUnits(nationalUnits, units, 2);

            List<OrderPlan> plans = search.generate(board, nation, predictions);

            if (predicted != !plans.isEmpty())
                throw new IllegalStateException(
                        "National generation differs from prediction availability");

            boolean hit = false;

            if (actual.keySet().containsAll(nationalUnits)) {

                String signature = actualSignature(board, nationalUnits, actual);

                hit = plans.stream().anyMatch(plan ->
                        Classifier.signature(board, plan.orders()).equals(signature));

            }

            result.put(nation, new boolean[] {predicted, full, retained, hit});

        }

        return result;

    }

    private static ScenarioStages opponentStages(
            BoardState board,
            Nation ownNation,
            List<UnitId> opponentUnits,
            Set<Nation> activeNations,
            Map<UnitId, Order> actual,
            Map<UnitId, RoutePrediction> predictions,
            Map<UnitId, boolean[]> units,
            Map<Nation, boolean[]> nationalStages,
            OpponentScenarios search) {

        boolean predicted = allUnits(opponentUnits, units, 0);
        boolean full = allUnits(opponentUnits, units, 1);
        boolean retained = allUnits(opponentUnits, units, 2);

        boolean plans = activeNations.stream()
                .filter(nation -> nation != ownNation)
                .allMatch(nation -> nationalStages.get(nation)[3]);

        boolean hit = false;
        long generated = 0;

        if (predicted) {

            List<OpponentScenario> scenarios =
                    search.generate(board, ownNation, predictions);

            if (scenarios.isEmpty())
                throw new IllegalStateException(
                        "Complete predictions produced no scenarios");

            generated = scenarios.size();

            String signature = actualSignature(board, opponentUnits, actual);

            hit = scenarios.stream().anyMatch(scenario ->
                    Classifier.signature(board, scenario.orders()).equals(signature));

        }

        return new ScenarioStages(
                new boolean[] {predicted, full, retained, plans, hit},
                generated);

    }


    private static boolean allUnits(
            List<UnitId> units,
            Map<UnitId, boolean[]> stages,
            int stage) {

        return units.stream().allMatch(unit -> stages.get(unit)[stage]);

    }

    private static String actualSignature(
            BoardState board,
            List<UnitId> units,
            Map<UnitId, Order> actual) {

        return Classifier.signature(
                board, units.stream().map(actual::get).toList());

    }

    private static void checkBound(UnitId unit, RoutePrediction prediction) {

        for (Order order : prediction.counts().keySet())
            if (!order.unit().equals(unit))
                throw new IllegalStateException(
                        "Prediction order is not bound to its query unit");

    }

    private static void observe(
            PairedFunnel overall,
            PairedFunnel annual,
            boolean[] reference,
            boolean[] experimental) {

        overall.observe(reference, experimental);
        annual.observe(reference, experimental);

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


    private record ScenarioStages(boolean[] stages, long scenarios) {  }


    private static final class Group {

        private final PairedFunnel units = new PairedFunnel(
                "Prediction available",
                "Actual order in full distribution",
                "Actual order in top four");

        private final PairedFunnel nations = new PairedFunnel(
                "All units predicted",
                "All actual orders in distributions",
                "All actual unit choices retained",
                "Actual national plan retained");

        private final PairedFunnel opponents = new PairedFunnel(
                "All opposing units predicted",
                "All actual orders in distributions",
                "All actual unit choices retained",
                "All actual national plans retained",
                "Actual opponent scenario retained");

        private final Map<String, Long> replacements = new TreeMap<>();

        private long referenceScenarios;
        private long experimentalScenarios;
        private long incompleteOpponentLabels;

        private long removedCandidates;
        private long newlyEmpty;
        private long removedActualLabels;
        private long referenceDefectiveLabels;

    }


    private static final class PairedFunnel {

        private final String[] labels;
        private final long[] reference;
        private final long[] experimental;
        private final long[] gains;
        private final long[] losses;

        private long eligible;

        private PairedFunnel(String... labels) {

            this.labels = labels;
            reference = new long[labels.length];
            experimental = new long[labels.length];
            gains = new long[labels.length];
            losses = new long[labels.length];

        }

        private void observe(boolean[] before, boolean[] after) {

            if (before.length != labels.length || after.length != labels.length)
                throw new IllegalArgumentException("Wrong stage count");

            requireFunnel(before);
            requireFunnel(after);

            eligible++;

            for (int index = 0; index < labels.length; index++) {

                if (before[index]) reference[index]++;
                if (after[index]) experimental[index]++;

                if (!before[index] && after[index]) gains[index]++;
                if (before[index] && !after[index]) losses[index]++;

            }

        }

        private static void requireFunnel(boolean[] stages) {

            boolean previous = true;

            for (boolean stage : stages) {

                if (stage && !previous)
                    throw new IllegalStateException(
                            "Downstream hit without upstream availability");

                previous = stage;

            }

        }

        private void print(PrintStream output, String title) {

            output.println();
            output.println(title + " — eligible=" + eligible);

            output.printf(
                    "%-37s %10s %10s %8s %8s %8s%n",
                    "Stage", "Reference", "Selected", "Gains", "Losses", "Delta");

            for (int index = 0; index < labels.length; index++) {

                long delta = experimental[index] - reference[index];

                if (delta != gains[index] - losses[index])
                    throw new IllegalStateException("Paired counts disagree");

                output.printf(
                        "%-37s %10d %10d %8d %8d %+8d%n",
                        labels[index],
                        reference[index],
                        experimental[index],
                        gains[index],
                        losses[index],
                        delta);

            }

            int last = labels.length - 1;

            output.printf(
                    "Final-stage rate: reference=%s selected=%s%n",
                    percent(reference[last], eligible),
                    percent(experimental[last], eligible));

        }

    }


    private static void printGroup(
            PrintStream output, String title, Group group) {

        output.println();
        output.println("=== " + title + " ===");

        group.units.print(output, "UNIT DECISIONS");
        group.nations.print(output, "NATIONAL TURNS");
        group.opponents.print(output, "OPPONENT PERSPECTIVES");

        output.println("Submitted decisions switched by original basis: "
                + group.replacements);

        output.printf(
                "Generated scenarios: reference=%d selected=%d%n",
                group.referenceScenarios, group.experimentalScenarios);

        output.printf(
                "Incomplete opponent-label perspectives excluded=%d%n",
                group.incompleteOpponentLabels);

        output.printf(
                "Filtering audit, submitted decisions only: "
                        + "removed candidate occurrences=%d; newly empty=%d; "
                        + "removed represented actual labels=%d; "
                        + "reference-defective submitted labels=%d%n",
                group.removedCandidates,
                group.newlyEmpty,
                group.removedActualLabels,
                group.referenceDefectiveLabels);

    }

    private static String percent(long numerator, long denominator) {

        return denominator == 0
                ? "n/a"
                : String.format(
                Locale.ROOT, "%.2f%%", 100.0 * numerator / denominator);

    }


    // private helper enum
    public enum Experiment {
        YEAR_POOLED_SELECTION,
        REFERENCE_FILTERING
    }


}