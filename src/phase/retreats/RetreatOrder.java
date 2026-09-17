package phase.retreats;

import domain.Province;
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
) {

    public RetreatOrder {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(destination, "destination");
    }

}