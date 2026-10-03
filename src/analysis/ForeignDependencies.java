package analysis;

import domain.*;
import phase.Order;
import phase.UnitId;
import java.util.*;

/** Alternatives are OR; every requirement within an alternative is AND. */
public record ForeignDependencies(List<List<Requirement>> alternatives, boolean truncated) {
    public ForeignDependencies {
        alternatives = alternatives.stream().map(List::copyOf).distinct().toList();
    }

    public enum Constraint { EXACT_ORDER, STATIONARY }

    public record Requirement(UnitId unit, Province origin, Constraint constraint,
                              OrderType orderType, Province target, Province auxiliaryTarget,
                              List<Order> affectedFriendlyOrders, String phase) {
        public Requirement {
            Objects.requireNonNull(unit);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(constraint);
            affectedFriendlyOrders = List.copyOf(affectedFriendlyOrders);
            if (!"MOVEMENT".equals(phase))
                throw new IllegalArgumentException("Movement dependency required");
            if (constraint == Constraint.EXACT_ORDER)
                Objects.requireNonNull(orderType);
        }

        public boolean satisfiedBy(List<Order> orders) {
            return orders.stream().anyMatch(order -> order.unit().equals(unit)
                    && (constraint == Constraint.STATIONARY
                    ? order.orderType() == OrderType.HOLD || order.orderType() == OrderType.SUPPORT
                    || order.orderType() == OrderType.CONVOY
                    : order.orderType() == orderType && order.target() == target
                    && order.auxiliaryTarget() == auxiliaryTarget));
        }
    }

    public record Coverage(List<String> satisfyingScenarios, int scenarioCount, boolean truncated) {
        public Coverage { satisfyingScenarios = List.copyOf(satisfyingScenarios); }
        public int satisfiedCount() { return satisfyingScenarios.size(); }
    }

    public Coverage coverage(Map<String, List<Order>> scenarios) {
        List<String> satisfied = new ArrayList<>();
        new TreeMap<>(scenarios).forEach((name, orders) -> {
            if (alternatives.stream().anyMatch(conjunction ->
                    conjunction.stream().allMatch(requirement -> requirement.satisfiedBy(orders))))
                satisfied.add(name);
        });
        return new Coverage(satisfied, scenarios.size(), truncated);
    }
}
