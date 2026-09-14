package adjudication.util;

import domain.Order;

import java.util.Comparator;

/**
 * Comparator which contains the Order sorting logic in the form of `compare()`<br><br>
 * `Order.java` implements `Comparable`, and its `compareTo()` func references this class<br><br>
 * Comparators are always useful for `Collections.sort(coll, new Comparator())`
 */
public class OrderComparator implements Comparator<Order> {

    @Override
    public int compare(Order order1, Order order2) {

        // first, sort by owner (`Nation` enum)
        int ownerCompare = order1.owner.compareTo(order2.owner);
        if (ownerCompare != 0)
            return ownerCompare;

        // if same nation, sort by `OrderType` enum (MOVE, HOLD, SUPPORT, CONVOY)
        int orderTypeCompare = order1.orderType.compareTo(order2.orderType);
        if (orderTypeCompare != 0)
            return orderTypeCompare;

        // if same order type, armies first + fleets second
        int unitTypeCompare = order1.unitType.compareTo(order2.unitType);
        if (unitTypeCompare != 0)
            return unitTypeCompare;

        // if same unit type, alphabetical order of abbreviation (sort by name of `pos0`)
        int pos0Compare = order1.pos0.name().compareTo(order2.pos0.name());
        if (pos0Compare != 0)
            return pos0Compare;

        // fallback: `Order::hashcode()`
        return Integer.compare(order1.hashCode(), order2.hashCode());

    }

}
