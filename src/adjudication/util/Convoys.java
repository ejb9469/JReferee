package adjudication.util;

import adjudication.Order;
import domain.OrderType;
import domain.UnitType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public abstract class Convoys {


    private Convoys() { }


    public static boolean convoyPathIsValid(
            Order moveOrder,
            List<Order> convoyPath
    ) {

        if (moveOrder.orderType != OrderType.MOVE
                || moveOrder.unitType != UnitType.ARMY
                || !Orders.orderIsValid(moveOrder)
                || convoyPath.isEmpty())
            return false;

        if (!convoyPath.getFirst().pos0.isAdjacentTo(moveOrder.pos0)
                || !convoyPath.getLast().pos0.isAdjacentTo(moveOrder.pos1))
            return false;

        Set<Order> visited = new HashSet<>();
        Order previous = null;

        for (Order convoy : convoyPath) {

            if (!matches(convoy, moveOrder)
                    || !visited.add(convoy))
                return false;

            if (previous != null
                    && !previous.pos0.isAdjacentTo(convoy.pos0))
                return false;

            previous = convoy;
        }

        return true;

    }

    public static List<Order> drawConvoyPath(
            Order moveOrder,
            Collection<Order> orders
    ) {

        if (moveOrder.orderType != OrderType.MOVE
                || moveOrder.unitType != UnitType.ARMY
                || !Orders.orderIsValid(moveOrder))
            return new ArrayList<>();

        List<Order> convoys = new ArrayList<>();

        for (Order order : orders)
            if (matches(order, moveOrder))
                convoys.add(order);

        for (Order first : convoys) {

            if (!first.pos0.isAdjacentTo(moveOrder.pos0))
                continue;

            List<Order> path = new ArrayList<>();

            if (findPath(first, moveOrder, convoys, new HashSet<>(), path))
                return path;
        }

        return new ArrayList<>();

    }

    public static List<Order> findAdjacentConvoys(
            Order convoyOrder,
            Collection<Order> excludedOrders,
            Collection<Order> orders
    ) {

        List<Order> adjacentConvoys = new ArrayList<>();

        for (Order order : orders) {

            if (excludedOrders.contains(order)
                    || order.equals(convoyOrder)
                    || order.orderType != OrderType.CONVOY
                    || !Orders.orderIsValid(order))
                continue;

            if (order.pos1 == convoyOrder.pos1
                    && order.pos2 == convoyOrder.pos2
                    && order.pos0.isAdjacentTo(convoyOrder.pos0))
                adjacentConvoys.add(order);
        }

        return adjacentConvoys;

    }

    private static boolean findPath(
            Order current,
            Order moveOrder,
            List<Order> convoys,
            Set<Order> visited,
            List<Order> path
    ) {

        if (!visited.add(current))
            return false;

        path.add(current);

        if (current.pos0.isAdjacentTo(moveOrder.pos1))
            return true;

        for (Order next : convoys) {

            if (visited.contains(next)
                    || !current.pos0.isAdjacentTo(next.pos0))
                continue;

            if (findPath(next, moveOrder, convoys, visited, path))
                return true;
        }

        path.removeLast();
        return false;

    }

    private static boolean matches(Order convoy, Order moveOrder) {
        return convoy.orderType == OrderType.CONVOY
                && Orders.orderIsValid(convoy)
                && convoy.pos1 == moveOrder.pos0
                && convoy.pos2 == moveOrder.pos1;
    }

}
