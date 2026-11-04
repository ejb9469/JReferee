package agents.generation;

import domain.CoastType;
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
            if (isPotentialMove(issuer.unitType(), currentLocation, destination))
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

            if (canSupportTo(
                    issuer.unitType(),
                    supportingLocation,
                    supportedLocation))
                orders.add(Order.supportHold(issuer, supportedLocation));

            for (Province destination : Province.values()) {
                if (!isPotentialMove(
                        supportedUnit.unitType(),
                        supportedLocation,
                        destination))
                    continue;

                if (canSupportTo(
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
                if (isPotentialConvoyMove(armyLocation, destination))
                    orders.add(Order.convoy(
                            convoyingFleet,
                            armyLocation,
                            destination));
        }
    }

    private static boolean isPotentialMove(
            UnitType unitType,
            Province origin,
            Province destination
    ) {
        if (Province.equalsIgnoreCoast(origin, destination))
            return false;

        if (unitType == UnitType.ARMY) {
            if (!isLandProvince(destination)
                    || destination.coastType == CoastType.SPLIT)
                return false;

            return origin.isAdjacentTo(destination)
                    || (origin.hasCoast() && destination.hasCoast());
        }

        if (destination.geography != Geography.WATER
                && destination.geography != Geography.COASTAL)
            return false;

        return origin.isAdjacentTo(destination);
    }

    private static boolean canSupportTo(
            UnitType supporterType,
            Province supportingLocation,
            Province destination
    ) {
        if (supporterType == UnitType.ARMY)
            return isLandProvince(destination)
                    && destination.coastType != CoastType.SPLIT
                    && supportingLocation.isAdjacentToIgnoreSplitCoast(destination);

        if (destination.geography != Geography.WATER
                && destination.geography != Geography.COASTAL
                && !destination.hasCoast())
            return false;

        return supportingLocation.isAdjacentToIgnoreSplitCoast(destination)
                && Province.adjacentBySea(supportingLocation, destination);
    }

    private static boolean isPotentialConvoyMove(
            Province origin,
            Province destination) {
        return isLandProvince(destination)
                && destination.coastType != CoastType.SPLIT
                && origin.hasCoast()
                && destination.hasCoast()
                && !Province.equalsIgnoreCoast(origin, destination);
    }

    private static boolean isLandProvince(Province province) {
        return province.geography == Geography.INLAND
                || province.geography == Geography.COASTAL;
    }

    private static void requireValidLocation(UnitId unit, Province location) {
        boolean valid =
                switch (unit.unitType()) {
                    case ARMY -> isLandProvince(location)
                            && location.coastType != CoastType.SPLIT;
                    case FLEET -> location.geography == Geography.WATER
                            || location.geography == Geography.COASTAL;
                };

        if (!valid)
            throw new IllegalArgumentException(
                    "Unit is in a location that does not match its type: "
                            + unit + " at " + location);
    }

}