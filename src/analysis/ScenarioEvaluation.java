package analysis;

import phase.Order;
import java.util.*;

/** Movement-end components, not ownership changes or scenario probabilities. */
public record ScenarioEvaluation(String name, List<Order> opponentOrders,
                                 double objectiveDelta, double centerPositionDelta,
                                 double dislodgementPenalty, long dislodgedUnits,
                                 double score) {
    public ScenarioEvaluation {
        Objects.requireNonNull(name, "name");
        opponentOrders = List.copyOf(opponentOrders);
        if (!Double.isFinite(objectiveDelta) || !Double.isFinite(centerPositionDelta)
                || !Double.isFinite(dislodgementPenalty) || !Double.isFinite(score)
                || dislodgedUnits < 0)
            throw new IllegalArgumentException("Invalid scenario components");
    }
}
