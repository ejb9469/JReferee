package phase.retreats;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import org.jetbrains.annotations.Nullable;
import phase.UnitId;

import java.util.Objects;

/**
 * Immutable submitted retreat order for one dislodged unit.
 *
 * <p>A unit with no submitted RetreatOrder is not represented by any Order.
 * i.e. The `RetreatProcessor` will record it as destroyed after the retreat
 * phase due to the absence of any Order submitted.</p>
 */
public record RetreatOrder(
        UnitId unit,
        Province destination
) implements OrderForm {

    public RetreatOrder {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(destination, "destination");
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
        return OrderType.RETREAT;
    }

    @Override
    public Province target() {
        return this.destination;
    }

    @Override
    public @Nullable Province auxiliaryTarget() {
        return null;
    }

}