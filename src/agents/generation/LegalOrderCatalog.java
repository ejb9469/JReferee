package agents.generation;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import game.GameState;
import game.GameState_Adjustments;
import game.GameState_Movement;
import game.GameState_Retreats;
import game.GamePhase;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Order options generated for one nation in one phase.
 */
public record LegalOrderCatalog(
        GameState state,
        Nation power,
        List<UnitOptions> unitOptions,
        List<AdjustmentOrder> adjustmentOptions
) {

    public LegalOrderCatalog {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");
        unitOptions = List.copyOf(
                Objects.requireNonNull(unitOptions, "unitOptions"));
        adjustmentOptions = List.copyOf(
                Objects.requireNonNull(adjustmentOptions, "adjustmentOptions"));

        validate(state, power, unitOptions, adjustmentOptions);
    }

    /**
     * Throws if this catalog was generated for another state or nation.
     */
    public void requireMatches(GameState expectedState, Nation expectedPower) {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(expectedPower, "expectedPower");

        if (!state.equals(expectedState) || power != expectedPower)
            throw new IllegalArgumentException(
                    "Legal order catalog does not match the action-generation request");
    }

    private static void validate(
            GameState state,
            Nation power,
            List<UnitOptions> unitOptions,
            List<AdjustmentOrder> adjustmentOptions
    ) {
        if (state instanceof GameState_Movement movementState) {
            if (!adjustmentOptions.isEmpty())
                throw new IllegalArgumentException(
                        "Movement catalog cannot contain adjustment orders");

            validateUnitOptions(
                    power,
                    movementUnits(movementState, power),
                    unitOptions,
                    false);
            return;
        }

        if (state instanceof GameState_Retreats retreatState) {
            if (!adjustmentOptions.isEmpty())
                throw new IllegalArgumentException(
                        "Retreat catalog cannot contain adjustment orders");

            validateUnitOptions(
                    power,
                    retreatUnits(retreatState, power),
                    unitOptions,
                    true);
            return;
        }

        if (state instanceof GameState_Adjustments) {
            if (!unitOptions.isEmpty())
                throw new IllegalArgumentException(
                        "Adjustment catalog cannot contain per-unit options");

            for (AdjustmentOrder order : adjustmentOptions)
                if (order.owner() != power)
                    throw new IllegalArgumentException(
                            "Adjustment option belongs to another nation: " + order);
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported game state: " + state.getClass().getName());
    }

    private static Map<UnitId, Province> movementUnits(
            GameState_Movement state,
            Nation power
    ) {
        Map<UnitId, Province> units = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : state.board().locations().entrySet())
            if (entry.getKey().owner() == power)
                units.put(entry.getKey(), entry.getValue());

        return units;
    }

    private static Map<UnitId, Province> retreatUnits(
            GameState_Retreats state,
            Nation power
    ) {
        Map<UnitId, Province> units = new LinkedHashMap<>();
        Map<UnitId, MovementResult.Dislodgement> dislodgements =
                state.movementResult().dislodgements();

        for (Map.Entry<UnitId, MovementResult.Dislodgement> entry
                : dislodgements.entrySet())
            if (entry.getKey().owner() == power)
                units.put(entry.getKey(), entry.getValue().displacedFrom());

        return units;
    }

    private static void validateUnitOptions(
            Nation power,
            Map<UnitId, Province> expectedLocations,
            List<UnitOptions> unitOptions,
            boolean retreatPhase
    ) {
        Map<UnitId, UnitOptions> optionsByUnit = new LinkedHashMap<>();

        for (UnitOptions options : unitOptions) {
            Province expectedLocation = expectedLocations.get(options.unit());

            if (expectedLocation == null)
                throw new IllegalArgumentException(
                        "Unit is not orderable by this nation: " + options.unit());

            if (expectedLocation != options.location())
                throw new IllegalArgumentException(
                        "Unit option has the wrong phase location: " + options.unit());

            if (optionsByUnit.putIfAbsent(options.unit(), options) != null)
                throw new IllegalArgumentException(
                        "Multiple option groups for unit: " + options.unit());

            if (!retreatPhase && options.orders().isEmpty())
                throw new IllegalArgumentException(
                        "Movement unit must have at least one order option: " + options.unit());

            for (OrderForm order : options.orders()) {
                if (order.owner() != power)
                    throw new IllegalArgumentException(
                            "Unit order belongs to another nation: " + order);

                if (retreatPhase) {
                    if (!(order instanceof RetreatOrder retreatOrder)
                            || !retreatOrder.unit().equals(options.unit()))
                        throw new IllegalArgumentException(
                                "Retreat option does not match its unit: " + order);
                } else {
                    if (!(order instanceof Order movementOrder)
                            || !movementOrder.unit().equals(options.unit())
                            || !isMovementOrder(movementOrder.orderType()))
                        throw new IllegalArgumentException(
                                "Movement option does not match its unit: " + order);
                }
            }
        }

        if (!optionsByUnit.keySet().equals(expectedLocations.keySet()))
            throw new IllegalArgumentException(
                    "Catalog must include every orderable unit");
    }

    private static boolean isMovementOrder(OrderType type) {
        return switch (type) {
            case MOVE, HOLD, SUPPORT, CONVOY -> true;
            default -> false;
        };
    }

    /**
     * Options for one unit at its location for this phase. Retreat locations
     * are the provinces from which the units were dislodged.
     */
    public record UnitOptions(
            UnitId unit,
            Province location,
            List<OrderForm> orders
    ) {

        public UnitOptions {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(location, "location");
            orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        }

        public UnitOptions(
                UnitId unit,
                Province location,
                Collection<? extends OrderForm> orders
        ) {
            this(unit, location, copyOrders(orders));
        }

        private static List<OrderForm> copyOrders(
                Collection<? extends OrderForm> orders
        ) {
            Objects.requireNonNull(orders, "orders");
            List<OrderForm> copy = new ArrayList<>(orders.size());
            copy.addAll(orders);
            return copy;
        }

    }

}