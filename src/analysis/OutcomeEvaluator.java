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


    public OutcomeEvaluator(double centerWeight, double dislodgementPenalty, double caution) {

        if (!Double.isFinite(centerWeight) || centerWeight < 0)
            throw new IllegalArgumentException("Center weight must be finite and non-negative");

        if (!Double.isFinite(dislodgementPenalty) || dislodgementPenalty < 0)
            throw new IllegalArgumentException("Dislodgement penalty must be finite and non-negative");

        if (!Double.isFinite(caution) || caution < 0 || caution > 1)
            throw new IllegalArgumentException("Caution must be between zero and one");

        this.centerWeight = centerWeight;
        this.dislodgementPenalty = dislodgementPenalty;
        this.caution = caution;

    }


    public List<PlanEvaluation> rank(
            BoardState board,
            GameMoment moment,
            Collection<OrderPlan> candidates,
            Map<String, List<Order>> opponentScenarios,
            Map<Province, Double> objectives,
            MovementProcessor processor) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(opponentScenarios, "opponentScenarios");
        Objects.requireNonNull(processor, "processor");

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
            double mean = 0;
            double worst = Double.POSITIVE_INFINITY;
            int index = 0;

            for (Map.Entry<String, List<Order>> scenario : scenarios.entrySet()) {

                MovementResult outcome = resolver.evaluate(
                        board, plan, scenario.getValue(), processor);

                double value = score(board, nation, outcome, targets);

                scores.put(scenario.getKey(), value);

                // Equal scenario weights; these are not inferred probabilities.
                mean += (value - mean) / ++index;
                worst = Math.min(worst, value);

            }

            double combined = (1 - caution) * mean + caution * worst;

            evaluations.add(new PlanEvaluation(plan, scores, mean, worst, combined));

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