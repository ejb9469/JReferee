package adjudication.util;

import adjudication.Order;
import contracts.OrderForm;
import contracts.OrderForms;
import domain.*;

import java.util.*;


public abstract class Orders {

    private Orders() {  }


    @Deprecated (forRemoval = false)
    public static boolean orderIsValid(Order order) {

        if (order == null)
            return false;

        if (order.orderType == OrderType.RETREAT)
            return OrderForms.isValidRetreatOrder(order, order.pos0);

        return OrderForms.isValidMovementOrder(order, order.pos0);

    }


    // Collection helpers \\

    @Deprecated (forRemoval = false)  // currently unused, & relies on old code (ought to be in `contracts.OrderForms`...)
    public static Collection<Order> cleanse(Collection<Order> orders) {

        Collection<Order> invalidOrders = new ArrayList<>();

        for (Order order : orders)
            if (!Orders.orderIsValid(order))
                invalidOrders.add(order);

        orders.removeAll(invalidOrders);
        return invalidOrders;

    }

    /**
     * Stub that redirects to `contracts.OrderForms::pruneForOrderType(...)`
     */
    @Deprecated (forRemoval = false)
    public static <T extends OrderForm> Collection<T> pruneForOrderType(
            OrderType orderType,
            Collection<T> orders) {
        return OrderForms.pruneForOrderType(orderType, orders);
    }


    // this func does not delegate to `OrderForms` - it is very simple
    public static Order locateUnitAtPosition(Province pos, Collection<Order> orders) {

        for (Order order : orders) {
            if (order.pos0 == pos)
                return order;
            else if (Province.equalsIgnoreCoast(order.pos0, pos))
                return order;
        }

        return null;

    }

    /**
     * Stub that redirects to `contracts.OrderForms::locateUnitsMovingToPosition(...)`
     */
    @Deprecated (forRemoval = false)
    public static Collection<Order> locateUnitsMovingToPosition(
            Province province,
            Collection<Order> orders) {
        return OrderForms.locateUnitsMovingToPosition(province, orders);
    }

    public static Order locateHeadToHead(Order moveOrder, Collection<Order> orders) {

        if (moveOrder.orderType != OrderType.MOVE)
            throw new IllegalArgumentException(String.format(
                    "`locateHeadToHead()` called on non-move Order: %s", moveOrder));

        for (Order order2 : orders) {

            if (order2.equals(moveOrder) || order2.orderType != OrderType.MOVE)
                continue;

            if (order2.pos1 == moveOrder.pos0 && order2.pos0 == moveOrder.pos1)
                return order2;
            else if (Province.equalsIgnoreCoast(order2.pos1, moveOrder.pos0)
                    && Province.equalsIgnoreCoast(order2.pos0, moveOrder.pos1))
                return order2;

        }

        return null;

    }

    public static Order locateCorresponding(Order supportOrConvoyOrder, Collection<Order> orders) {

        if (supportOrConvoyOrder.orderType != OrderType.SUPPORT
                && supportOrConvoyOrder.orderType != OrderType.CONVOY)
            throw new IllegalArgumentException(String.format(
                    "`locateCorresponding()` called on non-Support-or-Convoy Order: %s",
                    supportOrConvoyOrder));

        if (supportOrConvoyOrder.pos2 == null) {

            for (Order order : orders) {
                if (order.equals(supportOrConvoyOrder) || order.orderType == OrderType.MOVE)
                    continue;
                if (order.pos0 == supportOrConvoyOrder.pos1)
                    return order;
            }

        } else {

            for (Order order : orders) {
                if (order.equals(supportOrConvoyOrder) || order.orderType != OrderType.MOVE)
                    continue;
                if (order.pos0 == supportOrConvoyOrder.pos1 && order.pos1 == supportOrConvoyOrder.pos2)
                    return order;
            }

        }

        return null;

    }

