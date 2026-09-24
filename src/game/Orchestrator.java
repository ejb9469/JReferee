package game;

import contracts.OrderForm;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentResult;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatResult;

import java.util.Collection;

public interface Orchestrator {

    MovementResult      resolveMovement(Collection<Order> submittedOrders);
    RetreatResult       resolveRetreats(Collection<RetreatOrder> submittedOrders);
    AdjustmentResult    resolveAdjustments(Collection<AdjustmentOrder> submittedOrders);

}
