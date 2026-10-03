package analysis;

import java.util.*;


public record PlanEvaluation(
        OrderPlan plan,
        Map<String, Double> scenarioScores,
        double mean,
        double worst,
        double score,
        Map<String, ScenarioEvaluation> scenarioDetails
) {
    public PlanEvaluation(OrderPlan plan, Map<String, Double> scenarioScores,
                          double mean, double worst, double score) {
        this(plan, scenarioScores, mean, worst, score, Map.of());
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

        if (!Double.isFinite(mean) || !Double.isFinite(worst) || !Double.isFinite(score))
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