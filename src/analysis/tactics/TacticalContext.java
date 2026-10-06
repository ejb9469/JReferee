package analysis.tactics;

import domain.OrderType;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Immutable movement position and known order evidence.<br><br>
 *
 * The board is complete, but the supplied orders may be partial.
 * An absent order is UNKNOWN; it is never implicitly treated as HOLD.
 */
public record TacticalContext(
        String rulesetId,
        GameMoment moment,
        BoardState board,
        Map<UnitId, KnownOrder> orders
) {


    // Construction and validation \\

    public TacticalContext {

        Objects.requireNonNull(rulesetId, "rulesetId");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(orders, "orders");

        if (rulesetId.isBlank())
            throw new IllegalArgumentException(
                    "rulesetId must not be blank");

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException(
                    "Tactical analysis requires a movement phase");

        Map<UnitId, KnownOrder> checked = new LinkedHashMap<>();

        for (Map.Entry<UnitId, KnownOrder> entry : orders.entrySet()) {

            UnitId unit = Objects.requireNonNull(
                    entry.getKey(), "order key");

            KnownOrder known = Objects.requireNonNull(
                    entry.getValue(), "known order");

            if (!board.locations().containsKey(unit))
                throw new IllegalArgumentException(
                        "Inactive issuer: " + unit);

            if (!unit.equals(known.order().unit()))
                throw new IllegalArgumentException(
                        "Order key differs from its issuer: " + unit);

            checked.put(unit, known);

        }

        // Sort by actual board location, not UUID or creation origin.
        List<UnitId> sorted = new ArrayList<>(checked.keySet());
        BoardState position = board;

        sorted.sort(Comparator.comparing(
                unit -> position.locationOf(unit).name()));

        Map<UnitId, KnownOrder> ordered = new LinkedHashMap<>();

        for (UnitId unit : sorted)
            ordered.put(unit, checked.get(unit));

        orders = Collections.unmodifiableMap(ordered);

    }


    // Order and position queries \\

    public Optional<KnownOrder> orderOf(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        if (!board.locations().containsKey(unit))
            throw new IllegalArgumentException(
                    "Inactive unit: " + unit);

        return Optional.ofNullable(orders.get(unit));

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


    // Order evidence \\

    public enum Provenance {

        SUBMITTED,
        PLANNED,
        ASSUMED,
        DEFAULTED

    }

    /**
     * One known order and the source of that knowledge.<br><br>
     *
     * DEFAULTED means a confirmed missing submission, represented as HOLD.
     * It must not be used to stand in for an unknown opponent order.
     */
    public record KnownOrder(
            Order order,
            Provenance provenance
    ) {

        public KnownOrder {

            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(provenance, "provenance");

            // Snapshot the supplied values into the base Order implementation.
            order = new Order(
                    order.unit(),
                    order.orderType(),
                    order.target(),
                    order.auxiliaryTarget());

            // Structure only; geographic legality belongs to adjudication.
            boolean wellFormed = switch (order.orderType()) {

                case HOLD ->
                        order.target() == null
                                && order.auxiliaryTarget() == null;

                case MOVE ->
                        order.target() != null
                                && order.auxiliaryTarget() == null;

                case SUPPORT ->
                        order.target() != null;

                case CONVOY ->
                        order.target() != null
                                && order.auxiliaryTarget() != null;

                default -> false;

            };

            if (!wellFormed)
                throw new IllegalArgumentException(
                        "Malformed movement order: " + order);

            if (provenance == Provenance.DEFAULTED
                    && order.orderType() != OrderType.HOLD)
                throw new IllegalArgumentException(
                        "A defaulted movement order must be HOLD");

        }

    }


}