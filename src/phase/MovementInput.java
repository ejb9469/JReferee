package phase;

import domain.Province;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable input for one Spring or Fall movement phase.
 *
 * <p>startingLocations is the complete pre-movement board. UnitId is a stable
 * identity, while the map value is the unit's current location in this phase.</p>
 */
public record MovementInput(Map<UnitId, Province> startingLocations,
                            List<Order> submittedOrders)
        implements PhaseInput {


    public MovementInput(Map<UnitId, Province> startingLocations, List<Order> submittedOrders) {
        this.startingLocations = BoardRules.unitLocations(startingLocations, "startingLocations");
        this.submittedOrders = List.copyOf(
                Objects.requireNonNull(submittedOrders, "submittedOrders"));
    }


}