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


public final class FourPolicyEvaluation {

    private static final int MAXIMUM_SUFFIX = 4;
    private static final int CHOICES = 4;
    private static final int NATIONAL_PLANS = 8;
    private static final int SCENARIOS = 32;
    private static final long MINIMUM = 3;

    private static final List<PredictionPolicy> POLICIES = List.of(
            PredictionPolicy.ORIGINAL,
            PredictionPolicy.REFERENCE_FILTERED,
            PredictionPolicy.YEAR_POOLED_SELECTION,
            PredictionPolicy.YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED);

    private static final List<String> UNIT_STAGES = List.of(
            "Prediction available",
            "Actual in full distribution",
            "Actual in top four");

    private static final List<String> NATIONAL_STAGES = List.of(
            "All units predicted",
            "All actuals in distributions",
            "All actual choices in top four",
            "Actual national plan generated");

    private static final List<String> OPPONENT_STAGES = List.of(
            "All opposing units predicted",
            "All actuals in distributions",
            "All actual choices in top four",
            "All actual national plans generated",
            "Actual opponent scenario generated");


    private FourPolicyEvaluation() {  }


    public static Report evaluate(
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

        RoutePreferences preferences =
                new RoutePreferences(null, MAXIMUM_SUFFIX, true, true);

        for (int index = 0; index < training.size(); index++) {
            if (!preferences.add(training.get(index)))
                throw new IllegalStateException("Training game was not added");

            if ((index + 1) % 100 == 0 || index + 1 == training.size())
                output.printf("Training: %d/%d games%n", index + 1, training.size());
        }

        EvaluationBuilder report = new EvaluationBuilder();
        JointOrders nationalSearch = new JointOrders(CHOICES, NATIONAL_PLANS);
        OpponentScenarios opponentSearch =
                new OpponentScenarios(CHOICES, NATIONAL_PLANS, SCENARIOS);

        for (int index = 0; index < validation.size(); index++) {
            GameRecord game = validation.get(index);
            UnitRoutes histories = new UnitRoutes();
            histories.add(game);

            for (ResolvedPhaseRecord phase : game.resolvedPhases()) {
                if (phase instanceof MovementPhaseRecord movement)
                    evaluateMovement(
                            game, movement, histories, preferences,
                            nationalSearch, opponentSearch, report);
            }

            output.printf(
                    Locale.ROOT,
                    "Validation: %d/%d games%n", index + 1, validation.size());
            output.flush();
        }

        Report result = report.freeze(training.size(), validation.size());
        result.print(output);
        return result;

    }


