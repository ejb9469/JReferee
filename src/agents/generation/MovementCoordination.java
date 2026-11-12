package agents.generation;

import contracts.OrderForms;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.GameState_Movement;
import phase.Order;

import java.util.*;

/**
 * Counts own supports and convoys whose referenced order does not match.
 * Foreign orders, convoy paths, support cutting and move outcomes are outside
 * this check. A matching submission can still fail when adjudicated.
 */
public final class MovementCoordination {

    private MovementCoordination() { }

    public record Counts(int ownSupports, int mismatchedSupports, int ownConvoys, int mismatchedConvoys) {
        public Counts {
            if (ownSupports < 0 || mismatchedSupports < 0 || mismatchedSupports > ownSupports
                    || ownConvoys < 0 || mismatchedConvoys < 0 || mismatchedConvoys > ownConvoys)
                throw new IllegalArgumentException("Invalid coordination counts");
        }
        public boolean mismatched() { return mismatchedSupports != 0 || mismatchedConvoys != 0; }
    }

    /**
     * Requires one movement order per own unit. Catalog legality is checked
     * separately by CatalogActionValidator.
     */
    public static Counts inspect(GameState_Movement state, ActionCandidate candidate) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(candidate, "candidate");
        Map<Province, Order> orders = new EnumMap<>(Province.class);
        for (var form : candidate.orders()) {
            if (!(form instanceof Order order))
                throw new IllegalArgumentException("Coordination requires movement orders");
            Province location = state.board().locationOf(order.unit());
            if (location == null || orders.putIfAbsent(Province.canonical(location), order) != null)
                throw new IllegalArgumentException("Missing unit or duplicate movement order");
        }
        long units = state.board().locations().keySet().stream()
                .filter(unit -> unit.owner() == candidate.power()).count();
        if (orders.size() != units)
            throw new IllegalArgumentException("Coordination requires a complete action");
        int supports = 0, badSupports = 0, convoys = 0, badConvoys = 0;
        for (Order order : orders.values()) {
            if (order.orderType() != OrderType.SUPPORT && order.orderType() != OrderType.CONVOY)
                continue;
            if (order.target() == null)
                throw new IllegalArgumentException("Support or convoy has no referenced province");
            Order referenced = orders.get(Province.canonical(order.target()));
            if (referenced == null) continue;
            if (order.orderType() == OrderType.SUPPORT) {
                supports++;
                if (!OrderForms.supportMatches(order, referenced, state.board().locationOf(referenced.unit())))
                    badSupports++;
            } else {
                convoys++;
                if (referenced.unitType() != UnitType.ARMY || referenced.orderType() != OrderType.MOVE
                        || !Province.equalsIgnoreCoast(referenced.target(), order.auxiliaryTarget()))
                    badConvoys++;
            }
        }
        return new Counts(supports, badSupports, convoys, badConvoys);
    }
}