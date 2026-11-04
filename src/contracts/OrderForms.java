package contracts;

import domain.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Utility class -- highly-genericized common operations for `OrderForm`s shared by adjudication & other code (like the `game` package).
 */
public abstract class OrderForms {

    private OrderForms() { }

    /**
     * Returns a new list containing the supplied order forms.
     * The order-form instances themselves are not copied.
     */
    public static <T extends OrderForm> List<T> shallowCopy(
            Collection<? extends T> orderForms
    ) {
        Objects.requireNonNull(orderForms, "orderForms");

        List<T> copy = new ArrayList<>(orderForms.size());
        for (T orderForm : orderForms)
            copy.add(Objects.requireNonNull(orderForm, "orderForm"));

        return copy;
    }

    /**
     * Returns a new list, copying each order form with {@code copier}.
     */
    public static <T extends OrderForm> List<T> deepCopy(
            Collection<? extends T> orderForms,
            Function<? super T, ? extends T> copier
    ) {
        Objects.requireNonNull(orderForms, "orderForms");
        Objects.requireNonNull(copier, "copier");

        List<T> copy = new ArrayList<>(orderForms.size());
        for (T orderForm : orderForms) {
            T source = Objects.requireNonNull(orderForm, "orderForm");
            copy.add(Objects.requireNonNull(
                    copier.apply(source),
                    "copier returned null"));
        }

        return copy;
    }

    /**
     * Returns the order forms with the requested type, preserving iteration
     * order. A null collection produces an empty list.
     */
    public static <T extends OrderForm> List<T> pruneForOrderType(
            OrderType orderType,
            Collection<? extends T> orderForms
    ) {

        Objects.requireNonNull(orderType, "orderType");

        List<T> result = new ArrayList<>();
        if (orderForms == null)
            return result;

        for (T orderForm : orderForms) {
            Objects.requireNonNull(orderForm, "orderForm");
            if (orderForm.orderType() == orderType)
                result.add(orderForm);
        }

        return result;
    }

    /**
     * Returns move and retreat orders targeting {@code province}, ignoring
     * coast distinctions.
     */
    public static <T extends OrderForm> List<T> locateUnitsMovingToPosition(
            Province province,
            Collection<? extends T> orderForms
    ) {
        Objects.requireNonNull(province, "province");
        Objects.requireNonNull(orderForms, "orderForms");

        List<T> result = new ArrayList<>();
        for (T orderForm : orderForms) {
            Objects.requireNonNull(orderForm, "orderForm");
            OrderType type = orderForm.orderType();

            if ((type == OrderType.MOVE || type == OrderType.RETREAT)
                    && Province.equalsIgnoreCoast(orderForm.target(), province))
                result.add(orderForm);
        }

        return result;
    }

    /**
     * Checks the structural and geometric rules for a movement-phase order.
     * Convoy availability and support matching depend on other orders and are
     * not checked here.
     */
    public static boolean isValidMovementOrder(
            OrderForm order,
            Province currentLocation) {

        if (order == null
                || currentLocation == null
                || order.unitType() == null
                || order.orderType() == null)
            return false;

        Province target = order.target();
        Province auxiliaryTarget = order.auxiliaryTarget();

        return switch (order.orderType()) {
            case HOLD -> target == null && auxiliaryTarget == null;

            case MOVE -> {
                if (target == null
                        || auxiliaryTarget != null
                        || Province.equalsIgnoreCoast(currentLocation, target))
                    yield false;

                if (order.unitType() == UnitType.ARMY)
                    yield target.geography != Geography.WATER
                            && target.coastType != CoastType.SPLIT;

                yield target.geography != Geography.INLAND
                        && currentLocation.isAdjacentTo(target);
            }

            case SUPPORT -> {
                if (target == null)
                    yield false;

                Province supportedDestination = auxiliaryTarget == null
                        ? target : auxiliaryTarget;

                if (order.unitType() == UnitType.ARMY
                        && (supportedDestination.geography == Geography.WATER
                        || supportedDestination.coastType == CoastType.SPLIT))
                    yield false;

                if (order.unitType() == UnitType.FLEET
                        && supportedDestination.geography == Geography.INLAND
                        && !supportedDestination.hasCoast())
                    yield false;

                if (Province.equalsIgnoreCoast(target, auxiliaryTarget))
                    yield false;

                yield currentLocation.isAdjacentToIgnoreSplitCoast(
                        supportedDestination)
                        && (order.unitType() != UnitType.FLEET
                        || Province.adjacentBySea(
                        currentLocation,
                        supportedDestination));
            }

            case CONVOY -> order.unitType() == UnitType.FLEET
                    && currentLocation.geography == Geography.WATER
                    && target != null
                    && auxiliaryTarget != null
                    && !Province.equalsIgnoreCoast(target, auxiliaryTarget);

            default -> false;
        };

    }

