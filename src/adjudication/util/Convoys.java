package adjudication.util;

import adjudication.Order;
import contracts.OrderForms;
import domain.OrderType;
import domain.UnitType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public abstract class Convoys {


    private Convoys() { }


    /**
     * Stub that redirects to `contracts.OrderForms::convoyPathIsValid(...)`
     */
    @Deprecated (forRemoval = false)
    public static boolean convoyPathIsValid(
            Order moveOrder,
            List<Order> convoyPath
    ) {
        if (moveOrder == null)
            return false;

        return OrderForms.convoyPathIsValid(
                moveOrder,
                moveOrder.pos0,
                convoyPath,
                Order::origin
        );
    }

    /**
     * Stub that redirects to `contracts.OrderForms::drawConvoyPath(...)`
     */
    @Deprecated (forRemoval = false)
    public static List<Order> drawConvoyPath(
            Order moveOrder,
            Collection<Order> orders
    ) {
        if (moveOrder == null)
            return new ArrayList<>();

        return OrderForms.drawConvoyPath(
                moveOrder,
                moveOrder.pos0,
                orders,
                Order::origin
        );
    }


    /**
     * Stub that redirects to `contracts.OrderForms::findAdjacentConvoys(...)`
     */
    @Deprecated (forRemoval = false)
    public static List<Order> findAdjacentConvoys(
            Order convoyOrder,
            Collection<Order> excludedOrders,
            Collection<Order> orders
    ) {
        return OrderForms.findAdjacentConvoys(
                convoyOrder,
                excludedOrders,
                orders,
                Order::origin);
    }

    @Deprecated (forRemoval = true)  // unused since stub-replacement [ @ 11-04-26 ]
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

    @Deprecated (forRemoval = true)  // unused since stub-replacement [ @ 11-04-26 ]
    private static boolean matches(Order convoy, Order moveOrder) {
        return convoy.orderType == OrderType.CONVOY
                && Orders.orderIsValid(convoy)
                && convoy.pos1 == moveOrder.pos0
                && convoy.pos2 == moveOrder.pos1;
    }

}
