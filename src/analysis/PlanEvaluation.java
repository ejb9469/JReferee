package analysis;

import domain.OrderType;
import phase.Order;

import java.util.*;

/** Raw outcomes remain separate from shaping and once-per-plan preference/bias terms.
 * {@link #score()} is {@code shapedScore - penaltyTotal + humanContribution}.
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
        List<TacticalPrinciple.Warning> findings,
        double shapedMean,
        double shapedWorst,
        double shapedScore,
        HumanPreference humanPreference,
        double humanWeight,
        double humanContribution,
        ScoringConfiguration scoringConfiguration
) {
    public PlanEvaluation(OrderPlan plan, Map<String, Double> scenarioScores,
                          double mean, double worst, double score,
                          Map<String, ScenarioEvaluation> scenarioDetails,
                          double baseScore, double penaltyWeight, int offendingMoveCount,
                          double penaltyTotal, List<TacticalPrinciple.Warning> findings) {
        this(plan, scenarioScores, mean, worst, score, scenarioDetails, baseScore, penaltyWeight,
                offendingMoveCount, penaltyTotal, findings, mean, worst, baseScore,
                HumanPreference.unavailable(), 0, 0, ScoringConfiguration.defaults());
    }
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
        Set<Order> offendingMoves = new HashSet<>();
        for (TacticalPrinciple.Warning finding : findings)
            for (Order order : finding.orders())
                if (order.orderType() == OrderType.MOVE)
                    offendingMoves.add(order);
        if (offendingMoveCount < 0 || offendingMoveCount != offendingMoves.size()
                || !Double.isFinite(penaltyTotal) || penaltyTotal < 0
                || Double.compare(penaltyTotal, penaltyWeight * offendingMoveCount) != 0
                || Double.compare(score, shapedScore - penaltyTotal + humanContribution) != 0)
            throw new IllegalArgumentException("Inconsistent tactical penalty arithmetic");
        if (!Double.isFinite(mean) || !Double.isFinite(worst) || !Double.isFinite(score)
                || !Double.isFinite(baseScore) || !Double.isFinite(shapedMean)
                || !Double.isFinite(shapedWorst) || !Double.isFinite(shapedScore))
            throw new IllegalArgumentException("Evaluation scores must be finite");
        Objects.requireNonNull(humanPreference, "humanPreference");
        Objects.requireNonNull(scoringConfiguration, "scoringConfiguration");
        if (!Double.isFinite(humanWeight) || humanWeight < 0
                || Double.compare(humanWeight, scoringConfiguration.humanWeight()) != 0
                || (humanWeight > 0 && !humanPreference.available())
                || !Double.isFinite(humanContribution)
                || Double.compare(humanContribution, humanWeight * humanPreference.score()) != 0)
            throw new IllegalArgumentException("Inconsistent human preference arithmetic");

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