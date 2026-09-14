package adjudication.util;

import domain.Order;
import domain.OrderType;
import domain.UnitType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Abstract class of static utility functions re: Convoy and Convoy pathing functionality
 */
public abstract class Convoys /*extends Orders*/ {

    public static boolean convoyPathIsValid(Order moveOrder, List<Order> convoyPath) {

        if (convoyPath.isEmpty())
            return false;

        Order firstConvoy = convoyPath.getFirst();

        if (!firstConvoy.pos0.isAdjacentTo(moveOrder.pos0))
            return false;

        if (convoyPath.size() == 1) {

            // We have already checked the Convoy's adjacency to the Moving Order,
            // so all that remains is checking its adjacency to the Destination
            return (!firstConvoy.pos0.isAdjacentTo(moveOrder.pos1));

        } else {  // convoyPath.size() >= 2

            Order lastConvoy = convoyPath.getLast();

            if (!lastConvoy.pos0.isAdjacentTo(moveOrder.pos1))
                return false;

            for (int i = 1; i < convoyPath.size(); i++) {

                Order convoyOrder = convoyPath.get(i);
                if (!convoyOrder.pos0.isAdjacentTo(convoyPath.get(i-1).pos0))
                    return false;
                if (!Orders.orderIsValid(convoyOrder))
                    return false;

            }

            return true;

        }

    }

    /**
     * Draws one possible convoy path for a given Move Order
     * @param moveOrder Move Order
     * @param orders Collection of Orders to search
     * @return A list containing one possible convoy path for `moveOrder`
     */
    public static List<Order> drawConvoyPath(Order moveOrder, Collection<Order> orders) {

        List<Order> beginningConvoys = new ArrayList<>();
        for (Order order : orders) {
            if (order.equals(moveOrder) ||
                order.unitType == UnitType.ARMY ||
                order.orderType != OrderType.CONVOY) {
                continue;
            }
            if (order.pos1.equals(moveOrder.pos0) && order.pos2.equals(moveOrder.pos1) && order.pos0.isAdjacentTo(moveOrder.pos0))
                beginningConvoys.add(order);
        }

        if (beginningConvoys.isEmpty())
            return beginningConvoys;  // empty list

        Order firstConvoy = beginningConvoys.getFirst();
        List<Order> initPath = new ArrayList<>();
        initPath.add(firstConvoy);
        return convoyPath(firstConvoy, initPath, new ArrayList<>(), orders);

    }

    /**
     * Locate Convoy Orders identical to & adjacent to a given Convoy Order
     * @param convoyOrder Matching Convoy Order
     * @param excludedOrders Collection of Orders to exclude from the search
     * @param orders Collection of Orders to search
     * @return List of adjacent fleets convoying the same army as `convoyOrder`
     */
    public static List<Order> findAdjacentConvoys(Order convoyOrder, Collection<Order> excludedOrders, Collection<Order> orders) {

        List<Order> adjacentConvoys = new ArrayList<>();
        for (Order order : orders) {
            if (excludedOrders.contains(order) ||
                    order.equals(convoyOrder) ||
                    order.orderType != OrderType.CONVOY)
                continue;
            if (order.pos1.equals(convoyOrder.pos1) && order.pos2.equals(convoyOrder.pos2) && order.pos0.isAdjacentTo(convoyOrder.pos0))
                adjacentConvoys.add(order);
        }

        return adjacentConvoys;

    }


    /**
     * Internal convoy pathing function, will 'chain' multiple convoys if necessary
     * @param firstConvoy The principal convoy
     * @param convoyPath The convoy path taken so far
     * @param excludedOrders Collection of Orders to exclude from the search
     * @param orders Collection of Orders to search
     * @return A convoy path -- a List of at least 1 mutually-adjacent convoy(s)
     */
    private static List<Order> convoyPath(Order firstConvoy, List<Order> convoyPath, Collection<Order> excludedOrders, Collection<Order> orders) {

        List<Order> adjacentConvoys = findAdjacentConvoys(firstConvoy, convoyPath, orders);

        adjacentConvoys.removeIf(excludedOrders::contains);

        if (adjacentConvoys.isEmpty())
            return convoyPath;

        convoyPath.add(adjacentConvoys.getFirst());
        excludedOrders.add(adjacentConvoys.getFirst());

        return convoyPath(adjacentConvoys.getFirst(), convoyPath, excludedOrders, orders);

    }

}
