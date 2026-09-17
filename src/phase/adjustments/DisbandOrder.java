package phase.adjustments;

import domain.Nation;
import phase.UnitId;

import java.util.Objects;

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

}