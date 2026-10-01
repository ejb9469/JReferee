package adjudication.util;

import adjudication.Order;
import contracts.OrderForm;
import domain.*;

import java.util.*;


public abstract class Orders {

    private Orders() {  }


    public static boolean orderIsValid(Order order) {

        // Existing adjudication policy; not a general OrderForm validator.
        switch (order.orderType) {

            case MOVE -> {
                if (order.pos2 != null)
                    return false;
                if (Province.equalsIgnoreCoast(order.pos0, order.pos1))
                    return false;
                if (order.unitType == UnitType.ARMY && order.pos1.geography == Geography.WATER)
                    return false;
                else if (order.unitType == UnitType.ARMY && order.pos1.coastType == CoastType.SPLIT)
                    return false;
                else if (order.unitType == UnitType.FLEET && order.pos1.geography == Geography.INLAND)
                    return false;
                else if (order.unitType == UnitType.FLEET && !order.pos0.isAdjacentTo(order.pos1))
                    return false;
                else
                    return true;
            }

            case SUPPORT -> {

                Province supportedDestination =
                        order.pos2 == null ? order.pos1 : order.pos2;

                if (supportedDestination == null)
                    return false;

                if (order.unitType == UnitType.ARMY
                        && supportedDestination.geography == Geography.WATER)
                    return false;

                if (order.unitType == UnitType.FLEET
                        && supportedDestination.geography == Geography.INLAND
                        && !supportedDestination.hasCoast())
                    return false;

                if (Province.equalsIgnoreCoast(order.pos1, order.pos2))
                    return false;

                return order.pos0.isAdjacentToIgnoreSplitCoast(supportedDestination)
                        && (order.unitType != UnitType.FLEET
                        || Province.adjacentBySea(order.pos0, supportedDestination));

            }

            case CONVOY -> {
                if (order.pos1 == null || order.pos2 == null
                        || Province.equalsIgnoreCoast(order.pos1, order.pos2))
                    return false;
                if (order.unitType != UnitType.FLEET || order.pos0.geography != Geography.WATER)
                    return false;
                return true;
            }

            case HOLD -> {
                return order.pos1 == null && order.pos2 == null;
            }

            case RETREAT -> {
                if (order.pos0 != order.pos2)
                    return false;
                Order dummyMove = new Order(order);
                dummyMove.orderType = OrderType.MOVE;
                return orderIsValid(dummyMove);
            }

            default -> {
                return false;
            }

        }

    }


    // Collection helpers \\

    public static Collection<Order> cleanse(Collection<Order> orders) {

        Collection<Order> invalidOrders = new ArrayList<>();

        for (Order order : orders)
            if (!Orders.orderIsValid(order))
                invalidOrders.add(order);

        orders.removeAll(invalidOrders);
        return invalidOrders;

    }

    public static <T extends OrderForm> Collection<T> pruneForOrderType(
            OrderType orderType, Collection<T> orders) {

        Collection<T> result = new ArrayList<>();

        if (orders == null)
            return result;

        for (T order : orders)
            if (orderType == order.orderType())
                result.add(order);

        return result;

    }

    public static Order locateUnitAtPosition(Province pos, Collection<Order> orders) {

        for (Order order : orders) {
            if (order.pos0 == pos)
                return order;
            else if (Province.equalsIgnoreCoast(order.pos0, pos))
                return order;
        }

        return null;

    }

    public static Collection<Order> locateUnitsMovingToPosition(
            Province pos, Collection<Order> orders) {

        Collection<Order> ordersOut = new ArrayList<>();

        for (Order order : orders) {

            if (order.orderType != OrderType.MOVE && order.orderType != OrderType.RETREAT)
                continue;

            if (order.pos1 == pos)
                ordersOut.add(order);
            else if (Province.equalsIgnoreCoast(order.pos1, pos))
                ordersOut.add(order);

        }

        return ordersOut;

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

    public static List<Order> deepCopy(Collection<Order> orders) {
        List<Order> copies = new ArrayList<>(orders.size());
        for (Order order : orders)
            copies.add(new Order(order));
        return copies;
    }

    public static List<Order> deepCopy(List<Order> orders) {
        return deepCopy((Collection<Order>) orders);
    }

    public static Set<Order> deepCopy(Set<Order> orders) {
        Set<Order> copies = new HashSet<>(orders.size());
        for (Order order : orders)
            copies.add(new Order(order));
        return copies;
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