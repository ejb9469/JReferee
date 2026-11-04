package agents.generation;

import domain.Nation;
import domain.OrderType;
import game.BoardState;
import game.GameMoment;
import game.GameState;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable movement-order options for every unit owned by one power.
 *
 * <p>Each list contains orders for one issuing unit. Relationships between
 * orders are checked when a complete candidate is built.</p>
 */
public final class LegalOrderCatalog {

    private final GameMoment moment;
    private final Nation power;
    private final BoardState board;
    private final Map<UnitId, List<Order>> optionsByUnit;

    LegalOrderCatalog(
            GameState_Movement state,
            Nation power,
            Map<UnitId, List<Order>> optionsByUnit
    ) {
        Objects.requireNonNull(state, "state");
        this.power = Objects.requireNonNull(power, "power");
        this.moment = state.moment();
        this.board = state.board();
        this.optionsByUnit = copyAndValidate(optionsByUnit);
    }

    public GameMoment moment() {
        return moment;
    }

    public Nation power() {
        return power;
    }

    public Map<UnitId, List<Order>> optionsByUnit() {
        return optionsByUnit;
    }

    /**
     * Returns the available orders for {@code unit}, or an empty list if it is
     * not in this catalog.
     */
    public List<Order> optionsFor(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return optionsByUnit.getOrDefault(unit, List.of());
    }

    public boolean contains(Order order) {
        Objects.requireNonNull(order, "order");
        return optionsFor(order.unit()).contains(order);
    }

    /**
     * Checks that this catalog was generated for the supplied movement state
     * and power.
     */
    public void requireMatches(GameState state, Nation power) {

        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");

        if (!(state instanceof GameState_Movement movementState))
            throw new IllegalArgumentException(
                    "A movement-order catalog requires a movement state");

        if (this.power != power
                || !moment.equals(movementState.moment())
                || !board.equals(movementState.board()))
            throw new IllegalArgumentException(
                    "The order catalog does not match the supplied state and power");

    }

    private Map<UnitId, List<Order>> copyAndValidate(
                        Map<UnitId, List<Order>> suppliedOptions) {

        Objects.requireNonNull(suppliedOptions, "optionsByUnit");

        Map<UnitId, List<Order>> copied = new LinkedHashMap<>();

        for (Map.Entry<UnitId, List<Order>> entry : suppliedOptions.entrySet()) {
            UnitId unit = Objects.requireNonNull(entry.getKey(), "catalog unit");
            List<Order> suppliedOrders = Objects.requireNonNull(
                    entry.getValue(),
                    "orders for " + unit);

            if (!board.locations().containsKey(unit))
                throw new IllegalArgumentException(
                        "Catalog unit is absent from the board: " + unit);

            if (unit.owner() != power)
                throw new IllegalArgumentException(
                        "Catalog contains a unit owned by another power: " + unit);

            List<Order> orders = new ArrayList<>(suppliedOrders.size());
            Set<Order> seen = new LinkedHashSet<>();
            boolean hasHold = false;

            for (Order order : suppliedOrders) {
                Objects.requireNonNull(order, "catalog order");

                if (!unit.equals(order.unit()))
                    throw new IllegalArgumentException(
                            "Order issuer does not match its catalog unit: " + order);

                if (order.owner() != power)
                    throw new IllegalArgumentException(
                            "Catalog order is owned by another power: " + order);

                requireMovementOrderShape(order);

                if (!seen.add(order))
                    throw new IllegalArgumentException(
                            "Duplicate order in catalog: " + order);

                if (order.orderType() == OrderType.HOLD) {
                    if (!order.equals(Order.hold(unit)))
                        throw new IllegalArgumentException(
                                "Hold order has unexpected targets: " + order);
                    hasHold = true;
                }

                orders.add(order);
            }

            if (!hasHold)
                throw new IllegalArgumentException(
                        "Catalog has no hold order for " + unit);

            copied.put(unit, List.copyOf(orders));
        }

        Set<UnitId> expectedUnits = new LinkedHashSet<>();
        for (UnitId unit : board.locations().keySet())
            if (unit.owner() == power)
                expectedUnits.add(unit);

        if (!copied.keySet().equals(expectedUnits))
            throw new IllegalArgumentException(
                    "Catalog units do not match the units owned by " + power);

        return Collections.unmodifiableMap(copied);

    }

    private static void requireMovementOrderShape(Order order) {

        switch (order.orderType()) {
            case HOLD -> {
                if (order.target() != null || order.auxiliaryTarget() != null)
                    throw new IllegalArgumentException(
                            "Hold order has targets: " + order);
            }
            case MOVE -> {
                if (order.target() == null || order.auxiliaryTarget() != null)
                    throw new IllegalArgumentException(
                            "Malformed move order: " + order);
            }
            case SUPPORT -> {
                if (order.target() == null)
                    throw new IllegalArgumentException(
                            "Malformed support order: " + order);
            }
            case CONVOY -> {
                if (order.target() == null || order.auxiliaryTarget() == null)
                    throw new IllegalArgumentException(
                            "Malformed convoy order: " + order);
            }
            default -> throw new IllegalArgumentException(
                    "Order is not valid in a movement catalog: " + order);
        }

    }

}