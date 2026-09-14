package adjudication.util;

import adjudication.Order;
import domain.OrderType;
import domain.Province;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Partitions a submitted order collection into (!)conservative(!) adjudication
 * dependency components.<br><br>
 *
 * Orders in distinct components cannot affect one another through unit
 * occupancy, attacks, supports, convoys, competing destinations, or
 * head-to-head movement. They may therefore be adjudicated by independent
 * Judge instances without sharing recursive cycle bookkeeping.
 *
 * <p>The graph is intentionally conservative. A possible dependency creates
 * an edge even when later adjudication determines that it has no practical
 * effect. Keeping extra orders in one component is safe; incorrectly
 * separating dependent orders is not.</p>
 */
public abstract class Dependencies {


    /**
     * Returns conservative dependency components in the input collection's
     * iteration order.<br><br>
     *
     * Order identity, rather than `Order.equals(...)`, is used for graph
     * membership b/c adjudication metadata is mutable.
     */
    public static List<List<Order>> partition(
            Collection<Order> orders
    ) {

        List<Order> orderList = new ArrayList<>(orders);

        if (orderList.isEmpty())
            return Collections.emptyList();

        Map<Order, Set<Order>> graph = new IdentityHashMap<>();

        for (Order order : orderList) {
            graph.put(
                    order,
                    Collections.newSetFromMap(new IdentityHashMap<>())
            );
        }

        for (int i = 0; i < orderList.size(); i++) {
            for (int j = i + 1; j < orderList.size(); j++) {

                Order first = orderList.get(i);
                Order second = orderList.get(j);

                if (mayDepend(first, second))
                    connect(graph, first, second);
            }
        }

        Set<Order> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );

        List<List<Order>> components = new ArrayList<>();

        for (Order root : orderList) {

            if (!visited.add(root))
                continue;

            Set<Order> componentMembers =
                    Collections.newSetFromMap(new IdentityHashMap<>());

            Deque<Order> pending = new ArrayDeque<>();
            pending.add(root);

            while (!pending.isEmpty()) {

                Order current = pending.removeFirst();

                if (!componentMembers.add(current))
                    continue;

                for (Order neighbor : graph.get(current)) {
                    if (visited.add(neighbor))
                        pending.addLast(neighbor);
                }
            }

            /*
             * Preserve the original order collection's iteration order inside
             * each component. `Judge` behavior is intentionally order-sensitive
             * before `Justice` performs its shuffled trials.
             */
            List<Order> component = new ArrayList<>();

            for (Order order : orderList) {
                if (componentMembers.contains(order))
                    component.add(order);
            }

            components.add(component);
        }

        return components;

    }


    // Dependency graph helpers \\

    /**
     * Returns true when two orders may affect one another's adjudication.<br><br>
     *
     * This includes direct board conflicts plus the correspondence relations
     * used by `Orders.locateCorresponding(...)`, `Orders.locateHeadToHead(...)`,
     * and `Convoys.drawConvoyPath(...)`.
     */
    private static boolean mayDepend(Order first, Order second) {

        /*
         * Two units cannot occupy the same province in a legal position, but
         * retain this edge defensively for malformed or transitional input.
         */
        if (sameProvince(first.pos0, second.pos0))
            return true;

        /*
         * An order's referenced provinces can establish occupancy, support,
         * convoy, attack, and convoy-path dependencies with another unit.
         *
         * For example:
         * - MOVE destination -> defender at that destination;
         * - SUPPORT subject/destination -> supported or contested unit;
         * - CONVOY army source/destination -> convoyed army or relevant unit.
         */
        if (referencesProvince(first, second.pos0)
                || referencesProvince(second, first.pos0)) {
            return true;
        }

        /*
         * Competing moves to one destination depend on one another through
         * champion(...), prevent strength, and destination hold strength.
         */
        if (isMove(first)
                && isMove(second)
                && sameProvince(first.pos1, second.pos1)) {
            return true;
        }

        /*
         * A head-to-head pair is already included by the referenced-origin
         * tests above, but retain the relationship explicitly to document the
         * Orders.locateHeadToHead(...) dependency.
         */
        if (isMove(first)
                && isMove(second)
                && sameProvince(first.pos1, second.pos0)
                && sameProvince(second.pos1, first.pos0)) {
            return true;
        }

        /*
         * All fleets ordered to convoy the same army movement are connected.
         * Convoys.drawConvoyPath(...) can use them as alternative or chained
         * convoy-route members, so separating them would be unsafe.
         */
        if (first.orderType == OrderType.CONVOY
                && second.orderType == OrderType.CONVOY
                && sameProvince(first.pos1, second.pos1)
                && sameProvince(first.pos2, second.pos2)) {
            return true;
        }

        return false;

    }

    /**
     * Returns whether an order references a given Province as an order target.<br><br>
     *
     * `pos0` is deliberately excluded: it is the issuing unit's own current
     * location and is compared separately by mayDepend(...).
     */
    private static boolean referencesProvince(
            Order order,
            Province province
    ) {
        return sameProvince(order.pos1, province)
                || sameProvince(order.pos2, province);
    }

    private static boolean isMove(Order order) {
        return order.orderType == OrderType.MOVE
                || order.orderType == OrderType.RETREAT;
    }

    private static boolean sameProvince(
            Province first,
            Province second
    ) {

        if (first == null || second == null)
            return false;

        return first == second
                || Province.equalsIgnoreCoast(first, second);

    }

    private static void connect(
            Map<Order, Set<Order>> graph,
            Order first,
            Order second
    ) {
        graph.get(first).add(second);
        graph.get(second).add(first);
    }


}