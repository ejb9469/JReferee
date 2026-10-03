package analysis;

import analysis.openings.Classifier;
import domain.OrderType;
import domain.Province;
import game.BoardState;
import phase.Order;

import java.util.*;
import java.util.function.BiConsumer;

/** Selected-order warnings, independent of adjudication and never hard constraints. */
public final class TacticalBias {
    private TacticalBias() { }

    public static void validateWeight(double weight) {
        if (!Double.isFinite(weight) || weight < 0)
            throw new IllegalArgumentException("Tactical bias weight must be finite and non-negative");
    }

    public static List<TacticalPrinciple.Warning> inspect(BoardState board, OrderPlan plan) {
        List<TacticalPrinciple.Warning> findings = new ArrayList<>();
        scan(board, plan, (move, stationary) -> {
            boolean convoy = stationary.orderType() == OrderType.CONVOY;
            var category = convoy ? TacticalPrinciple.Category.FRIENDLY_CONVOY_FLEET
                    : TacticalPrinciple.Category.FRIENDLY_SUPPORT_UNIT;
            String principle = convoy ? "friendly-convoy-fleet" : "friendly-support-unit";
            findings.add(new TacticalPrinciple.Warning(
                    principle + ":" + Classifier.signature(board, List.of(move, stationary)),
                    category, TacticalPrinciple.Severity.WARNING, List.of(move, stationary),
                    convoy
                            ? "The move targets a friendly convoying fleet's occupied province; "
                            + "it does not reinforce convoy defense and may waste an order. "
                            + "An unsuccessful friendly move does not itself disrupt the convoy. "
                            + "Supporting the stationary fleet may be worth comparing."
                            : "The move targets a friendly supporting unit's occupied province; "
                            + "it does not reinforce that unit and may waste an order. "
                            + "Supporting the stationary unit may be worth comparing.",
                    Order.supportHold(move.unit(), board.locationOf(stationary.unit())),
                    TacticalPrinciple.EvaluationStatus.NOT_REQUESTED));
        });
        findings.sort(Comparator.comparing(TacticalPrinciple.Warning::id));
        return List.copyOf(findings);
    }

    /** Linear selected-order scan; beam comparisons do not construct warning metadata. */
    public static int offendingMoveCount(BoardState board, OrderPlan plan) {
        return scan(board, plan, null);
    }

    private static int scan(BoardState board, OrderPlan plan, BiConsumer<Order, Order> finding) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(plan, "plan");
        Map<Province, Order> stationaryOrders = new EnumMap<>(Province.class);
        for (Order order : plan.orders()) {
            if (board.locations().containsKey(order.unit())
                    && (order.orderType() == OrderType.SUPPORT || order.orderType() == OrderType.CONVOY))
                stationaryOrders.put(Province.canonical(board.locationOf(order.unit())), order);
        }
        int count = 0;
        for (Order move : plan.orders()) {
            if (move.orderType() != OrderType.MOVE || move.target() == null
                    || !board.locations().containsKey(move.unit()))
                continue;
            Order stationary = stationaryOrders.get(Province.canonical(move.target()));
            if (stationary == null || stationary.unit().equals(move.unit())
                    || stationary.owner() != move.owner())
                continue;
            count++;
            if (finding != null)
                finding.accept(move, stationary);
        }
        return count;
    }
}
