package phase.movement;

import domain.Province;
import phase.Order;
import phase.PhaseInput;
import phase.UnitId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable input for one Spring or Fall movement phase.
 *
 * <p>startingLocations is the complete pre-movement board. UnitId is a stable
 * identity, while the map value is the unit's current location in this phase.</p>
 */
public final class MovementInput implements PhaseInput {

    private final Map<UnitId, Province> startingLocations;
    private final List<Order> submittedOrders;

    public MovementInput(
            Map<UnitId, Province> startingLocations,
            List<Order> submittedOrders
    ) {
        this.startingLocations = immutableBoard(startingLocations);
        this.submittedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));
    }

    public Map<UnitId, Province> startingLocations() {
        return startingLocations;
    }

    public List<Order> submittedOrders() {
        return submittedOrders;
    }

    private static Map<UnitId, Province> immutableBoard(
            Map<UnitId, Province> suppliedLocations
    ) {

        Objects.requireNonNull(
                suppliedLocations,
                "startingLocations");

        Map<UnitId, Province> copied = new LinkedHashMap<>();
        Set<Province> occupiedTerritories = new LinkedHashSet<>();

        for (Map.Entry<UnitId, Province> entry : suppliedLocations.entrySet()) {

            UnitId unit = Objects.requireNonNull(
                    entry.getKey(),
                    "startingLocations contains a null unit");

            Province location = Objects.requireNonNull(
                    entry.getValue(),
                    "startingLocations contains a null location");

            Province territory = canonicalProvince(location);

            if (!occupiedTerritories.add(territory))
                throw new IllegalArgumentException(
                        "Multiple units occupy one starting territory: " + territory);

            copied.put(unit, location);

        }

        return Collections.unmodifiableMap(copied);

    }

    private static Province canonicalProvince(Province province) {
        return ( province.parent == null
                ? province
                : province.parent );
    }

}