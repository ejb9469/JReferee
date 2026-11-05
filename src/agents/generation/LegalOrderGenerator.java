package agents.generation;

import contracts.OrderForms;
import domain.Geography;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.GameState;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates per-unit movement orders from a game state.
 *
 * <p>Coast-to-coast Army moves are included as convoy possibilities. The
 * completed candidate must contain orders that provide a usable convoy route.</p>
 */
public final class LegalOrderGenerator {

    public LegalOrderCatalog generate(GameState state, Nation power) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");

        if (!(state instanceof GameState_Movement movementState))
            throw new IllegalArgumentException(
                    "Movement-order generation requires a movement state");

        Map<UnitId, Province> locations = movementState.board().locations();
        for (Map.Entry<UnitId, Province> entry : locations.entrySet())
            requireValidLocation(entry.getKey(), entry.getValue());

        Map<UnitId, List<Order>> optionsByUnit = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId unit = entry.getKey();
            if (unit.owner() == power)
                optionsByUnit.put(
                        unit,
                        generateOrders(unit, entry.getValue(), locations));
        }

        return new LegalOrderCatalog(movementState, power, optionsByUnit);
    }

    private static List<Order> generateOrders(
            UnitId issuer,
            Province currentLocation,
            Map<UnitId, Province> locations
    ) {
        List<Order> orders = new ArrayList<>();
        orders.add(Order.hold(issuer));

        for (Province destination : Province.values())
            if (OrderForms.isPotentialMove(
                    issuer.unitType(),
                    currentLocation,
                    destination))
                orders.add(Order.move(issuer, destination));

        addSupportOrders(orders, issuer, currentLocation, locations);

        if (issuer.unitType() == UnitType.FLEET
                && currentLocation.geography == Geography.WATER)
            addConvoyOrders(orders, issuer, locations);

        return List.copyOf(orders);
    }

    private static void addSupportOrders(
            List<Order> orders,
            UnitId issuer,
            Province supportingLocation,
            Map<UnitId, Province> locations
    ) {
        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId supportedUnit = entry.getKey();
            Province supportedLocation = entry.getValue();

            if (issuer.equals(supportedUnit))
                continue;

            if (OrderForms.canSupportTo(
                    issuer.unitType(),
                    supportingLocation,
                    supportedLocation))
                orders.add(Order.supportHold(issuer, supportedLocation));

            for (Province destination : Province.values()) {
                if (!OrderForms.isPotentialMove(
                        supportedUnit.unitType(),
                        supportedLocation,
                        destination))
                    continue;

                if (OrderForms.canSupportTo(
                        issuer.unitType(),
                        supportingLocation,
                        destination))
                    orders.add(Order.supportMove(
                            issuer,
                            supportedLocation,
                            destination));
            }
        }
    }

    private static void addConvoyOrders(
            List<Order> orders,
            UnitId convoyingFleet,
            Map<UnitId, Province> locations
    ) {
        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId army = entry.getKey();
            Province armyLocation = entry.getValue();

            if (army.unitType() != UnitType.ARMY)
                continue;

            for (Province destination : Province.values())
                if (OrderForms.isPotentialConvoyMove(
                        armyLocation,
                        destination))
                    orders.add(Order.convoy(
                            convoyingFleet,
                            armyLocation,
                            destination));
        }
    }

    private static void requireValidLocation(UnitId unit, Province location) {
        if (!OrderForms.isValidUnitLocation(unit.unitType(), location))
            throw new IllegalArgumentException(
                    "Unit is in a location that does not match its type: "
                            + unit + " at " + location);
    }

}