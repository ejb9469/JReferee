package phase.adjustments;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * Requests a new unit at a vacant, owned home supply center.
 *
 * <p>Whether this request is legal is decided later by `AdjustmentProcessor`.
 * This class only guarantees that the submitted order is structurally valid.</p>
 */
public record BuildOrder(
        Nation nation,
        UnitType unitType,
        Province location)
    implements AdjustmentOrder {

    public BuildOrder {
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(unitType, "unitType");
        Objects.requireNonNull(location, "location");
    }

    @Override
    public Nation owner() {
        return this.nation;
    }

    @Override
    public UnitType unitType() {
        return unitType;
    }

    @Override
    public OrderType orderType() {
        return OrderType.BUILD;
    }

    @Override
    public Province origin() {
        return this.location;
    }

    @Override
    public Province target() {
        return this.location;   // probably should return null, but to be safe, since this use case is small, ...
    }                               // ... ret `this.location` for BuildOrders

    @Override
    public @Nullable Province auxiliaryTarget() {
        return null;
    }

}