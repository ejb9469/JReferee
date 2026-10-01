package phase.adjustments;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * Voluntarily leaves one available build unused.
 * <p>Whether this request is legal is decided later by `AdjustmentProcessor`.
 * This class only guarantees that the submitted order is structurally valid.</p>
 */
public record WaiveOrder(Nation nation) implements AdjustmentOrder {

    public WaiveOrder {
        Objects.requireNonNull(nation, "nation");
    }

    @Override
    public Nation owner() {
        return this.nation;
    }

    @Override
    public @Nullable UnitType unitType() {
        return null;
    }

    @Override
    public Province origin() {
        return Province.Swi;  // lol
    }

    @Override
    public OrderType orderType() {
        return OrderType.WAIVE;
    }

    @Override
    public @Nullable Province target() {
        return null;
    }

    @Override
    public @Nullable Province auxiliaryTarget() {
        return null;
    }


}