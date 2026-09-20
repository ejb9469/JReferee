package phase.movement;

import domain.Province;
import phase.BoardRules;
import phase.Order;
import phase.PhaseInput;
import phase.UnitId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable input for one Spring or Fall movement phase.
 *
 * <p>startingLocations is the complete pre-movement board. UnitId is a stable
 * identity, while the map value is the unit's current location in this phase.</p>
 */
public final class MovementInput implements PhaseInput {

    private final Map<UnitId, Province> startingLocations;
    private final List<Order> submittedOrders;

    public MovementInput(Map<UnitId, Province> startingLocations, List<Order> submittedOrders) {
        this.startingLocations = BoardRules.unitLocations(startingLocations, "startingLocations");
        this.submittedOrders = List.copyOf(
                Objects.requireNonNull(submittedOrders, "submittedOrders"));
    }

    public Map<UnitId, Province> startingLocations() {
        return startingLocations;
    }

    public List<Order> submittedOrders() {
        return submittedOrders;
    }

}