package analysis;

import analysis.openings.Classifier;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.util.*;


public class OutcomeEvaluator {

    private final double centerWeight;
    private final double dislodgementPenalty;
    private final double caution;
    private final double tacticalBiasWeight;
    private final ScoringConfiguration scoringConfiguration;


    public OutcomeEvaluator(double centerWeight, double dislodgementPenalty, double caution) {
        this(centerWeight, dislodgementPenalty, caution, 0);
    }

    public OutcomeEvaluator(double centerWeight, double dislodgementPenalty, double caution,
                            double tacticalBiasWeight) {
        this(centerWeight, dislodgementPenalty, caution, tacticalBiasWeight,
                ScoringConfiguration.defaults());
    }

    public OutcomeEvaluator(double centerWeight, double dislodgementPenalty, double caution,
                            double tacticalBiasWeight, ScoringConfiguration scoringConfiguration) {

        if (!Double.isFinite(centerWeight) || centerWeight < 0)
            throw new IllegalArgumentException("Center weight must be finite and non-negative");

        if (!Double.isFinite(dislodgementPenalty) || dislodgementPenalty < 0)
            throw new IllegalArgumentException("Dislodgement penalty must be finite and non-negative");

        if (!Double.isFinite(caution) || caution < 0 || caution > 1)
            throw new IllegalArgumentException("Caution must be between zero and one");

        this.centerWeight = centerWeight;
        this.dislodgementPenalty = dislodgementPenalty;
        this.caution = caution;
        TacticalBias.validateWeight(tacticalBiasWeight);
        this.tacticalBiasWeight = tacticalBiasWeight;
        this.scoringConfiguration = Objects.requireNonNull(scoringConfiguration, "scoringConfiguration");

    }

    public double tacticalBiasWeight() { return tacticalBiasWeight; }
    public ScoringConfiguration scoringConfiguration() { return scoringConfiguration; }

    public OutcomeEvaluator withScoringConfiguration(ScoringConfiguration configuration) {
        return new OutcomeEvaluator(centerWeight, dislodgementPenalty, caution, tacticalBiasWeight,
                configuration);
    }

    /** Preserve raw outcome settings while explicitly selecting a recommendation bias. */
    public OutcomeEvaluator withTacticalBiasWeight(double weight) {
        TacticalBias.validateWeight(weight);
        return weight == tacticalBiasWeight ? this
                : new OutcomeEvaluator(centerWeight, dislodgementPenalty, caution, weight, scoringConfiguration);
    }


    public List<PlanEvaluation> rank(
            BoardState board,
            GameMoment moment,
            Collection<OrderPlan> candidates,
            Map<String, List<Order>> opponentScenarios,
            Map<Province, Double> objectives,
            MovementProcessor processor) {
        return rank(board, moment, candidates, opponentScenarios, objectives, processor, Map.of());
    }

    public List<PlanEvaluation> rank(
            BoardState board,
            GameMoment moment,
            Collection<OrderPlan> candidates,
            Map<String, List<Order>> opponentScenarios,
            Map<Province, Double> objectives,
            MovementProcessor processor,
            Map<UnitId, RoutePrediction> evidence) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(opponentScenarios, "opponentScenarios");
        Objects.requireNonNull(processor, "processor");
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence"));

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException("This evaluator supports movement phases only");

        if (opponentScenarios.isEmpty())
            throw new IllegalArgumentException("At least one opponent scenario is required");

        Map<Province, Double> targets = canonicalObjectives(objectives);

        // Stable scenario order makes reports reproducible.
        Map<String, List<Order>> scenarios = new TreeMap<>();

        for (Map.Entry<String, List<Order>> entry : opponentScenarios.entrySet()) {

            String name = Objects.requireNonNull(entry.getKey(), "scenario name");

            if (name.isBlank())
                throw new IllegalArgumentException("Scenario names must not be blank");

            scenarios.put(name, List.copyOf(entry.getValue()));

        }

        List<OrderPlan> plans = List.copyOf(candidates);
        List<PlanEvaluation> evaluations = new ArrayList<>();
        JointOrders resolver = new JointOrders();

        Nation nation = plans.isEmpty() ? null : plans.getFirst().nation();

