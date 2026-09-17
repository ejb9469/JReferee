package phase.adjustments;

import domain.Nation;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

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

}