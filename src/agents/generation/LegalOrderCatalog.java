package agents.generation;

import contracts.OrderForm;
import contracts.OrderForms;
import domain.Nation;
import domain.Province;
import game.GameMoment;
import game.GameState;
import game.GameState_Movement;
import game.GameState_Retreats;
import org.jetbrains.annotations.Nullable;
import phase.Order;
import phase.UnitId;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Immutable per-unit order options for one nation in movement or retreats.
 *
 * <p>Retreat units are the nation's dislodged units. Their lists contain legal
 * retreat orders; omission is an additional choice which destroys the unit.
 * An empty retreat list therefore permits only omission.</p>
 */
public final class LegalOrderCatalog {

    private final GameState state;
    private final Nation power;
    private final Map<UnitId, Province> unitLocations;
    private final Map<UnitId, List<OrderForm>> optionsByUnit;

    LegalOrderCatalog(
            GameState state,
            Nation power,
            Map<UnitId, ? extends List<? extends OrderForm>> optionsByUnit
    ) {
        this.state = Objects.requireNonNull(state, "state");
        this.power = Objects.requireNonNull(power, "power");

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        if (state instanceof GameState_Movement movement) {
            movement.board().locations().forEach((unit, location) -> {
                if (unit.owner() == power)
                    locations.put(unit, location);
            });
        } else if (state instanceof GameState_Retreats retreats) {
            retreats.movementResult().dislodgements().forEach((unit, dislodgement) -> {
                if (unit.owner() == power)
                    locations.put(unit, dislodgement.displacedFrom());
            });
        } else {
            throw new IllegalArgumentException(
                    "Order catalogs currently support movement and retreats");
        }

        this.unitLocations = Collections.unmodifiableMap(locations);
        this.optionsByUnit = copyAndValidate(optionsByUnit);
    }

    public GameMoment moment() {
        return state.moment();
    }

    public Nation power() {
        return power;
    }

    public Map<UnitId, Province> unitLocations() {
        return unitLocations;
    }

    public Map<UnitId, List<OrderForm>> optionsByUnit() {
        return optionsByUnit;
    }

    public List<OrderForm> optionsFor(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return optionsByUnit.getOrDefault(unit, List.of());
    }

    public boolean allowsOmission() {
        return state instanceof GameState_Retreats;
    }

    public boolean contains(OrderForm order) {
        Objects.requireNonNull(order, "order");
        UnitId unit = unitOf(order);
        return unit != null && optionsFor(unit).contains(order);
    }

    /**
     * Retreat catalogs are tied to their source MovementResult. A matching
     * moment and board alone cannot establish equal retreat restrictions.
     */
    public void requireMatches(GameState state, Nation power) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");

        if (this.power != power || !this.state.equals(state))
            throw new IllegalArgumentException(
                    "The order catalog does not match the supplied state and power");
    }

    boolean acceptsPhase(OrderForm order) {
        if (allowsOmission())
            return order instanceof RetreatOrder;

        if (order.getClass() != Order.class)
            return false;

        return switch (order.orderType()) {
            case HOLD, MOVE, SUPPORT, CONVOY -> true;
            default -> false;
        };
    }

    static @Nullable UnitId unitOf(OrderForm order) {
        if (order instanceof Order movement)
            return movement.unit();
        if (order instanceof RetreatOrder retreat)
            return retreat.unit();
        return null;
    }

    private Map<UnitId, List<OrderForm>> copyAndValidate(
            Map<UnitId, ? extends List<? extends OrderForm>> suppliedOptions
    ) {
        Objects.requireNonNull(suppliedOptions, "optionsByUnit");
        if (!suppliedOptions.keySet().equals(unitLocations.keySet()))
            throw new IllegalArgumentException(
                    "Catalog units do not match the units eligible to act for " + power);

        Map<UnitId, List<OrderForm>> copied = new LinkedHashMap<>();
        for (UnitId unit : unitLocations.keySet()) {
            List<OrderForm> orders = OrderForms.shallowCopy(
                    Objects.requireNonNull(suppliedOptions.get(unit), "orders for " + unit));
            Set<OrderForm> seen = new HashSet<>();

            for (OrderForm order : orders) {
                if (!acceptsPhase(order) || !unit.equals(unitOf(order)))
                    throw new IllegalArgumentException(
                            "Order phase or issuer does not match its catalog entry: " + order);

                boolean legal = state instanceof GameState_Retreats retreats
                        && order instanceof RetreatOrder retreat
                        ? RetreatProcessor.isLegalRetreat(
                        retreat, retreats.movementResult())
                        : OrderForms.isValidMovementOrder(order, unitLocations.get(unit));
                if (!legal)
                    throw new IllegalArgumentException("Invalid order in catalog: " + order);
                if (!seen.add(order))
                    throw new IllegalArgumentException("Duplicate order in catalog: " + order);
            }

            if (!allowsOmission() && !orders.contains(Order.hold(unit)))
                throw new IllegalArgumentException("Catalog has no hold order for " + unit);

            copied.put(unit, List.copyOf(orders));
        }

        return Collections.unmodifiableMap(copied);
    }

}