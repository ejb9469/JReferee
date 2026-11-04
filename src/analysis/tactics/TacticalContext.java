package analysis.tactics;

import domain.OrderType;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Immutable movement position, known orders, and their provenance.<br><br>
 *
 * The board is complete, but the supplied orders may be partial.
 * An absent order is UNKNOWN; it is never implicitly treated as HOLD.
 */
public record TacticalContext(
        String rulesetId,
        GameMoment moment,
        BoardState board,
        Map<UnitId, Order> orders,
        Map<UnitId, Provenance> provenance
) {


    // Construction and validation \\

    public TacticalContext {

        Objects.requireNonNull(rulesetId, "rulesetId");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(provenance, "provenance");

        if (rulesetId.isBlank())
            throw new IllegalArgumentException(
                    "rulesetId must not be blank");

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException(
                    "Tactical analysis requires a movement phase");

        Map<UnitId, Order> checkedOrders = new LinkedHashMap<>();
        Map<UnitId, Provenance> checkedProvenance = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Order> entry : orders.entrySet()) {

            UnitId unit = Objects.requireNonNull(
                    entry.getKey(), "order key");

            Order order = Objects.requireNonNull(
                    entry.getValue(), "order");
            Provenance source = Objects.requireNonNull(
                    provenance.get(unit), "order provenance");

            if (!board.locations().containsKey(unit))
                throw new IllegalArgumentException(
                        "Inactive issuer: " + unit);

            if (!unit.equals(order.unit()))
                throw new IllegalArgumentException(
                        "Order key differs from its issuer: " + unit);

            Order snapshot = new Order(
                    order.unit(),
                    order.orderType(),
                    order.target(),
                    order.auxiliaryTarget());

            if (!wellFormed(snapshot))
                throw new IllegalArgumentException(
                        "Malformed movement order: " + snapshot);

            if (source == Provenance.DEFAULTED
                    && snapshot.orderType() != OrderType.HOLD)
                throw new IllegalArgumentException(
                        "A defaulted movement order must be HOLD");

            checkedOrders.put(unit, snapshot);
            checkedProvenance.put(unit, source);

        }


        if (provenance.size() != checkedProvenance.size())
            throw new IllegalArgumentException(
                    "Order provenance keys differ from order keys");

        // Sort by actual board location, not UUID or creation origin.
        List<UnitId> sorted = new ArrayList<>(checkedOrders.keySet());
        BoardState position = board;

        sorted.sort(Comparator.comparing(
                unit -> position.locationOf(unit).name()));

        Map<UnitId, Order> orderedOrders = new LinkedHashMap<>();
        Map<UnitId, Provenance> orderedProvenance = new LinkedHashMap<>();

        for (UnitId unit : sorted) {
            orderedOrders.put(unit, checkedOrders.get(unit));
            orderedProvenance.put(unit, checkedProvenance.get(unit));
        }

        orders = Collections.unmodifiableMap(orderedOrders);
        provenance = Collections.unmodifiableMap(orderedProvenance);

    }

    /**
     * Creates a context where every supplied order is marked as submitted.
     */
    public TacticalContext(
            String rulesetId,
            GameMoment moment,
            BoardState board,
            Map<UnitId, Order> orders
    ) {
        this(rulesetId, moment, board, orders, submittedProvenance(orders));
    }


    // Order and position queries \\

    public Optional<Order> orderOf(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        if (!board.locations().containsKey(unit))
            throw new IllegalArgumentException(
                    "Inactive unit: " + unit);

        return Optional.ofNullable(orders.get(unit));

    }

    public Optional<Provenance> provenanceOf(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        if (!board.locations().containsKey(unit))
            throw new IllegalArgumentException(
                    "Inactive unit: " + unit);

        return Optional.ofNullable(provenance.get(unit));

    }

    /**
     * Locates a unit by canonical territory for pattern recognition.<br><br>
     *
     * This lookup does not alter the exact coast-bearing locations or
     * destinations retained by the board and submitted orders.
     */
    public Optional<UnitId> unitAt(Province province) {

        Objects.requireNonNull(province, "province");

        Province territory = Province.canonical(province);

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (Province.canonical(entry.getValue()) == territory)
                return Optional.of(entry.getKey());

        return Optional.empty();

    }


    // Completeness \\

    public List<UnitId> unknownUnits() {

        List<UnitId> unknown = new ArrayList<>();

        for (UnitId unit : board.locations().keySet())
            if (!orders.containsKey(unit))
                unknown.add(unit);

        unknown.sort(Comparator.comparing(
                unit -> board.locationOf(unit).name()));

        return List.copyOf(unknown);

    }

    public boolean complete() {
        return orders.size() == board.locations().size();
    }


    // Order provenance \\

    public enum Provenance {

        SUBMITTED,
        PLANNED,
        ASSUMED,
        DEFAULTED

    }

    private static boolean wellFormed(Order order) {
        return switch (order.orderType()) {
            case HOLD ->
                    order.target() == null
                            && order.auxiliaryTarget() == null;
            case MOVE ->
                    order.target() != null
                            && order.auxiliaryTarget() == null;
            case SUPPORT -> order.target() != null;
            case CONVOY ->
                    order.target() != null
                            && order.auxiliaryTarget() != null;
            default -> false;
        };
    }

    private static Map<UnitId, Provenance> submittedProvenance(
            Map<UnitId, Order> orders
    ) {
        Objects.requireNonNull(orders, "orders");
        Map<UnitId, Provenance> provenance = new LinkedHashMap<>();
        for (UnitId unit : orders.keySet())
            provenance.put(unit, Provenance.SUBMITTED);
        return provenance;
    }

}