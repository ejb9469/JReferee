package phase;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

/**
 * Immutable submitted movement order.
 *
 * <p>The issuing {@link UnitId} is persistent across turns. Its current
 * location is supplied separately by MovementInput, rather than being encoded
 * in this order or inferred from UnitId.origin().</p>
 */
public class Order
        implements OrderForm {

    private final UnitId unit;
    private final OrderType type;
    private final Province target;
    private final Province auxiliaryTarget;

    public Order(
            UnitId unit,
            OrderType type,
            Province target,
            Province auxiliaryTarget
    ) {
        this.unit = Objects.requireNonNull(unit, "unit");
        this.type = Objects.requireNonNull(type, "type");
        this.target = target;
        this.auxiliaryTarget = auxiliaryTarget;
    }

    public static Order hold(UnitId unit) {
        return new Order(unit, OrderType.HOLD, null, null);
    }

    public static Order move(
            UnitId unit,
            Province destination
    ) {
        return new Order(
                unit,
                OrderType.MOVE,
                Objects.requireNonNull(destination, "destination"),
                null);
    }

    public static Order supportHold(
            UnitId unit,
            Province supportedUnitPosition
    ) {
        return new Order(
                unit,
                OrderType.SUPPORT,
                Objects.requireNonNull(
                        supportedUnitPosition,
                        "supportedUnitPosition"
                ),
                null);
    }

    public static Order supportMove(
            UnitId unit,
            Province supportedUnitPosition,
            Province supportedDestination
    ) {
        return new Order(
                unit,
                OrderType.SUPPORT,
                Objects.requireNonNull(
                        supportedUnitPosition,
                        "supportedUnitPosition"),
                Objects.requireNonNull(
                        supportedDestination,
                        "supportedDestination")
        );
    }

    public static Order convoy(
            UnitId fleet,
            Province armyPosition,
            Province armyDestination
    ) {

        if (fleet.unitType() != UnitType.FLEET)
            throw new IllegalArgumentException(
                    "Only fleets may receive convoy orders: " + fleet);

        return new Order(
                fleet,
                OrderType.CONVOY,
                Objects.requireNonNull(armyPosition, "armyPosition"),
                Objects.requireNonNull(
                        armyDestination,
                        "armyDestination")
        );

    }

    /*
     * Transitional factories for initial-position tests and callers. Do not
     * use these once a game has persistent UnitId values created by prior
     * adjustment phases.
     */

    @Deprecated
    public static Order hold(
            Nation owner,
            UnitType unitType,
            Province currentLocation
    ) {
        return hold(new UnitId(owner, unitType, currentLocation));
    }

    @Deprecated
    public static Order move(
            Nation owner,
            UnitType unitType,
            Province currentLocation,
            Province destination
    ) {
        return move(
                new UnitId(owner, unitType, currentLocation),
                destination);
    }

    @Deprecated
    public static Order supportHold(
            Nation owner,
            UnitType unitType,
            Province currentLocation,
            Province supportedUnitPosition
    ) {
        return supportHold(
                new UnitId(owner, unitType, currentLocation),
                supportedUnitPosition);
    }

    @Deprecated
    public static Order supportMove(
            Nation owner,
            UnitType unitType,
            Province currentLocation,
            Province supportedUnitPosition,
            Province supportedDestination
    ) {
        return supportMove(
                new UnitId(owner, unitType, currentLocation),
                supportedUnitPosition,
                supportedDestination);
    }

    @Deprecated
    public static Order convoy(
            Nation owner,
            Province fleetLocation,
            Province armyPosition,
            Province armyDestination
    ) {
        return convoy(
                new UnitId(
                        owner,
                        UnitType.FLEET,
                        fleetLocation),
                armyPosition,
                armyDestination
        );
    }

    public UnitId unit() {
        return unit;
    }

    public Nation owner() {
        return unit.owner();
    }

    public UnitType unitType() {
        return unit.unitType();
    }

    public OrderType type() {
        return type;
    }

    public Province target() {
        return target;
    }

    public Province auxiliaryTarget() {
        return auxiliaryTarget;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Order that))
            return false;
        return ( unit.equals(that.unit)
                && type == that.type
                && target == that.target
                && auxiliaryTarget == that.auxiliaryTarget );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                unit,
                type,
                target,
                auxiliaryTarget
        );
    }

    @Override
    public String toString() {
        return "Order{"
                + "unit=" + unit
                + ", type=" + type
                + ", target=" + target
                + ", auxiliaryTarget=" + auxiliaryTarget
                + '}';
    }

}