    private static void evaluateMovement(
            GameRecord game,
            MovementPhaseRecord movement,
            UnitRoutes histories,
            RoutePreferences preferences,
            JointOrders nationalSearch,
            OpponentScenarios opponentSearch,
            EvaluationBuilder report) {

        BoardState board = movement.boardBefore();
        int year = movement.gameMoment().year();
        Map<Nation, List<UnitId>> nations = new EnumMap<>(Nation.class);

        for (UnitId unit : board.locations().keySet())
            nations.computeIfAbsent(unit.owner(), ignored -> new ArrayList<>())
                    .add(unit);

        /*
         * Build every policy's evidence and candidates before inspecting this
         * phase's submitted orders. The labels cannot affect policy selection.
         */
        Map<PredictionPolicy, PolicyPhase> policies =
                new EnumMap<>(PredictionPolicy.class);

        for (PredictionPolicy policy : POLICIES)
            policies.put(policy, new PolicyPhase());

        for (UnitId unit : board.locations().keySet()) {
            List<String> history = RoutePreferences.historyBefore(
                    histories.route(game.id(), unit), movement.gameMoment());
            RoutePrediction original = preferences.predict(
                    game.rulesetId(), movement.gameMoment(), board,
                    unit, history, MINIMUM);
            RoutePrediction pooled = preferences.positionEvidence(
                    game.rulesetId(), movement.gameMoment(), board, unit).yearPooled();

            for (PredictionPolicy policy : POLICIES) {
                RoutePrediction prefilter = selection(policy, original, pooled);
                RoutePrediction selected =
                        policy.apply(board, original, pooled, MINIMUM);
                requireBound(unit, original);
                requireBound(unit, prefilter);
                requireBound(unit, selected);
                policies.get(policy).put(unit, original, prefilter, selected);
            }
        }

        for (PredictionPolicy policy : POLICIES) {
            PolicyPhase current = policies.get(policy);

            for (Nation nation : nations.keySet())
                current.nationalPlans.put(
                        nation, nationalSearch.generate(
                                board, nation, current.selected));

            for (Nation ownNation : nations.keySet()) {
                List<UnitId> opponentUnits = board.locations().keySet().stream()
                        .filter(unit -> unit.owner() != ownNation)
                        .toList();
                current.scenarioQueries++;

                if (opponentUnits.stream().allMatch(unit ->
                        !current.selected.get(unit).counts().isEmpty())) {
                    List<OpponentScenario> scenarios =
                            opponentSearch.generate(board, ownNation, current.selected);
                    current.scenarios.put(ownNation, scenarios);
                    current.generatedScenarios += scenarios.size();
                    current.predictedScenarioQueries++;
                } else {
                    current.scenarios.put(ownNation, List.of());
                }
            }

            report.addScenarioMetrics(
                    year, policy, current.scenarioQueries,
                    current.predictedScenarioQueries, current.generatedScenarios);
        }

        Map<UnitId, Order> actual = readSubmittedOrders(board, movement);
        List<UnitId> activeUnits = List.copyOf(board.locations().keySet());
        report.addOpportunities(year, activeUnits.size(), nations.size(),
                nations.size() < 2 ? 0 : nations.size());

        for (UnitId unit : activeUnits) {
            Order label = actual.get(unit);

            if (label == null)
                report.addOmittedUnitLabel(year);
            else
                report.addSubmittedUnitLabel(year);

            boolean defective = label != null
                    && ReferenceCompatibility.issue(board, label)
                    != ReferenceCompatibility.Issue.NONE;

            for (PredictionPolicy policy : POLICIES) {
                PolicyPhase state = policies.get(policy);
                RoutePrediction beforeFilter = state.prefilter.get(unit);
                RoutePrediction selected = state.selected.get(unit);
                report.observeAudit(year, policy,
                        state.original.get(unit), beforeFilter, selected,
                        label, defective);
            }

            if (label == null)
                continue;

            Map<PredictionPolicy, boolean[]> stages = new EnumMap<>(PredictionPolicy.class);

            for (PredictionPolicy policy : POLICIES)
                stages.put(policy, unitStages(board, policies.get(policy), unit, label));

            report.observeUnit(year, stages);
        }

        for (Nation nation : nations.keySet()) {
            List<UnitId> units = nations.get(nation);

            if (!actual.keySet().containsAll(units)) {
                report.addIncompleteNationalLabel(year);
                continue;
            }

            Map<PredictionPolicy, boolean[]> stages = new EnumMap<>(PredictionPolicy.class);

            for (PredictionPolicy policy : POLICIES)
                stages.put(policy, nationalStages(
                        board, units, actual, policies.get(policy), nation));

            report.observeNational(year, stages);
        }

        if (nations.size() < 2)
            return;

        for (Nation ownNation : nations.keySet()) {
            List<UnitId> opponentUnits = activeUnits.stream()
                    .filter(unit -> unit.owner() != ownNation)
                    .toList();

            if (!actual.keySet().containsAll(opponentUnits)) {
                report.addIncompleteOpponentLabel(year);
                continue;
            }

            Map<PredictionPolicy, boolean[]> stages = new EnumMap<>(PredictionPolicy.class);

            for (PredictionPolicy policy : POLICIES)
                stages.put(policy, opponentStages(
                        board, ownNation, opponentUnits, nations.keySet(),
                        actual, policies.get(policy)));

            report.observeOpponent(year, stages);
        }

    }


    private static RoutePrediction selection(
            PredictionPolicy policy,
            RoutePrediction original,
            RoutePrediction pooled) {

        return switch (policy) {
            case YEAR_POOLED_SELECTION,
                 YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED ->
                    YearPooledSelection.select(original, pooled, MINIMUM);
            case ORIGINAL, REFERENCE_FILTERED -> original;
        };

    }


    private static boolean[] unitStages(
            BoardState board,
            PolicyPhase phase,
            UnitId unit,
            Order actual) {

        RoutePrediction prediction = phase.selected.get(unit);
        List<Order> ranked = JointOrders.rankedOrders(board, prediction);
        boolean available = !ranked.isEmpty();
        boolean full = prediction.counts().containsKey(actual);
        boolean top = ranked.subList(0, Math.min(CHOICES, ranked.size()))
                .contains(actual);
        return new boolean[] {available, full, top};

    }


    private static boolean[] nationalStages(
            BoardState board,
            List<UnitId> units,
            Map<UnitId, Order> actual,
            PolicyPhase phase,
            Nation nation) {

        boolean predicted = allAvailable(units, phase);
        boolean full = units.stream().allMatch(unit ->
                phase.selected.get(unit).counts().containsKey(actual.get(unit)));
        boolean top = units.stream().allMatch(unit ->
                unitStages(board, phase, unit, actual.get(unit))[2]);
        String signature = actualSignature(board, units, actual);
        boolean plan = phase.nationalPlans.get(nation).stream().anyMatch(candidate ->
                Classifier.signature(board, candidate.orders()).equals(signature));
        return new boolean[] {predicted, full, top, plan};

    }


