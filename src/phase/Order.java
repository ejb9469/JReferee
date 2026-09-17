package phase;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

/**
 * IMMUTABLE Order!
 * `phase.Order` is distinct from `adjudication.Order`, which is (fully) mutable.
 */
public final class Order {

    private final Nation owner;
    private final UnitType unitType;
    private final OrderType type;
    private final Province origin;
    private final Province target;
    private final Province auxiliaryTarget;

    // PRIVATE constructor; this class uses static method 'factory' constructors
    private Order(
            Nation owner,
            UnitType unitType,
            OrderType type,
            Province origin,
            Province target,
            Province auxiliaryTarget
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.unitType = Objects.requireNonNull(unitType, "unitType");
        this.type = Objects.requireNonNull(type, "type");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.target = target;
        this.auxiliaryTarget = auxiliaryTarget;
    }

    public static Order hold(
            Nation owner,
            UnitType unitType,
            Province origin
    ) {
        return new Order(owner, unitType, OrderType.HOLD, origin, null, null);
    }

    public static Order move(
            Nation owner,
            UnitType unitType,
            Province origin,
            Province destination
    ) {
        return new Order(
                owner,
                unitType,
                OrderType.MOVE,
                origin,
                Objects.requireNonNull(destination, "destination"),
                null
        );
    }

    public static Order supportHold(
            Nation owner,
            UnitType unitType,
            Province origin,
            Province supportedUnitPosition
    ) {
        return new Order(
                owner,
                unitType,
                OrderType.SUPPORT,
                origin,
                Objects.requireNonNull(
                        supportedUnitPosition,
                        "supportedUnitPosition"
                ),
                null
        );
    }

    public static Order supportMove(
            Nation owner,
            UnitType unitType,
            Province origin,
            Province supportedUnitPosition,
            Province supportedDestination
    ) {
        return new Order(
                owner,
                unitType,
                OrderType.SUPPORT,
                origin,
                Objects.requireNonNull(
                        supportedUnitPosition,
                        "supportedUnitPosition"
                ),
                Objects.requireNonNull(
                        supportedDestination,
                        "supportedDestination"
                )
        );
    }

    public static Order convoy(
            Nation owner,
            Province fleetPosition,
            Province armyOrigin,
            Province armyDestination
    ) {
        return new Order(
                owner,
                UnitType.FLEET,
                OrderType.CONVOY,
                fleetPosition,
                Objects.requireNonNull(armyOrigin, "armyOrigin"),
                Objects.requireNonNull(armyDestination, "armyDestination")
        );
    }


    public Nation owner() {
        return owner;
    }

    public UnitType unitType() {
        return unitType;
    }

    public OrderType type() {
        return type;
    }

    public Province origin() {
        return origin;
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
        return owner == that.owner
                && unitType == that.unitType
                && type == that.type
                && origin == that.origin
                && target == that.target
                && auxiliaryTarget == that.auxiliaryTarget;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                owner,
                unitType,
                type,
                origin,
                target,
                auxiliaryTarget
        );
    }

    @Override
    public String toString() {
        return "Order{"
                + "owner=" + owner
                + ", unitType=" + unitType
                + ", type=" + type
                + ", origin=" + origin
                + ", target=" + target
                + ", auxiliaryTarget=" + auxiliaryTarget
                + '}';
    }

}