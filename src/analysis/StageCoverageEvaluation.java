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


public final class StageCoverageEvaluation {

    private final int maximumSuffix;
    private final int choicesPerUnit;
    private final int plansPerNation;
    private final int scenarioLimit;
    private final long minimumObservations;
    private final boolean yearPooledFallback;


    public StageCoverageEvaluation() {
        this(4, 4, 8, 32, 3, false);
    }

    public StageCoverageEvaluation(
            int maximumSuffix,
            int choicesPerUnit,
            int plansPerNation,
            int scenarioLimit,
            long minimumObservations) {

        this(
                maximumSuffix, choicesPerUnit, plansPerNation,
                scenarioLimit, minimumObservations, false);

    }

    public StageCoverageEvaluation(
            int maximumSuffix,
            int choicesPerUnit,
            int plansPerNation,
            int scenarioLimit,
            long minimumObservations,
            boolean yearPooledFallback) {

        if (maximumSuffix < 0)
            throw new IllegalArgumentException(
                    "Maximum suffix must not be negative");

        if (choicesPerUnit < 1 || plansPerNation < 1
                || scenarioLimit < 1 || minimumObservations < 1)
            throw new IllegalArgumentException(
                    "Evaluation limits must be positive");

        this.maximumSuffix = maximumSuffix;
        this.choicesPerUnit = choicesPerUnit;
        this.plansPerNation = plansPerNation;
        this.scenarioLimit = scenarioLimit;
        this.minimumObservations = minimumObservations;
        this.yearPooledFallback = yearPooledFallback;

    }


