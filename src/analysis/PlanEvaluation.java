package analysis;

import java.util.*;

/** Raw outcome summaries plus a once-per-plan, optional tactical recommendation penalty.
 * {@link #score()} is {@code baseScore - penaltyTotal}; scenario scores, mean and worst
 * remain adjudicated outcome values, not bias-adjusted values.
 */
public record PlanEvaluation(
        OrderPlan plan,
        Map<String, Double> scenarioScores,
        double mean,
        double worst,
        double score,
        Map<String, ScenarioEvaluation> scenarioDetails,
        double baseScore,
        double penaltyWeight,
        int offendingMoveCount,
        double penaltyTotal,
        List<TacticalPrinciple.Warning> findings
) {
    public PlanEvaluation(OrderPlan plan, Map<String, Double> scenarioScores,
                          double mean, double worst, double score) {
        this(plan, scenarioScores, mean, worst, score, Map.of());
    }

    public PlanEvaluation(OrderPlan plan, Map<String, Double> scenarioScores,
                          double mean, double worst, double score,
                          Map<String, ScenarioEvaluation> scenarioDetails) {
        this(plan, scenarioScores, mean, worst, score, scenarioDetails,
                score, 0, 0, 0, List.of());
    }

    public PlanEvaluation {

        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scenarioScores, "scenarioScores");

        if (scenarioScores.isEmpty())
            throw new IllegalArgumentException("At least one scenario is required");

        Map<String, Double> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : scenarioScores.entrySet()) {

            Objects.requireNonNull(entry.getKey(), "scenario name");
            Double value = Objects.requireNonNull(entry.getValue(), "scenario score");

            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Scenario scores must be finite");

            copy.put(entry.getKey(), value);

        }

        TacticalBias.validateWeight(penaltyWeight);
        findings = List.copyOf(findings);
        if (offendingMoveCount < 0 || offendingMoveCount != findings.size()
                || !Double.isFinite(penaltyTotal) || penaltyTotal < 0
                || Double.compare(penaltyTotal, penaltyWeight * offendingMoveCount) != 0
                || Double.compare(score, baseScore - penaltyTotal) != 0)
            throw new IllegalArgumentException("Inconsistent tactical penalty arithmetic");
        if (!Double.isFinite(mean) || !Double.isFinite(worst) || !Double.isFinite(score)
                || !Double.isFinite(baseScore))
            throw new IllegalArgumentException("Evaluation scores must be finite");

        scenarioScores = Collections.unmodifiableMap(copy);
        scenarioDetails = Collections.unmodifiableMap(new LinkedHashMap<>(scenarioDetails));
        if (!scenarioDetails.isEmpty() && !scenarioDetails.keySet().equals(scenarioScores.keySet()))
            throw new IllegalArgumentException("Scenario details must match scores");
        for (var entry : scenarioDetails.entrySet()) {
            ScenarioEvaluation detail = Objects.requireNonNull(entry.getValue(), "scenario detail");
            if (!entry.getKey().equals(detail.name())
                    || Double.compare(scenarioScores.get(entry.getKey()), detail.score()) != 0)
                throw new IllegalArgumentException("Scenario details must describe the same scores");
        }

    }

}