    /**
     * Checks whether {@code convoyPath} forms a route for the supplied Army
     * move. The resolver must return each convoying unit's current location.
     */
    public static <M extends OrderForm, C extends OrderForm>
        boolean convoyPathIsValid(
            M armyMove,
            Province armyLocation,
            List<? extends C> convoyPath,
            Function<? super C, Province> currentLocation) {

        Objects.requireNonNull(convoyPath, "convoyPath");
        Objects.requireNonNull(currentLocation, "currentLocation");

        if (armyMove == null
                || armyLocation == null
                || armyMove.orderType() != OrderType.MOVE
                || armyMove.unitType() != UnitType.ARMY
                || !isValidMovementOrder(armyMove, armyLocation)
                || convoyPath.isEmpty())
            return false;

        Set<C> visited = new HashSet<>();
        Province previousLocation = null;

        for (C convoy : convoyPath) {
            if (!matchesConvoy(
                    convoy,
                    armyLocation,
                    armyMove.target(),
                    currentLocation)
                    || !visited.add(convoy))
                return false;

            Province fleetLocation = currentLocation.apply(convoy);
            if (previousLocation == null) {
                if (!fleetLocation.isAdjacentTo(armyLocation))
                    return false;
            } else if (!previousLocation.isAdjacentTo(fleetLocation)) {
                return false;
            }

            previousLocation = fleetLocation;
        }

        return previousLocation.isAdjacentTo(armyMove.target());

    }

    /**
     * Returns one matching convoy path, or an empty list if no path is found.
     * The resolver must return each convoying unit's current location.
     */
    public static <M extends OrderForm, C extends OrderForm> List<C> drawConvoyPath(
            M armyMove,
            Province armyLocation,
            Collection<? extends C> convoyOrders,
            Function<? super C, Province> currentLocation
    ) {
        Objects.requireNonNull(convoyOrders, "convoyOrders");
        Objects.requireNonNull(currentLocation, "currentLocation");

        if (armyMove == null
                || armyLocation == null
                || armyMove.orderType() != OrderType.MOVE
                || armyMove.unitType() != UnitType.ARMY
                || !isValidMovementOrder(armyMove, armyLocation))
            return new ArrayList<>();

        List<C> matchingConvoys = new ArrayList<>();
        for (C convoy : convoyOrders)
            if (matchesConvoy(
                    convoy,
                    armyLocation,
                    armyMove.target(),
                    currentLocation))
                matchingConvoys.add(convoy);

        for (C first : matchingConvoys) {
            Province firstLocation = currentLocation.apply(first);
            if (!firstLocation.isAdjacentTo(armyLocation))
                continue;

            List<C> path = new ArrayList<>();
            if (findPath(
                    first,
                    armyMove.target(),
                    matchingConvoys,
                    currentLocation,
                    new HashSet<>(),
                    path))
                return path;
        }

        return new ArrayList<>();
    }

    /**
     * Returns matching convoy orders adjacent to {@code convoyOrder}.
     */
    public static <T extends OrderForm> List<T> findAdjacentConvoys(
            T convoyOrder,
            Collection<? extends T> excludedOrders,
            Collection<? extends T> orders,
            Function<? super T, Province> currentLocation
    ) {
        Objects.requireNonNull(convoyOrder, "convoyOrder");
        Objects.requireNonNull(excludedOrders, "excludedOrders");
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(currentLocation, "currentLocation");

        List<T> result = new ArrayList<>();
        Province convoyLocation = currentLocation.apply(convoyOrder);

        if (convoyOrder.orderType() != OrderType.CONVOY
                || !isValidMovementOrder(convoyOrder, convoyLocation))
            return result;

        for (T order : orders) {
            if (order == null
                    || excludedOrders.contains(order)
                    || order.equals(convoyOrder)
                    || !matchesConvoy(
                    order,
                    convoyOrder.target(),
                    convoyOrder.auxiliaryTarget(),
                    currentLocation))
                continue;

            if (currentLocation.apply(order).isAdjacentTo(convoyLocation))
                result.add(order);
        }

        return result;
    }

    private static <M extends OrderForm, C extends OrderForm> boolean findPath(
            C current,
            Province destination,
            List<C> convoyOrders,
            Function<? super C, Province> currentLocation,
            Set<C> visited,
            List<C> path
    ) {
        if (!visited.add(current))
            return false;

        Province currentProvince = currentLocation.apply(current);
        path.add(current);

        if (currentProvince.isAdjacentTo(destination))
            return true;

        for (C next : convoyOrders) {
            if (visited.contains(next))
                continue;

            Province nextProvince = currentLocation.apply(next);
            if (currentProvince.isAdjacentTo(nextProvince)
                    && findPath(
                    next,
                    destination,
                    convoyOrders,
                    currentLocation,
                    visited,
                    path))
                return true;
        }

        path.removeLast();
        return false;
    }

    private static <C extends OrderForm> boolean matchesConvoy(
            C convoy,
            Province armyLocation,
            Province destination,
            Function<? super C, Province> currentLocation
    ) {
        if (convoy == null
                || convoy.orderType() != OrderType.CONVOY
                || convoy.target() != armyLocation
                || convoy.auxiliaryTarget() != destination)
            return false;

        Province fleetLocation = currentLocation.apply(convoy);
        return fleetLocation != null
                && isValidMovementOrder(convoy, fleetLocation);
    }

}