    public static Collection<Order> locateCorresponding(
            Order moveOrHoldOrder, boolean locateSupportsFromMove, Collection<Order> orders) {

        if (!locateSupportsFromMove)
            throw new IllegalArgumentException(
                    "`locateCorresponding(moveOrHoldOrder, false, orders)` is "
                            + "invalid: a MOVE/HOLD cannot be passed to the "
                            + "Support/Convoy lookup overload. Received: "
                            + moveOrHoldOrder);

        if (moveOrHoldOrder.orderType != OrderType.MOVE
                && moveOrHoldOrder.orderType != OrderType.HOLD)
            throw new IllegalArgumentException(String.format(
                    "`locateCorresponding()` called on non-Move-or-Hold Order: %s",
                    moveOrHoldOrder));

        Collection<Order> corresponding = new ArrayList<>();

        for (Order order : orders) {

            if (order.equals(moveOrHoldOrder)
                    || (order.orderType != OrderType.CONVOY
                    && order.orderType != OrderType.SUPPORT))
                continue;

            if ((moveOrHoldOrder.pos1 == null
                    && order.pos1 == moveOrHoldOrder.pos0
                    && order.pos2 == null)
                    || (moveOrHoldOrder.pos1 != null
                    && order.pos1 == moveOrHoldOrder.pos0
                    && order.pos2 == moveOrHoldOrder.pos1))
                corresponding.add(order);

        }

        return corresponding;

    }

    public static boolean adjacentMatchingConvoyFleetExists(
            Order moveOrder, Collection<Order> orders) {

        for (Order order2 : orders) {

            if (order2.equals(moveOrder))
                continue;

            if (order2.pos0.isAdjacentTo(moveOrder.pos0)
                    && order2.orderType == OrderType.CONVOY
                    && order2.unitType == UnitType.FLEET
                    && order2.pos0.geography == Geography.WATER) {
                if (order2.pos1 == moveOrder.pos0 && order2.pos2 == moveOrder.pos1)
                    return true;
            }

        }

        return false;

    }


    // Copying preserves adjudication state. \\

    /**
     * Stub that redirects to `contracts.OrderForms::deepCopy(...)`
     */
    @Deprecated (forRemoval = false)
    public static List<Order> deepCopy(Collection<Order> orders) {
        return OrderForms.deepCopy(orders, Order::new);
    }

    /**
     * Stub that redirects to `contracts.OrderForms::deepCopy(...)`
     */
    @Deprecated (forRemoval = false)
    public static List<Order> deepCopy(List<Order> orders) {
        return OrderForms.deepCopy(orders, Order::new);
    }

    /**
     * Stub that redirects to `contracts.OrderForms::deepCopy(...)`
     */
    @Deprecated (forRemoval = false)
    public static Set<Order> deepCopy(Set<Order> orders) {
        return new HashSet<>(OrderForms.deepCopy(orders, Order::new));
    }

    public static Set<Order> uniq(Collection<Collection<Order>> ordersBag) {

        Set<Order> finalBag = new HashSet<>();

        for (Collection<Order> orders : ordersBag) {
            for (Order order : orders) {
                for (Collection<Order> orders2 : ordersBag) {
                    if (orders2 == orders)
                        continue;
                    if (!orders2.contains(order)) {
                        finalBag.add(order);
                        break;
                    }
                }
            }
        }

        return finalBag;

    }

    public static List<Order> conformOrder(Collection<Order> orders, List<Order> orderedOrders) {

        if (orders == null || orderedOrders == null)
            return null;

        if (orders.size() != orderedOrders.size())
            throw new IllegalStateException(String.format(
                    "[`static List<Order> %s::conformOrder(Collection<Order>, List<Order>)`]\n"
                            + "\t==>collection size mismatch! (orders.length=%d, orderedOrders.length=%d)\n",
                    "Orders", orders.size(), orderedOrders.size()));

        if (orders.isEmpty())
            return new ArrayList<>();

        Order[] ordersArr = new Order[orders.size()];

        for (Order order : orders) {
            for (int i = 0; i < orderedOrders.size(); i++) {
                if (order.pos0 == orderedOrders.get(i).pos0) {
                    ordersArr[i] = order;
                    break;
                }
            }
        }

        return new ArrayList<>(Arrays.asList(ordersArr));

    }


    // Stable identity helpers: retain original submitted-order semantics. \\

    public static Order originalOf(Order order) {
        Order snapshot = order.getSnapshot();
        return snapshot == null ? order : snapshot;
    }

    public static String keyOf(Order order) {
        Order originalOrder = Orders.originalOf(order);
        return String.valueOf(originalOrder.owner)
                + "\u001F" + String.valueOf(originalOrder.unitType)
                + "\u001F" + String.valueOf(originalOrder.orderType)
                + "\u001F" + String.valueOf(originalOrder.pos0)
                + "\u001F" + String.valueOf(originalOrder.pos1)
                + "\u001F" + String.valueOf(originalOrder.pos2);
    }

    public static boolean sameKey(Order first, Order second) {
        return Orders.keyOf(first).equals(Orders.keyOf(second));
    }

}