package analysis;

import domain.OrderType;
import phase.Order;
import phase.UnitId;

import java.util.*;


public record OpponentScenario(
        List<Order> orders,
        double logPreference
) {

    public OpponentScenario {

        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));

        if (!Double.isFinite(logPreference) || logPreference > 0)
            throw new IllegalArgumentException("Log preference must be finite and non-positive");

        Set<UnitId> issuers = new HashSet<>();

        for (Order order : orders) {

            if (!issuers.add(order.unit()))
                throw new IllegalArgumentException("Scenario contains duplicate unit orders");

            OrderType type = order.orderType();

            if (type != OrderType.MOVE
                    && type != OrderType.HOLD
                    && type != OrderType.SUPPORT
                    && type != OrderType.CONVOY)
                throw new IllegalArgumentException("Scenario contains a non-movement order");

        }

    }

}