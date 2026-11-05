package contracts;

import domain.*;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Utility class -- highly-genericized common operations for `OrderForm`s shared by adjudication & other code (like the `game` package).<br><br>
 *
 * See also: `adjudication.util.Orders` and `adjudication.util.Convoys` - from where most of this logic originated
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
            @Nullable   OrderType orderType,  // may be null in the case of `phase.UnitId`
                        Collection<? extends T> orderForms
    ) {

        // we cannot omit null order types b/c `pruneForOrderType(null,mixed) may occur, ...
        // ... in the case of `phase.UnitId` (impl. `OrderForm`) - where a null order type refers to a unit without an order
        //Objects.requireNonNull(orderType, "orderType");

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
     * Checks whether a unit can occupy {@code location}.
     */
    public static boolean isValidUnitLocation(
            UnitType unitType,
            Province location
    ) {
        if (unitType == null || location == null)
            return false;

        return switch (unitType) {
            case ARMY -> isLandProvince(location)
                    && location.coastType != CoastType.SPLIT;
            case FLEET -> location.geography == Geography.WATER
                    || location.geography == Geography.COASTAL;
        };
    }

    /**
     * Checks whether the unit may move directly or by a possible convoy.
     * Convoy availability is not checked.
     */
    public static boolean isPotentialMove(
            UnitType unitType,
            Province origin,
            Province destination
    ) {
        if (!isValidUnitLocation(unitType, origin)
                || !isValidUnitLocation(unitType, destination)
                || Province.equalsIgnoreCoast(origin, destination))
            return false;

        if (unitType == UnitType.ARMY)
            return origin.isAdjacentTo(destination)
                    || isPotentialConvoyMove(origin, destination);

        return origin.isAdjacentTo(destination)
                && (origin.geography != Geography.COASTAL
                || destination.geography != Geography.COASTAL
                || Province.adjacentBySea(origin, destination));
    }

    /**
     * Checks whether two Army locations can be endpoints of a convoy.
     */
    public static boolean isPotentialConvoyMove(
            Province origin,
            Province destination
    ) {
        return isValidUnitLocation(UnitType.ARMY, origin)
                && isValidUnitLocation(UnitType.ARMY, destination)
                && origin.hasCoast()
                && destination.hasCoast()
                && !Province.equalsIgnoreCoast(origin, destination);
    }

    /**
     * Checks whether a unit at {@code supportingLocation} can support into
     * {@code destination}.
     */
    public static boolean canSupportTo(
            UnitType supporterType,
            Province supportingLocation,
            Province destination
    ) {
        if (!isValidUnitLocation(supporterType, supportingLocation)
                || destination == null)
            return false;

        if (supporterType == UnitType.ARMY) {
            Province territory = Province.canonical(destination);
            return isValidUnitLocation(UnitType.ARMY, territory)
                    && supportingLocation.isAdjacentTo(territory);
        }

        boolean fleetCanSupportTo = destination.geography == Geography.WATER
                || destination.geography == Geography.COASTAL
                || destination.hasCoast();

        return fleetCanSupportTo
                && supportingLocation.isAdjacentToIgnoreSplitCoast(destination)
                && Province.adjacentBySea(supportingLocation, destination);
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
                || order.orderType() == null
                || !isValidUnitLocation(order.unitType(), currentLocation))
            return false;

        Province target = order.target();
        Province auxiliaryTarget = order.auxiliaryTarget();

        return switch (order.orderType()) {
            case HOLD -> target == null && auxiliaryTarget == null;

            case MOVE -> target != null
                    && auxiliaryTarget == null
                    && isPotentialMove(
                    order.unitType(),
                    currentLocation,
                    target);

            case SUPPORT -> {
                if (target == null
                        || Province.equalsIgnoreCoast(target, auxiliaryTarget))
                    yield false;

                Province supportedDestination = auxiliaryTarget == null
                        ? target : auxiliaryTarget;

                yield canSupportTo(
                        order.unitType(),
                        currentLocation,
                        supportedDestination);
            }

            case CONVOY -> order.unitType() == UnitType.FLEET
                    && currentLocation.geography == Geography.WATER
                    && isPotentialConvoyMove(target, auxiliaryTarget);

            default -> false;
        };

    }

    /**
     * Checks the structural and geometric rules for a retreat order.
     * Occupancy, standoffs, and the dislodging attack are not checked here.
     */
    public static boolean isValidRetreatOrder(
            OrderForm order,
            Province currentLocation
    ) {
        if (order == null
                || currentLocation == null
                || order.unitType() == null
                || !isValidUnitLocation(order.unitType(), currentLocation)
                || order.orderType() != OrderType.RETREAT
                || order.target() == null
                || order.auxiliaryTarget() != null
                || Province.equalsIgnoreCoast(currentLocation, order.target()))
            return false;

        Province destination = order.target();

        return switch (order.unitType()) {
            case ARMY -> isValidUnitLocation(UnitType.ARMY, destination)
                    && currentLocation.isAdjacentTo(destination);

            case FLEET -> isPotentialMove(
                    UnitType.FLEET, currentLocation, destination);
        };

    }

    private static boolean isLandProvince(Province province) {
        return province.geography == Geography.INLAND
                || province.geography == Geography.COASTAL;
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