    public Report evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream progress) {

        List<GameRecord> training = List.copyOf(
                Objects.requireNonNull(trainingGames, "trainingGames"));
        List<GameRecord> validation = List.copyOf(
                Objects.requireNonNull(validationGames, "validationGames"));

        Objects.requireNonNull(progress, "progress");

        if (training.isEmpty() || validation.isEmpty())
            throw new IllegalArgumentException(
                    "Training and validation must not be empty");

        requireSeparateGames(training, validation);

        long started = System.nanoTime();

        progress.println("Prediction policy: "
                + (yearPooledFallback
                ? "baseline + final cross-year POSITION fallback"
                : "baseline"));

        RoutePreferences preferences =
                new RoutePreferences(null, maximumSuffix, yearPooledFallback);

        for (int index = 0; index < training.size(); index++) {

            if (!preferences.add(training.get(index)))
                throw new IllegalStateException("Training game was not added");

            if ((index + 1) % 100 == 0 || index + 1 == training.size())
                progress.printf(
                        "Training: %d/%d games%n", index + 1, training.size());

        }

        JointOrders nationalSearch =
                new JointOrders(choicesPerUnit, plansPerNation);

        OpponentScenarios opponentSearch =
                new OpponentScenarios(
                        choicesPerUnit, plansPerNation, scenarioLimit);

        Bucket overall = new Bucket();
        NavigableMap<Integer, Bucket> years = new TreeMap<>();

        for (int index = 0; index < validation.size(); index++) {

            GameRecord game = validation.get(index);

            UnitRoutes histories = new UnitRoutes();
            histories.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

                if (!(phase instanceof MovementPhaseRecord movement))
                    continue;

                int year = movement.gameMoment().year();
                Bucket annual = years.computeIfAbsent(year, ignored -> new Bucket());

                evaluateMovement(
                        game, movement, histories, preferences,
                        nationalSearch, opponentSearch, overall, annual);

            }

            progress.printf(
                    Locale.ROOT,
                    "Validation: %d/%d games; elapsed %.1f s%n",
                    index + 1,
                    validation.size(),
                    (System.nanoTime() - started) / 1_000_000_000.0);

            progress.flush();

        }

        return new Report(
                training.size(), validation.size(),
                choicesPerUnit, plansPerNation, scenarioLimit,
                minimumObservations, overall, years);

    }


    private void evaluateMovement(
            GameRecord game,
            MovementPhaseRecord movement,
            UnitRoutes histories,
            RoutePreferences preferences,
            JointOrders nationalSearch,
            OpponentScenarios opponentSearch,
            Bucket overall,
            Bucket annual) {

        BoardState board = movement.boardBefore();

        Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();
        Map<UnitId, RoutePrediction> positionPredictions = new LinkedHashMap<>();

        Map<Nation, List<UnitId>> nationalUnits = new EnumMap<>(Nation.class);

        /*
         * Build both maps for every active unit before reading actual orders.
         * The selected prediction drives candidate generation.
         * The position-only prediction is used exclusively for the audit.
         */
        for (UnitId unit : board.locations().keySet()) {

            nationalUnits.computeIfAbsent(
                    unit.owner(), ignored -> new ArrayList<>()).add(unit);

            List<String> history = RoutePreferences.historyBefore(
                    histories.route(game.id(), unit),
                    movement.gameMoment());

            RoutePrediction selected = Objects.requireNonNull(
                    preferences.predict(
                            game.rulesetId(),
                            movement.gameMoment(),
                            board,
                            unit,
                            history,
                            minimumObservations),
                    "RoutePreferences.predict returned null for " + unit);

            predictions.put(unit, selected);

            RoutePrediction positionOnly = Objects.requireNonNull(
                    preferences.predict(
                            game.rulesetId(),
                            movement.gameMoment(),
                            board,
                            unit,
                            List.of(),
                            1),
                    "Position prediction returned null for " + unit);

            /*
             * Keep the audit year-specific. With minimum 1, reaching pooled
             * fallback means the year-specific position context was absent.
             */
            if (positionOnly.basis().equals("POSITION_YEAR_POOLED"))
                positionOnly = new RoutePrediction("NONE", 0, Map.of());

            positionPredictions.put(unit, positionOnly);

        }

        if (!predictions.keySet().equals(board.locations().keySet())
                || !positionPredictions.keySet().equals(board.locations().keySet()))
            throw new IllegalStateException(
                    "Prediction maps must cover every active unit");

        Map<UnitId, Order> actual = new LinkedHashMap<>();
        Map<UnitId, UnitFacts> unitFacts = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet()) {

            var outcome = movement.result().outcomes().get(unit);

            if (outcome == null)
                throw new IllegalArgumentException(
                        "Missing movement outcome for active unit");

            RoutePrediction prediction = predictions.get(unit);

            for (Order order : prediction.counts().keySet())
                if (!order.unit().equals(unit))
                    throw new IllegalStateException(
                            "Prediction is not bound to its query unit");

            boolean predicted = !prediction.counts().isEmpty();

            if (!outcome.submitted()) {

                unitFacts.put(unit,
                        new UnitFacts(false, predicted, false, false));

                continue;

            }

            Order label = outcome.submittedOrder();

            if (!label.unit().equals(unit))
                throw new IllegalArgumentException("Actual order has the wrong issuer");

            actual.put(unit, label);

            boolean inDistribution = prediction.counts().containsKey(label);
            boolean retained = topContains(
                    board, prediction, label, choicesPerUnit);

            UnitFacts facts =
                    new UnitFacts(true, predicted, inDistribution, retained);

            unitFacts.put(unit, facts);

            overall.units.observe(predicted, inDistribution, retained);
            annual.units.observe(predicted, inDistribution, retained);

            audit(
                    overall, board, prediction,
                    positionPredictions.get(unit), label);

            audit(
                    annual, board, prediction,
                    positionPredictions.get(unit), label);

        }

        Map<Nation, NationFacts> nationalFacts = new EnumMap<>(Nation.class);

        for (var entry : nationalUnits.entrySet()) {

            Nation nation = entry.getKey();
            List<UnitId> units = entry.getValue();

            boolean complete = units.stream()
                    .allMatch(unit -> unitFacts.get(unit).submitted());

            boolean predicted = units.stream()
                    .allMatch(unit -> unitFacts.get(unit).predicted());

            boolean distribution = complete && units.stream()
                    .allMatch(unit -> unitFacts.get(unit).inDistribution());

            boolean retained = complete && units.stream()
                    .allMatch(unit -> unitFacts.get(unit).retained());

            List<OrderPlan> plans =
                    nationalSearch.generate(board, nation, predictions);

            if (predicted != !plans.isEmpty())
                throw new IllegalStateException(
                        "National generation disagrees with evidence availability");

            boolean planHit = false;

            if (complete) {

                String signature = signature(board, units, actual);

                planHit = plans.stream().anyMatch(plan ->
                        Classifier.signature(board, plan.orders()).equals(signature));

                overall.nations.observe(
                        predicted, distribution, retained, planHit);

                annual.nations.observe(
                        predicted, distribution, retained, planHit);

            }

            nationalFacts.put(nation, new NationFacts(
                    complete, predicted, distribution, retained, planHit));

        }

        // Match ScenarioCoverageEvaluation: no opponent problem with <2 nations.
        if (nationalUnits.size() < 2)
            return;

        for (Nation ownNation : nationalUnits.keySet()) {

            List<Nation> opponents = nationalUnits.keySet().stream()
                    .filter(nation -> nation != ownNation)
                    .toList();

            boolean complete = opponents.stream()
                    .allMatch(nation -> nationalFacts.get(nation).complete());

            if (!complete) {

                overall.incompleteOpponentLabels++;
                annual.incompleteOpponentLabels++;
                continue;

            }

            boolean predicted = opponents.stream()
                    .allMatch(nation -> nationalFacts.get(nation).predicted());

            boolean distribution = opponents.stream()
                    .allMatch(nation -> nationalFacts.get(nation).inDistribution());

            boolean retained = opponents.stream()
                    .allMatch(nation -> nationalFacts.get(nation).retained());

            boolean nationalPlans = opponents.stream()
                    .allMatch(nation -> nationalFacts.get(nation).planHit());

            boolean scenarioHit = false;

            if (predicted) {

                List<OpponentScenario> scenarios =
                        opponentSearch.generate(board, ownNation, predictions);

                if (scenarios.isEmpty())
                    throw new IllegalStateException(
                            "Complete evidence produced no opponent scenarios");

                List<UnitId> opponentUnits = board.locations().keySet().stream()
                        .filter(unit -> unit.owner() != ownNation)
                        .toList();

                String signature = signature(board, opponentUnits, actual);

                scenarioHit = scenarios.stream().anyMatch(scenario ->
                        Classifier.signature(board, scenario.orders()).equals(signature));

                overall.generatedScenarios = Math.addExact(
                        overall.generatedScenarios, scenarios.size());

                annual.generatedScenarios = Math.addExact(
                        annual.generatedScenarios, scenarios.size());

            }

            overall.opponents.observe(
                    predicted, distribution, retained, nationalPlans, scenarioHit);

            annual.opponents.observe(
                    predicted, distribution, retained, nationalPlans, scenarioHit);

        }

    }


    // Counterfactual audit: same context, but history deliberately omitted. \\

    private void audit(
            Bucket bucket,
            BoardState board,
            RoutePrediction selected,
            RoutePrediction position,
            Order actual) {

        if (selected.basis().equals("POSITION_YEAR_POOLED")) {

            if (selected.observations() < minimumObservations)
                throw new IllegalStateException(
                        "Pooled prediction did not meet the evidence threshold");

            bucket.yearPooled++;

            if (topContains(board, selected, actual, choicesPerUnit))
                bucket.yearPooledHits++;

            if (position.counts().isEmpty()) {

                bucket.yearPooledNoContext++;

            } else {

                if (position.observations() >= minimumObservations)
                    throw new IllegalStateException(
                            "Pooled fallback replaced sufficient year-specific evidence");

                bucket.yearPooledBelowThreshold++;

            }

            return;

        }

        if (selected.counts().isEmpty()) {

            if (position.counts().isEmpty()) {
                bucket.noPositionContext++;
            } else {

                if (position.observations() >= minimumObservations)
                    throw new IllegalStateException(
                            "Prediction NONE despite sufficient POSITION evidence");

                bucket.belowThreshold++;

            }

            return;

        }

        switch (selected.basis()) {
            case "EXACT" -> bucket.exact++;
            case "SUFFIX" -> bucket.suffix++;
            case "POSITION" -> bucket.position++;
            default -> throw new IllegalStateException(
                    "Unexpected prediction basis: " + selected.basis());
        }

        if (position.counts().isEmpty()
                || position.observations() < selected.observations())
            throw new IllegalStateException(
                    "POSITION evidence should include selected history evidence");

        if (selected.basis().equals("POSITION"))
            return;

        bucket.historySelected++;

        if (position.observations() <= selected.observations())
            return;

        bucket.broaderPosition++;

        boolean selectedHit = topContains(
                board, selected, actual, choicesPerUnit);

        boolean positionHit = topContains(
                board, position, actual, choicesPerUnit);

        if (selectedHit)
            bucket.selectedHits++;

        if (positionHit)
            bucket.positionHits++;

        if (!selectedHit && positionHit)
            bucket.positionGains++;

        if (selectedHit && !positionHit)
            bucket.positionLosses++;

    }

    private static boolean topContains(
            BoardState board, RoutePrediction prediction,
            Order actual, int limit) {

        List<Order> ranked = JointOrders.rankedOrders(board, prediction);

        return ranked.subList(0, Math.min(limit, ranked.size())).contains(actual);

    }

    private static String signature(
            BoardState board,
            List<UnitId> units,
            Map<UnitId, Order> actual) {

        return Classifier.signature(
                board, units.stream().map(actual::get).toList());

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


    private record UnitFacts(
            boolean submitted,
            boolean predicted,
            boolean inDistribution,
            boolean retained
    ) {  }

    private record NationFacts(
            boolean complete,
            boolean predicted,
            boolean inDistribution,
            boolean retained,
            boolean planHit
    ) {  }


    private static final class Funnel {

        private final long[] counts;

        private Funnel(int stages) {
            counts = new long[stages];
        }

        private void observe(boolean... stages) {

            if (stages.length != counts.length - 1)
                throw new IllegalArgumentException("Wrong number of funnel stages");

            boolean previous = true;

            for (boolean stage : stages) {

                if (stage && !previous)
                    throw new IllegalStateException(
                            "Downstream hit without upstream availability");

                previous = stage;

            }

            counts[0]++;

            for (int index = 0; index < stages.length; index++)
                if (stages[index])
                    counts[index + 1]++;

        }

        private List<Long> values() {
            return Arrays.stream(counts).boxed().toList();
        }

    }

    private static final class Bucket {

        private final Funnel units = new Funnel(4);
        private final Funnel nations = new Funnel(5);
        private final Funnel opponents = new Funnel(6);

        private long incompleteOpponentLabels;
        private long generatedScenarios;

        private long exact;
        private long suffix;
        private long position;
        private long noPositionContext;
        private long belowThreshold;

        private long historySelected;
        private long broaderPosition;
        private long selectedHits;
        private long positionHits;
        private long positionGains;
        private long positionLosses;

        private long yearPooled;
        private long yearPooledHits;
        private long yearPooledNoContext;
        private long yearPooledBelowThreshold;

    }


    public static final class Report {

        private final int trainingGames;
        private final int validationGames;
        private final int choices;
        private final int plans;
        private final int scenarios;
        private final long minimum;

        private final Bucket overall;
        private final NavigableMap<Integer, Bucket> years;

        private Report(
                int trainingGames, int validationGames,
                int choices, int plans, int scenarios, long minimum,
                Bucket overall, NavigableMap<Integer, Bucket> years) {

            this.trainingGames = trainingGames;
            this.validationGames = validationGames;
            this.choices = choices;
            this.plans = plans;
            this.scenarios = scenarios;
            this.minimum = minimum;
            this.overall = overall;
            this.years = Collections.unmodifiableNavigableMap(new TreeMap<>(years));

        }

        public List<Long> unitCounts() {
            return overall.units.values();
        }

        public List<Long> nationalCounts() {
            return overall.nations.values();
        }

        public List<Long> opponentCounts() {
            return overall.opponents.values();
        }

        public long generatedScenarios() {
            return overall.generatedScenarios;
        }

        public long noPositionContext() {
            return overall.noPositionContext;
        }

        public long belowThreshold() {
            return overall.belowThreshold;
        }


        public void print(PrintStream output) {

            Objects.requireNonNull(output, "output");

            output.println();
            output.println("STAGE COVERAGE VALIDATION");
            output.printf("Training: %d; validation: %d%n",
                    trainingGames, validationGames);

            output.printf("Minimum: %d; unit choices: %d; national plans: %d; scenarios: %d%n",
                    minimum, choices, plans, scenarios);

            printFunnel(output, "UNIT DECISIONS", overall.units,
                    "Submitted decisions",
                    "Prediction available",
                    "Actual order in full selected distribution",
                    "Actual order retained in top " + choices);

            printFunnel(output, "NATIONAL TURNS", overall.nations,
                    "Complete national labels",
                    "All units have predictions",
                    "All actual orders in full selected distributions",
                    "All actual orders retained in unit choices",
                    "Actual plan survives national beam");

            printFunnel(output, "OPPONENT PERSPECTIVES", overall.opponents,
                    "Complete opponent labels",
                    "All opposing units have predictions",
                    "All actual opposing orders in full distributions",
                    "All actual opposing orders retained in unit choices",
                    "All actual opposing national plans survive",
                    "Actual combination survives scenario beam");

            output.printf("%nGenerated scenarios: %d%n", overall.generatedScenarios);
            output.printf("Incomplete opponent-label perspectives: %d%n",
                    overall.incompleteOpponentLabels);

            output.println();
            output.println("YEAR-BY-YEAR COUNTS");
            output.println("Unit: eligible / predicted / full distribution / retained");
            output.println("Nation: eligible / predicted / full distribution / retained / beam hit");
            output.println("Opponent: eligible / predicted / full distribution / retained / plans / beam hit");

            for (var entry : years.entrySet()) {

                Bucket bucket = entry.getValue();

                output.printf("%n%d%n", entry.getKey());
                output.println("  Unit:     " + bucket.units.values());
                output.println("  Nation:   " + bucket.nations.values());
                output.println("  Opponent: " + bucket.opponents.values());

            }

            output.println();
            output.println("FALLBACK AUDIT — SUBMITTED UNIT DECISIONS ONLY");
            output.println("Counterfactual POSITION uses the same full board context.");
            output.println("It is measured, not used to generate candidates.");
            output.println();

            printAudit(output, "ALL", overall);

            for (var entry : years.entrySet())
                printAudit(output, Integer.toString(entry.getKey()), entry.getValue());

            output.println();
            output.println("No-context = POSITION had zero observations even at minimum 1.");
            output.println("Below-cutoff = POSITION existed but did not meet the configured minimum.");
            output.println("Broader = more POSITION observations than the selected EXACT/SUFFIX.");
            output.println("Paired top-K hits/gains/losses use only those broader-POSITION decisions.");
            output.println("These are counts, not independent games or calibrated probabilities.");

        }

        private static void printFunnel(
                PrintStream output, String title,
                Funnel funnel, String... labels) {

            output.println();
            output.println(title);

            long[] counts = funnel.counts;

            for (int index = 0; index < counts.length; index++) {

                String loss = index == 0 ? "-"
                        : Long.toString(counts[index - 1] - counts[index]);

                output.printf(
                        Locale.ROOT,
                        "  %-57s %8d  %7s of eligible  lost=%s%n",
                        labels[index],
                        counts[index],
                        percent(counts[index], counts[0]),
                        loss);

            }

        }

        private static void printAudit(
                PrintStream output, String label, Bucket bucket) {

            output.printf(
                    "%s: EXACT=%d SUFFIX=%d POSITION=%d no-context=%d below-cutoff=%d%n",
                    label, bucket.exact, bucket.suffix, bucket.position,
                    bucket.noPositionContext, bucket.belowThreshold);

            output.printf(
                    "  History-selected=%d broader-position=%d; "
                            + "paired selectedHits=%d positionHits=%d gains=%d losses=%d%n",
                    bucket.historySelected,
                    bucket.broaderPosition,
                    bucket.selectedHits,
                    bucket.positionHits,
                    bucket.positionGains,
                    bucket.positionLosses);

            output.printf(
                    "  Year-pooled=%d top-K hits=%d; "
                            + "rescued absent-context=%d below-cutoff=%d%n",
                    bucket.yearPooled,
                    bucket.yearPooledHits,
                    bucket.yearPooledNoContext,
                    bucket.yearPooledBelowThreshold);

        }

        private static String percent(long numerator, long denominator) {

            return denominator == 0 ? "n/a"
                    : String.format(Locale.ROOT, "%.2f%%",
                    100.0 * numerator / denominator);

        }

    }

}