package phase.movement;

import phase.Order;
import phase.UnitId;

import java.util.Objects;

/**
 * One submitted, normalized movement-phase order for one active unit.
 *
 * <p>{@code submitted} is false when `MovementProcessor` 'synthesized' a HOLD
 * because the unit received no player order.</p>
 */
record NormalizedOrder(
        UnitId unit,
        Order order,
        boolean submitted
) {

    NormalizedOrder {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(order, "order");
    }

}