        for (OrderPlan plan : plans) {

            if (plan.nation() != nation)
                throw new IllegalArgumentException("Rank plans for one nation at a time");

            Map<String, Double> scores = new LinkedHashMap<>();
            Map<String, ScenarioEvaluation> details = new LinkedHashMap<>();
            double mean = 0;
            double worst = Double.POSITIVE_INFINITY;
            double shapedMean = 0;
            double shapedWorst = Double.POSITIVE_INFINITY;
            int index = 0;
            HumanPreference preference = HumanPreference.evaluate(board, plan, evidence);
            if (scoringConfiguration.humanWeight() > 0 && !preference.available())
                throw new IllegalArgumentException("Positive human weight requires full post-policy evidence");

            for (Map.Entry<String, List<Order>> scenario : scenarios.entrySet()) {

                MovementResult outcome = resolver.evaluate(
                        board, plan, scenario.getValue(), processor);

                double value = score(board, nation, outcome, targets);
                double objectiveDelta = objectiveValue(nation, outcome.finalLocations(), targets)
                        - objectiveValue(nation, board.locations(), targets);
                double centerDelta = positionValue(board, nation, outcome.finalLocations(), Map.of())
                        - positionValue(board, nation, board.locations(), Map.of());
                long dislodged = outcome.dislodgements().keySet().stream()
                        .filter(unit -> unit.owner() == plan.nation()).count();
                PositionShaping.Breakdown shaping = PositionShaping.evaluate(
                        board, nation, outcome, scoringConfiguration);
                double augmented = value + shaping.provinceContribution() + shaping.regionalContribution();
                if (!Double.isFinite(augmented))
                    throw new IllegalArgumentException("Shaped evaluation overflowed; reduce the weights");
                details.put(scenario.getKey(), new ScenarioEvaluation(scenario.getKey(),
                        scenario.getValue(), objectiveDelta, centerDelta,
                        dislodgementPenalty * dislodged, dislodged, value,
                        shaping.provinceContribution(), shaping.regionalContribution(), augmented, shaping));

                scores.put(scenario.getKey(), value);

                // Equal scenario weights; these are not inferred probabilities.
                mean += (value - mean) / ++index;
                worst = Math.min(worst, value);
                shapedMean += (augmented - shapedMean) / index;
                shapedWorst = Math.min(shapedWorst, augmented);

            }

            double combined = (1 - caution) * mean + caution * worst;
            double shapedScore = (1 - caution) * shapedMean + caution * shapedWorst;

            var findings = TacticalBias.inspect(board, plan);
            int offendingMoveCount = TacticalBias.offendingMoveCount(board, plan);
            double penalty = tacticalBiasWeight * offendingMoveCount;
            double humanContribution = scoringConfiguration.humanWeight() * preference.score();
            double finalScore = shapedScore - penalty + humanContribution;
            if (!Double.isFinite(finalScore))
                throw new IllegalArgumentException("Evaluation overflowed; reduce the weights");
            evaluations.add(new PlanEvaluation(plan, scores, mean, worst, finalScore, details,
                    combined, tacticalBiasWeight, offendingMoveCount, penalty, findings,
                    shapedMean, shapedWorst, shapedScore, preference, scoringConfiguration.humanWeight(),
                    humanContribution, scoringConfiguration));

        }

        evaluations.sort(
                Comparator.comparingDouble(PlanEvaluation::score).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (PlanEvaluation value) -> value.plan().logPreference()).reversed())
                        .thenComparing(value ->
                                Classifier.signature(board, value.plan().orders())));

        return List.copyOf(evaluations);

    }


    // Movement-end estimates, not final turn outcomes. \\

    private double score(
            BoardState board, Nation nation, MovementResult result,
            Map<Province, Double> objectives) {

        double before = positionValue(board, nation, board.locations(), objectives);
        double after = positionValue(board, nation, result.finalLocations(), objectives);

        long dislodged = result.dislodgements().keySet().stream()
                .filter(unit -> unit.owner() == nation)
                .count();

        // A dislodged unit may retreat; this is a risk penalty, not a death count.
        double value = after - before - dislodgementPenalty * dislodged;

        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Evaluation overflowed; reduce the weights");

        return value;

    }

    private double positionValue(
            BoardState board, Nation nation,
            Map<UnitId, Province> positions,
            Map<Province, Double> objectives) {

        double value = 0;

        for (Map.Entry<UnitId, Province> entry : positions.entrySet()) {

            UnitId unit = entry.getKey();
            Province territory = Province.canonical(entry.getValue());

            if (unit.owner() == nation) {

                value += objectives.getOrDefault(territory, 0.0);

                if (territory.supplyCenter && board.ownerOf(territory) != nation)
                    value += centerWeight;

            } else if (territory.supplyCenter && board.ownerOf(territory) == nation) {
                value -= centerWeight;
            }

        }

        return value;

    }

    private static double objectiveValue(Nation nation, Map<UnitId, Province> positions,
                                         Map<Province, Double> objectives) {
        double value = 0;
        for (var entry : positions.entrySet())
            if (entry.getKey().owner() == nation)
                value += objectives.getOrDefault(Province.canonical(entry.getValue()), 0.0);
        return value;
    }

    private static Map<Province, Double> canonicalObjectives(Map<Province, Double> objectives) {

        Objects.requireNonNull(objectives, "objectives");

        Map<Province, Double> result = new EnumMap<>(Province.class);

        for (Map.Entry<Province, Double> entry : objectives.entrySet()) {

            Province province = Province.canonical(
                    Objects.requireNonNull(entry.getKey(), "objective province"));

            double value = Objects.requireNonNull(entry.getValue(), "objective value");

            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Objective values must be finite");

            if (result.putIfAbsent(province, value) != null)
                throw new IllegalArgumentException(
                        "Multiple objectives refer to the same territory: " + province);

        }

        return Collections.unmodifiableMap(result);

    }

}