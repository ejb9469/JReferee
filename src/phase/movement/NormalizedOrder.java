package phase.movement;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import org.jetbrains.annotations.Nullable;
import phase.Order;
import phase.UnitId;

import java.util.Objects;

/**
 * One submitted, normalized movement-phase order for one active unit.
 *
 * <p>{@code submitted} is false when `MovementProcessor` 'synthesized' a HOLD
 * because the unit received no player order.</p>
 */
public record NormalizedOrder(
        UnitId unit,
        Order order,
        boolean submitted
) implements OrderForm {

    public NormalizedOrder {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(order, "order");
    }

    @Override
    public Nation owner() {
        return this.unit.owner();
    }

    @Override
    public UnitType unitType() {
        return this.unit.unitType();
    }

    @Override
    public Province origin() {
        return this.unit.origin();
    }

    @Override
    public OrderType orderType() {
        return this.order.orderType();
    }

    @Override
    public Province target() {
        return this.order.target();
    }

    @Override
    public Province auxiliaryTarget() {
        return this.order.auxiliaryTarget();
    }

}