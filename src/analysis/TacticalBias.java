package analysis;

import analysis.openings.Classifier;
import domain.OrderType;
import domain.Province;
import game.BoardState;
import phase.Order;
import phase.UnitId;

import java.util.*;

/** Selected-order warnings, independent of adjudication and never hard constraints. */
public final class TacticalBias {
    private TacticalBias() { }

    public static void validateWeight(double weight) {
        if (!Double.isFinite(weight) || weight < 0)
            throw new IllegalArgumentException("Tactical bias weight must be finite and non-negative");
    }

    public static List<TacticalPrinciple.Warning> inspect(BoardState board, OrderPlan plan) {
        List<TacticalPrinciple.Warning> findings = new ArrayList<>();
        scan(board, plan, (move, related, category, principle, explanation, alternative) ->
                findings.add(new TacticalPrinciple.Warning(
                        principle + ":" + Classifier.signature(board, List.of(move, related)),
                        category, TacticalPrinciple.Severity.WARNING, List.of(move, related),
                        explanation, alternative, TacticalPrinciple.EvaluationStatus.NOT_REQUESTED)));
        findings.sort(Comparator.comparing(TacticalPrinciple.Warning::id));
        return List.copyOf(findings);
    }

    /** Linear selected-order scan; beam comparisons do not construct warning metadata. */
    public static int offendingMoveCount(BoardState board, OrderPlan plan) {
        return scan(board, plan, null);
    }

    static boolean supportsForeignMoveTo(BoardState board, Order support, Province destination) {
        if (support.orderType() != OrderType.SUPPORT || support.auxiliaryTarget() == null
                || support.target() == null
                || Province.canonical(support.auxiliaryTarget()) != Province.canonical(destination))
            return false;
        UnitId supported = unitAtExactLocation(board, support.target());
        return supported != null && supported.owner() != support.owner();
    }

    private static int scan(BoardState board, OrderPlan plan, FindingConsumer finding) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(plan, "plan");
        Map<Province, UnitId> unitsAt = new EnumMap<>(Province.class);
        for (var entry : board.locations().entrySet())
            unitsAt.put(entry.getValue(), entry.getKey());
        Map<Province, Order> stationaryOrders = new EnumMap<>(Province.class);
        for (Order order : plan.orders()) {
            if (order.owner() == plan.nation() && board.locations().containsKey(order.unit())
                    && (order.orderType() == OrderType.HOLD || order.orderType() == OrderType.SUPPORT
                    || order.orderType() == OrderType.CONVOY))
                stationaryOrders.put(Province.canonical(board.locationOf(order.unit())), order);
        }
        Map<Province, List<Order>> foreignSupportDestinations = new EnumMap<>(Province.class);
        for (Order support : plan.orders()) {
            if (support.owner() != plan.nation() || support.orderType() != OrderType.SUPPORT
                    || support.auxiliaryTarget() == null || support.target() == null)
                continue;
            UnitId supported = unitsAt.get(support.target());
            if (supported == null || supported.owner() == support.owner())
                continue;
            foreignSupportDestinations.computeIfAbsent(
                    Province.canonical(support.auxiliaryTarget()), ignored -> new ArrayList<>())
                    .add(support);
        }
        Set<Order> offendingMoves = new HashSet<>();
        for (Order move : plan.orders()) {
            if (move.orderType() != OrderType.MOVE || move.target() == null
                    || move.owner() != plan.nation() || !board.locations().containsKey(move.unit()))
                continue;
            Order stationary = stationaryOrders.get(Province.canonical(move.target()));
            if (stationary != null && !stationary.unit().equals(move.unit())
                    && stationary.owner() == move.owner()) {
                offendingMoves.add(move);
                if (finding != null) {
                    var category = switch (stationary.orderType()) {
                        case HOLD -> TacticalPrinciple.Category.FRIENDLY_HOLD_UNIT;
                        case SUPPORT -> TacticalPrinciple.Category.FRIENDLY_SUPPORT_UNIT;
                        case CONVOY -> TacticalPrinciple.Category.FRIENDLY_CONVOY_FLEET;
                        default -> throw new IllegalStateException("Unexpected stationary order");
                    };
                    String principle = switch (category) {
                        case FRIENDLY_HOLD_UNIT -> "friendly-hold-unit";
                        case FRIENDLY_SUPPORT_UNIT -> "friendly-support-unit";
                        case FRIENDLY_CONVOY_FLEET -> "friendly-convoy-fleet";
                        case FOREIGN_SUPPORTED_DESTINATION -> throw new IllegalStateException();
                    };
                    String explanation = switch (category) {
                        case FRIENDLY_HOLD_UNIT -> "Moves into your holding unit.";
                        case FRIENDLY_SUPPORT_UNIT -> "Moves into your supporting unit.";
                        case FRIENDLY_CONVOY_FLEET -> "Moves into your convoying fleet.";
                        case FOREIGN_SUPPORTED_DESTINATION -> throw new IllegalStateException();
                    };
                    finding.accept(move, stationary, category, principle, explanation,
                            Order.supportHold(move.unit(), board.locationOf(stationary.unit())));
                }
            }
            for (Order support : foreignSupportDestinations.getOrDefault(
                    Province.canonical(move.target()), List.of())) {
                offendingMoves.add(move);
                if (finding != null)
                    finding.accept(move, support, TacticalPrinciple.Category.FOREIGN_SUPPORTED_DESTINATION,
                            "foreign-supported-destination", "Competes with a foreign move you support.", null);
            }
        }
        return offendingMoves.size();
    }

    private static UnitId unitAtExactLocation(BoardState board, Province location) {
        for (var entry : board.locations().entrySet())
            if (entry.getValue() == location)
                return entry.getKey();
        return null;
    }

    @FunctionalInterface
    private interface FindingConsumer {
        void accept(Order move, Order related, TacticalPrinciple.Category category,
                    String principle, String explanation, Order alternative);
    }
}
