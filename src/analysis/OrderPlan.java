package analysis;

import domain.Nation;
import domain.OrderType;
import phase.Order;
import phase.UnitId;

import java.util.*;


public record OrderPlan(
        Nation nation,
        List<Order> orders,
        double logPreference
) {

    public OrderPlan {

        Objects.requireNonNull(nation, "nation");
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));

        if (!Double.isFinite(logPreference) || logPreference > 0)
            throw new IllegalArgumentException("Log preference must be finite and non-positive");

        Set<UnitId> issuers = new HashSet<>();

        for (Order order : orders) {

            if (order.owner() != nation)
                throw new IllegalArgumentException("Plan contains another nation's order");

            if (!issuers.add(order.unit()))
                throw new IllegalArgumentException("Plan contains duplicate unit orders");

            OrderType type = order.orderType();

            if (type != OrderType.MOVE
                    && type != OrderType.HOLD
                    && type != OrderType.SUPPORT
                    && type != OrderType.CONVOY)
                throw new IllegalArgumentException("Plan contains a non-movement order");

        }

    }

}