    private static boolean[] opponentStages(
            BoardState board,
            Nation ownNation,
            List<UnitId> opponentUnits,
            Set<Nation> nations,
            Map<UnitId, Order> actual,
            PolicyPhase phase) {

        boolean predicted = allAvailable(opponentUnits, phase);
        boolean full = opponentUnits.stream().allMatch(unit ->
                phase.selected.get(unit).counts().containsKey(actual.get(unit)));
        boolean top = opponentUnits.stream().allMatch(unit ->
                unitStages(board, phase, unit, actual.get(unit))[2]);

        boolean plans = nations.stream()
                .filter(nation -> nation != ownNation)
                .allMatch(nation -> {
                    List<UnitId> units = opponentUnits.stream()
                            .filter(unit -> unit.owner() == nation)
                            .toList();
                    String signature = actualSignature(board, units, actual);
                    return phase.nationalPlans.get(nation).stream().anyMatch(candidate ->
                            Classifier.signature(board, candidate.orders())
                                    .equals(signature));
                });

        String signature = actualSignature(board, opponentUnits, actual);
        boolean scenario = phase.scenarios.getOrDefault(ownNation, List.of()).stream()
                .anyMatch(candidate -> Classifier.signature(board, candidate.orders())
                        .equals(signature));

        return new boolean[] {predicted, full, top, plans, scenario};

    }


    private static boolean allAvailable(List<UnitId> units, PolicyPhase phase) {
        return units.stream()
                .allMatch(unit -> !phase.selected.get(unit).counts().isEmpty());
    }


    private static String actualSignature(
            BoardState board,
            List<UnitId> units,
            Map<UnitId, Order> actual) {
        return Classifier.signature(board, units.stream().map(actual::get).toList());
    }


    private static Map<UnitId, Order> readSubmittedOrders(
            BoardState board,
            MovementPhaseRecord movement) {

        Map<UnitId, Order> actual = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet()) {
            var outcome = movement.result().outcomes().get(unit);

            if (outcome == null)
                throw new IllegalArgumentException("Missing outcome for an active unit");

            if (!outcome.submitted())
                continue;

            Order order = outcome.submittedOrder();

            if (!order.unit().equals(unit))
                throw new IllegalArgumentException("Actual order has wrong issuer");

            actual.put(unit, order);
        }

