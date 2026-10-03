package analysis;

import phase.Order;
import java.util.*;

/** Movement-end components, not ownership changes or scenario probabilities. */
public record ScenarioEvaluation(String name, List<Order> opponentOrders,
                                 double objectiveDelta, double centerPositionDelta,
                                 double dislodgementPenalty, long dislodgedUnits,
                                 double score, double provinceContribution,
                                 double regionalContribution, double augmentedScore,
                                 PositionShaping.Breakdown shaping) {
    public ScenarioEvaluation(String name, List<Order> opponentOrders,
                              double objectiveDelta, double centerPositionDelta,
                              double dislodgementPenalty, long dislodgedUnits, double score) {
        this(name, opponentOrders, objectiveDelta, centerPositionDelta, dislodgementPenalty,
                dislodgedUnits, score, 0, 0, score, PositionShaping.Breakdown.empty());
    }

    public ScenarioEvaluation {
        Objects.requireNonNull(name, "name");
        opponentOrders = List.copyOf(opponentOrders);
        if (!Double.isFinite(objectiveDelta) || !Double.isFinite(centerPositionDelta)
                || !Double.isFinite(dislodgementPenalty) || !Double.isFinite(score)
                || dislodgedUnits < 0)
            throw new IllegalArgumentException("Invalid scenario components");
        Objects.requireNonNull(shaping, "shaping");
        if (!Double.isFinite(provinceContribution) || !Double.isFinite(regionalContribution)
                || !Double.isFinite(augmentedScore)
                || Double.compare(provinceContribution, shaping.provinceContribution()) != 0
                || Double.compare(regionalContribution, shaping.regionalContribution()) != 0
                || Double.compare(augmentedScore, score + provinceContribution + regionalContribution) != 0)
            throw new IllegalArgumentException("Inconsistent scenario shaping arithmetic");
    }
}
