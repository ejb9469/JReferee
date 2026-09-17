package phase.adjustments;

import domain.Nation;

import java.util.Objects;

/**
 * Voluntarily leaves one available build unused.
 * <p>Whether this request is legal is decided later by `AdjustmentProcessor`.
 * This class only guarantees that the submitted order is structurally valid.</p>
 */
public record WaiveOrder(Nation nation) implements AdjustmentOrder {

    public WaiveOrder {
        Objects.requireNonNull(nation, "nation");
    }
}