        return actual;

    }


    private static void requireBound(UnitId unit, RoutePrediction prediction) {
        for (Order order : prediction.counts().keySet())
            if (!order.unit().equals(unit))
                throw new IllegalStateException("Prediction order is not bound to its query unit");
    }


    private static void requireSeparateGames(
            List<GameRecord> training,
            List<GameRecord> validation) {

        Set<UUID> ids = new HashSet<>();

        for (GameRecord game : training)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException("Repeated training game ID");

        for (GameRecord game : validation)
            if (!ids.add(game.id()))
                throw new IllegalArgumentException(
                        "Repeated or overlapping validation game ID");

    }


    private static List<PolicyPair> pairs() {
        List<PolicyPair> result = new ArrayList<>();

        for (int first = 0; first < POLICIES.size(); first++)
            for (int second = first + 1; second < POLICIES.size(); second++)
                result.add(new PolicyPair(
                        POLICIES.get(first), POLICIES.get(second)));

        return List.copyOf(result);
    }


    private static final class PolicyPhase {

        private final Map<UnitId, RoutePrediction> original = new LinkedHashMap<>();
        private final Map<UnitId, RoutePrediction> prefilter = new LinkedHashMap<>();
        private final Map<UnitId, RoutePrediction> selected = new LinkedHashMap<>();
        private final Map<Nation, List<OrderPlan>> nationalPlans =
                new EnumMap<>(Nation.class);
        private final Map<Nation, List<OpponentScenario>> scenarios =
                new EnumMap<>(Nation.class);
        private long scenarioQueries;
        private long predictedScenarioQueries;
        private long generatedScenarios;

        private void put(
                UnitId unit,
                RoutePrediction original,
                RoutePrediction beforeFilter,
                RoutePrediction selected) {
            this.original.put(unit, original);
            prefilter.put(unit, beforeFilter);
            this.selected.put(unit, selected);
        }

    }


    private static final class EvaluationBuilder {

        private final MutableBucket overall = new MutableBucket();
        private final Map<Integer, MutableBucket> byYear = new TreeMap<>();
        private final MutableExclusions exclusions = new MutableExclusions();

        private MutableBucket bucket(int year) {
            return byYear.computeIfAbsent(year, ignored -> new MutableBucket());
        }

        private void addOpportunities(
                int year, long units, long nations, long opponentPerspectives) {
            exclusions.unitOpportunities += units;
            exclusions.nationalOpportunities += nations;
            exclusions.opponentOpportunities += opponentPerspectives;
            bucket(year).exclusions.unitOpportunities += units;
            bucket(year).exclusions.nationalOpportunities += nations;
            bucket(year).exclusions.opponentOpportunities += opponentPerspectives;
        }

        private void addOmittedUnitLabel(int year) {
            exclusions.omittedUnits++;
            bucket(year).exclusions.omittedUnits++;
        }

        private void addSubmittedUnitLabel(int year) {
            exclusions.submittedUnits++;
            bucket(year).exclusions.submittedUnits++;
        }

        private void addIncompleteNationalLabel(int year) {
            exclusions.incompleteNations++;
            bucket(year).exclusions.incompleteNations++;
        }

        private void addIncompleteOpponentLabel(int year) {
            exclusions.incompleteOpponents++;
            bucket(year).exclusions.incompleteOpponents++;
        }

        private void addScenarioMetrics(
                int year,
                PredictionPolicy policy,
                long queries,
                long predictedQueries,
                long generatedScenarios) {
            overall.metrics.get(policy).addScenarios(
                    queries, predictedQueries, generatedScenarios);
            bucket(year).metrics.get(policy).addScenarios(
                    queries, predictedQueries, generatedScenarios);
        }

        private void observeAudit(
                int year,
                PredictionPolicy policy,
                RoutePrediction original,
                RoutePrediction prefilter,
                RoutePrediction selected,
                Order label,
                boolean defective) {
            overall.metrics.get(policy).audit.observe(
                    original, prefilter, selected, label, defective);
            bucket(year).metrics.get(policy).audit.observe(
                    original, prefilter, selected, label, defective);
        }

        private void observeUnit(int year, Map<PredictionPolicy, boolean[]> stages) {
            overall.observeUnit(stages);
            bucket(year).observeUnit(stages);
        }

        private void observeNational(int year, Map<PredictionPolicy, boolean[]> stages) {
            overall.observeNational(stages);
            bucket(year).observeNational(stages);
            exclusions.eligibleNations++;
            bucket(year).exclusions.eligibleNations++;
        }

        private void observeOpponent(int year, Map<PredictionPolicy, boolean[]> stages) {
            overall.observeOpponent(stages);
            bucket(year).observeOpponent(stages);
            exclusions.eligibleOpponents++;
            bucket(year).exclusions.eligibleOpponents++;
        }

        private Report freeze(int trainingGames, int validationGames) {
            Map<Integer, YearReport> annual = new TreeMap<>();
            byYear.forEach((year, bucket) -> annual.put(year, bucket.freeze()));
            return new Report(
                    new Configuration(MINIMUM, MAXIMUM_SUFFIX, CHOICES,
                            NATIONAL_PLANS, SCENARIOS, POLICIES),
                    trainingGames, validationGames,
                    overall.freeze(), annual, exclusions.freeze());
        }

    }


    private static final class MutableBucket {

        private final Map<PredictionPolicy, MutablePolicyMetrics> metrics =
                new EnumMap<>(PredictionPolicy.class);
        private final Map<PolicyPair, MutableComparison> comparisons =
                new LinkedHashMap<>();
        private final MutableExclusions exclusions = new MutableExclusions();

        private MutableBucket() {
            for (PredictionPolicy policy : POLICIES)
                metrics.put(policy, new MutablePolicyMetrics());
            for (PolicyPair pair : pairs())
                comparisons.put(pair, new MutableComparison());
        }

        private void observeUnit(Map<PredictionPolicy, boolean[]> stages) {
            for (PredictionPolicy policy : POLICIES)
                metrics.get(policy).units.observe(stages.get(policy));
            observePairs(stages, 0);
        }

        private void observeNational(Map<PredictionPolicy, boolean[]> stages) {
            for (PredictionPolicy policy : POLICIES)
                metrics.get(policy).nations.observe(stages.get(policy));
            observePairs(stages, 1);
        }

        private void observeOpponent(Map<PredictionPolicy, boolean[]> stages) {
            for (PredictionPolicy policy : POLICIES)
                metrics.get(policy).opponents.observe(stages.get(policy));
            observePairs(stages, 2);
        }

        private void observePairs(Map<PredictionPolicy, boolean[]> stages, int funnel) {
            for (var entry : comparisons.entrySet())
                entry.getValue().observe(
                        funnel,
                        stages.get(entry.getKey().first()),
                        stages.get(entry.getKey().second()));
        }

        private YearReport freeze() {
            return new YearReport(policyReports(), comparisonReports(), exclusions.freeze());
        }

        private Map<PredictionPolicy, PolicyMetrics> policyReports() {
            Map<PredictionPolicy, PolicyMetrics> result =
                    new EnumMap<>(PredictionPolicy.class);
            metrics.forEach((policy, value) ->
                    result.put(policy, value.freeze()));
            return result;
        }

        private Map<PolicyPair, ComparisonMetrics> comparisonReports() {
            Map<PolicyPair, ComparisonMetrics> result = new LinkedHashMap<>();
            comparisons.forEach((pair, value) ->
                    result.put(pair, value.freeze()));
            return result;
        }

    }


    private static final class MutablePolicyMetrics {

        private final MutableFunnel units = new MutableFunnel(UNIT_STAGES);
        private final MutableFunnel nations = new MutableFunnel(NATIONAL_STAGES);
        private final MutableFunnel opponents = new MutableFunnel(OPPONENT_STAGES);
        private final MutableAudit audit = new MutableAudit();
        private long scenarioQueries;
        private long predictedScenarioQueries;
        private long generatedScenarios;

        private void addScenarios(
                long queries, long predictedQueries, long scenarios) {
            scenarioQueries += queries;
            predictedScenarioQueries += predictedQueries;
            generatedScenarios += scenarios;
        }

        private PolicyMetrics freeze() {
            return new PolicyMetrics(
                    units.freeze(), nations.freeze(), opponents.freeze(),
                    audit.freeze(), scenarioQueries,
                    predictedScenarioQueries, generatedScenarios);
        }

    }


    private static final class MutableFunnel {

        private final List<String> stages;
        private final long[] successes;
        private long eligible;

        private MutableFunnel(List<String> stages) {
            this.stages = stages;
            successes = new long[stages.size()];
        }

        private void observe(boolean[] values) {
            if (values.length != stages.size())
                throw new IllegalArgumentException("Wrong funnel stage count");

            boolean previous = true;
            for (int index = 0; index < values.length; index++) {
                if (values[index] && !previous)
                    throw new IllegalStateException(
                            "Downstream stage succeeded without upstream availability");
                if (values[index])
                    successes[index]++;
                previous = values[index];
            }
            eligible++;
        }

        private Funnel freeze() {
            List<Long> counts = Arrays.stream(successes).boxed().toList();
            return new Funnel(eligible, stages, counts);
        }

    }


    private static final class MutableComparison {

        private final List<MutableStageComparison> funnels = List.of(
                new MutableStageComparison(UNIT_STAGES),
                new MutableStageComparison(NATIONAL_STAGES),
                new MutableStageComparison(OPPONENT_STAGES));

        private void observe(int funnel, boolean[] first, boolean[] second) {
            funnels.get(funnel).observe(first, second);
        }

        private ComparisonMetrics freeze() {
            return new ComparisonMetrics(
                    funnels.get(0).freeze(),
                    funnels.get(1).freeze(),
                    funnels.get(2).freeze());
        }

    }


    private static final class MutableStageComparison {

        private final List<String> stages;
        private final long[] gains;
        private final long[] losses;
        private long eligible;

        private MutableStageComparison(List<String> stages) {
            this.stages = stages;
            gains = new long[stages.size()];
            losses = new long[stages.size()];
        }

        private void observe(boolean[] before, boolean[] after) {
            if (before.length != stages.size() || after.length != stages.size())
                throw new IllegalArgumentException("Wrong paired stage count");

            eligible++;
            for (int index = 0; index < stages.size(); index++) {
                if (!before[index] && after[index])
                    gains[index]++;
                if (before[index] && !after[index])
                    losses[index]++;
            }
        }

        private StageComparison freeze() {
            return new StageComparison(
                    eligible, stages,
                    Arrays.stream(gains).boxed().toList(),
                    Arrays.stream(losses).boxed().toList());
        }

    }


    private static final class MutableAudit {

        private long predictions;
        private long newlyEmpty;
        private long candidateOccurrencesBeforeFilter;
        private long retainedCandidateOccurrences;
        private long submittedLabels;
        private long representedActualLabels;
        private long removedRepresentedActualLabels;
        private long defectiveSubmittedLabels;
        private final Map<String, Long> selectionBasisTransitions = new TreeMap<>();
        private final Map<String, Long> finalBasisTransitions = new TreeMap<>();
        private final Map<String, Long> selectedBasisCounts = new TreeMap<>();

        private void observe(
                RoutePrediction original,
                RoutePrediction prefilter,
                RoutePrediction selected,
                Order label,
                boolean defective) {

            predictions++;
            candidateOccurrencesBeforeFilter += prefilter.counts().size();
            retainedCandidateOccurrences += selected.counts().size();

            String selectionTransition = original.basis() + " -> " + prefilter.basis();
            String finalTransition = original.basis() + " -> " + selected.basis();
            selectionBasisTransitions.merge(selectionTransition, 1L, Math::addExact);
            finalBasisTransitions.merge(finalTransition, 1L, Math::addExact);
            selectedBasisCounts.merge(selected.basis(), 1L, Math::addExact);

            if (!prefilter.counts().isEmpty() && selected.counts().isEmpty())
                newlyEmpty++;

            if (label != null) {
                submittedLabels++;
                if (defective)
                    defectiveSubmittedLabels++;
                if (prefilter.counts().containsKey(label)) {
                    representedActualLabels++;
                    if (!selected.counts().containsKey(label))
                        removedRepresentedActualLabels++;
                }
            }

        }

        private PolicyAudit freeze() {
            return new PolicyAudit(
                    predictions, newlyEmpty,
                    candidateOccurrencesBeforeFilter,
                    retainedCandidateOccurrences,
                    candidateOccurrencesBeforeFilter - retainedCandidateOccurrences,
                    submittedLabels, representedActualLabels,
                    removedRepresentedActualLabels, defectiveSubmittedLabels,
                    selectionBasisTransitions, finalBasisTransitions,
                    selectedBasisCounts);
        }

    }


    private static final class MutableExclusions {

        private long unitOpportunities;
        private long submittedUnits;
        private long omittedUnits;
        private long nationalOpportunities;
        private long eligibleNations;
        private long incompleteNations;
        private long opponentOpportunities;
        private long eligibleOpponents;
        private long incompleteOpponents;

        private Exclusions freeze() {
            return new Exclusions(
                    unitOpportunities, submittedUnits, omittedUnits,
                    nationalOpportunities, eligibleNations, incompleteNations,
                    opponentOpportunities, eligibleOpponents, incompleteOpponents);
        }

    }


    public record Configuration(
            long minimumObservations,
            int maximumSuffix,
            int choicesPerUnit,
            int nationalPlanLimit,
            int opponentScenarioLimit,
            List<PredictionPolicy> policies) {

        public Configuration {
            policies = List.copyOf(policies);
        }

    }


    public record Funnel(long eligible, List<String> stages, List<Long> successes) {

        public Funnel {
            stages = List.copyOf(stages);
            successes = List.copyOf(successes);
            if (eligible < 0 || stages.size() != successes.size())
                throw new IllegalArgumentException("Funnel labels and counts differ");
            long previous = eligible;
            for (long success : successes)
                if (success < 0 || success > eligible || success > previous)
                    throw new IllegalArgumentException("Funnel count is outside its denominator");
                else
                    previous = success;
        }

    }


    public record PolicyAudit(
            long predictionDenominator,
            long newlyEmptyPredictions,
            long candidateOccurrencesBeforeFiltering,
            long retainedCandidateOccurrences,
            long removedCandidateOccurrences,
            long submittedLabelDenominator,
            long representedActualLabelsBeforeFiltering,
            long removedRepresentedActualLabels,
            long defectiveSubmittedLabels,
            Map<String, Long> selectionBasisTransitions,
            Map<String, Long> finalBasisTransitions,
            Map<String, Long> selectedBasisCounts) {

        public PolicyAudit {
            selectionBasisTransitions = immutableSorted(selectionBasisTransitions);
            finalBasisTransitions = immutableSorted(finalBasisTransitions);
            selectedBasisCounts = immutableSorted(selectedBasisCounts);

            if (predictionDenominator < 0
                    || newlyEmptyPredictions < 0
                    || candidateOccurrencesBeforeFiltering < 0
                    || retainedCandidateOccurrences < 0
                    || removedCandidateOccurrences < 0
                    || submittedLabelDenominator < 0
                    || representedActualLabelsBeforeFiltering < 0
                    || removedRepresentedActualLabels < 0
                    || defectiveSubmittedLabels < 0
                    || candidateOccurrencesBeforeFiltering
                    != retainedCandidateOccurrences + removedCandidateOccurrences
                    || representedActualLabelsBeforeFiltering
                    < removedRepresentedActualLabels
                    || submittedLabelDenominator < representedActualLabelsBeforeFiltering
                    || submittedLabelDenominator < defectiveSubmittedLabels)
                throw new IllegalArgumentException("Inconsistent policy audit counts");
            if (selectionBasisTransitions.values().stream().mapToLong(Long::longValue).sum()
                            != predictionDenominator
                    || finalBasisTransitions.values().stream().mapToLong(Long::longValue).sum()
                            != predictionDenominator
                    || selectedBasisCounts.values().stream().mapToLong(Long::longValue).sum()
                            != predictionDenominator
                    || newlyEmptyPredictions > predictionDenominator)
                throw new IllegalArgumentException("Policy audit denominators disagree");
        }

    }


    public record StageComparison(
            long eligible,
            List<String> stages,
            List<Long> gains,
            List<Long> losses) {

        public StageComparison {
            stages = List.copyOf(stages);
            gains = List.copyOf(gains);
            losses = List.copyOf(losses);
            if (eligible < 0 || stages.size() != gains.size()
                    || stages.size() != losses.size())
                throw new IllegalArgumentException("Paired stage arrays differ");
            for (int index = 0; index < stages.size(); index++)
                if (gains.get(index) < 0 || losses.get(index) < 0
                        || gains.get(index) > eligible || losses.get(index) > eligible)
                    throw new IllegalArgumentException("Paired count outside denominator");
        }

        public List<Long> net() {
            List<Long> result = new ArrayList<>(stages.size());
            for (int index = 0; index < stages.size(); index++)
                result.add(gains.get(index) - losses.get(index));
            return List.copyOf(result);
        }

    }


    public record PolicyMetrics(
            Funnel units,
            Funnel nations,
            Funnel opponents,
            PolicyAudit audit,
            long scenarioPerspectiveDenominator,
            long scenarioPredictionCount,
            long generatedScenarios) {  }


    public record PolicyPair(PredictionPolicy first, PredictionPolicy second) {

        public PolicyPair {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            if (first == second)
                throw new IllegalArgumentException("A comparison needs two policies");
        }

        @Override
        public String toString() {
            return first + " vs " + second;
        }

    }


    public record ComparisonMetrics(
            StageComparison units,
            StageComparison nations,
            StageComparison opponents) {  }


    public record YearReport(
            Map<PredictionPolicy, PolicyMetrics> policies,
            Map<PolicyPair, ComparisonMetrics> comparisons,
            Exclusions exclusions) {

        public YearReport {
            policies = immutableEnum(policies);
            comparisons = Collections.unmodifiableMap(new LinkedHashMap<>(comparisons));
            for (var entry : comparisons.entrySet()) {
                PolicyMetrics first = policies.get(entry.getKey().first());
                PolicyMetrics second = policies.get(entry.getKey().second());
                requirePairedConsistency(first.units(), second.units(),
                        entry.getValue().units());
                requirePairedConsistency(first.nations(), second.nations(),
                        entry.getValue().nations());
                requirePairedConsistency(first.opponents(), second.opponents(),
                        entry.getValue().opponents());
            }
        }

    }

    private static void requirePairedConsistency(
            Funnel first, Funnel second, StageComparison paired) {
        if (first.eligible() != second.eligible()
                || first.eligible() != paired.eligible())
            throw new IllegalArgumentException("Paired funnel denominators disagree");
        for (int index = 0; index < paired.stages().size(); index++)
            if (second.successes().get(index) - first.successes().get(index)
                    != paired.gains().get(index) - paired.losses().get(index))
                throw new IllegalArgumentException(
                        "Net policy delta does not equal paired gains minus losses");
    }


    public record Exclusions(
            long unitLabelOpportunities,
            long submittedUnitLabels,
            long omittedUnitLabels,
            long nationalLabelOpportunities,
            long eligibleNationalLabels,
            long incompleteNationalLabels,
            long opponentPerspectiveOpportunities,
            long eligibleOpponentLabels,
            long incompleteOpponentLabels) {

        public Exclusions {
            if (unitLabelOpportunities != submittedUnitLabels + omittedUnitLabels
                    || nationalLabelOpportunities
                    != eligibleNationalLabels + incompleteNationalLabels
                    || opponentPerspectiveOpportunities
                    != eligibleOpponentLabels + incompleteOpponentLabels)
                throw new IllegalArgumentException("Label eligibility accounting disagrees");
        }

    }


    public record Report(
            Configuration configuration,
            int trainingGames,
            int validationGames,
            YearReport overall,
            Map<Integer, YearReport> byYear,
            Exclusions exclusions) {

        public Report {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(overall, "overall");
            byYear = Collections.unmodifiableMap(new TreeMap<>(byYear));
            Objects.requireNonNull(exclusions, "exclusions");
        }

        public void print(PrintStream output) {
            Objects.requireNonNull(output, "output");
            output.println();
            output.println("FOUR-POLICY ROUTE-PREDICTION COMPARISON");
            output.printf(
                    "Training=%d validation=%d minimum=%d maxSuffix=%d "
                            + "choices=%d nationalPlans=%d scenarios=%d%n",
                    trainingGames, validationGames,
                    configuration.minimumObservations(),
                    configuration.maximumSuffix(),
                    configuration.choicesPerUnit(),
                    configuration.nationalPlanLimit(),
                    configuration.opponentScenarioLimit());
            output.println(
                    "A=original with fallbacks; B=A then filter; "
                            + "C=year-pooled selection; D=C then filter.");
            output.println(
                    "The model is trained once; each policy sees the same validation labels.");
            printYear(output, "ALL YEARS", overall);
            byYear.forEach((year, metrics) ->
                    printYear(output, Integer.toString(year), metrics));
            output.println();
            output.println("Reserved TEST records are not used for fitting or evaluation.");
            output.println(
                    "These are exact-submission imitation funnels, not tactical strength or "
                            + "calibrated probabilities.");
        }

        private static void printYear(PrintStream output, String title, YearReport report) {
            output.println();
            output.println("=== " + title + " ===");
            for (PredictionPolicy policy : POLICIES) {
                PolicyMetrics metrics = report.policies().get(policy);
                output.println();
                output.println(policy);
                printFunnel(output, "Units", metrics.units());
                printFunnel(output, "National turns", metrics.nations());
                printFunnel(output, "Opponent perspectives", metrics.opponents());
                printAudit(output, metrics);
            }

            output.println();
            output.println("PAIRED GAINS / LOSSES (second policy relative to first)");
            for (var comparison : report.comparisons().entrySet()) {
                output.println(comparison.getKey());
                printComparison(output, "Units", comparison.getValue().units());
                printComparison(output, "National turns", comparison.getValue().nations());
                printComparison(output, "Opponent perspectives",
                        comparison.getValue().opponents());
            }

            output.printf(
                    "%nExcluded labels: units submitted=%d/%d (omitted=%d); "
                            + "national complete=%d/%d (incomplete=%d); "
                            + "opponent complete=%d/%d (incomplete=%d)%n",
                    report.exclusions().submittedUnitLabels(),
                    report.exclusions().unitLabelOpportunities(),
                    report.exclusions().omittedUnitLabels(),
                    report.exclusions().eligibleNationalLabels(),
                    report.exclusions().nationalLabelOpportunities(),
                    report.exclusions().incompleteNationalLabels(),
                    report.exclusions().eligibleOpponentLabels(),
                    report.exclusions().opponentPerspectiveOpportunities(),
                    report.exclusions().incompleteOpponentLabels());
        }

        private static void printFunnel(PrintStream output, String title, Funnel funnel) {
            output.printf("  %s eligible=%d%n", title, funnel.eligible());
            for (int index = 0; index < funnel.stages().size(); index++)
                output.printf("    %-39s %d/%d%n",
                        funnel.stages().get(index),
                        funnel.successes().get(index), funnel.eligible());
        }

        private static void printComparison(
                PrintStream output, String title, StageComparison comparison) {
            output.printf("  %s eligible=%d%n", title, comparison.eligible());
            for (int index = 0; index < comparison.stages().size(); index++) {
                long gain = comparison.gains().get(index);
                long loss = comparison.losses().get(index);
                long net = comparison.net().get(index);
                if (net != gain - loss)
                    throw new IllegalStateException("Paired gains/losses do not balance");
                output.printf("    %-39s gains=%d losses=%d net=%+d%n",
                        comparison.stages().get(index), gain, loss, net);
            }
        }

        private static void printAudit(
                PrintStream output, PolicyMetrics metrics) {
            PolicyAudit audit = metrics.audit();
            output.printf(
                    "  Candidate audit: predictions=%d; newly empty=%d; "
                            + "basis selection=%s; final=%s%n",
                    audit.predictionDenominator(),
                    audit.newlyEmptyPredictions(),
                    audit.selectionBasisTransitions(),
                    audit.finalBasisTransitions());
            output.printf(
                    "  Candidate occurrences: before=%d retained=%d removed=%d "
                            + "(denominator=pre-filter occurrences)%n",
                    audit.candidateOccurrencesBeforeFiltering(),
                    audit.retainedCandidateOccurrences(),
                    audit.removedCandidateOccurrences());
            output.printf(
                    "  Submitted labels=%d represented before filtering=%d "
                            + "removed among represented=%d defective=%d; selected bases=%s%n",
                    audit.submittedLabelDenominator(),
                    audit.representedActualLabelsBeforeFiltering(),
                    audit.removedRepresentedActualLabels(),
                    audit.defectiveSubmittedLabels(),
                    audit.selectedBasisCounts());
            output.printf(
                    "  Generated scenarios=%d across %d perspectives "
                            + "(%d with complete evidence)%n",
                    metrics.generatedScenarios(),
                    metrics.scenarioPerspectiveDenominator(),
                    metrics.scenarioPredictionCount());
        }

    }


    private static Map<String, Long> immutableSorted(Map<String, Long> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }


    private static <V> Map<PredictionPolicy, V> immutableEnum(
            Map<PredictionPolicy, V> values) {
        return Collections.unmodifiableMap(new EnumMap<>(values));
    }

}
