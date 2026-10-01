package phase.adjustments;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.UnitId;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * Requests removal of an existing unit.
 *
 * <p>Whether this request is legal is decided later by `AdjustmentProcessor`.
 * This class only guarantees that the submitted order is structurally valid.</p>
 */
public record DisbandOrder(
        Nation nation,
        UnitId unit)
    implements AdjustmentOrder {

    public DisbandOrder {
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(unit, "unit");
        if (nation != unit.owner())
            throw new IllegalArgumentException(
                    "A nation may only order its own unit disbanded: " + unit);
    }

    @Override
    public Nation owner() {
        return this.nation;
    }

    @Override
    public UnitType unitType() {
        return this.unit.unitType();
    }

    @Override
    public OrderType orderType() {
        return OrderType.DESTROY;
    }

    @Override
    public Province origin() {
        return this.unit.origin();
    }

    @Override
    public Province target() {
        return this.origin();           // ibid, see `BuildOrder`
    }

    @Override
    public @Nullable Province auxiliaryTarget() {
        return null;
    }

}