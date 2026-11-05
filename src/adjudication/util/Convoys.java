package adjudication.util;

import adjudication.Order;
import contracts.OrderForms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


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

}