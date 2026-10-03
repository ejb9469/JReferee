package analysis;

import adjudication.util.Orders;
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
            for (Occurrence occurrence : occurrences(board, plan)) {
                Order move = occurrence.move();
                Order related = occurrence.related();
                var category = occurrence.category();
                String principle = principleId(category);
            findings.add(new TacticalPrinciple.Warning(
                            findingId(board, principle, occurrence),
                        category, TacticalPrinciple.Severity.WARNING, List.of(move, related),
                        explanation(category), suggestedAlternative(board, occurrence),
                        TacticalPrinciple.EvaluationStatus.NOT_REQUESTED));
            }
            findings.sort(Comparator.comparing(TacticalPrinciple.Warning::id));
            return List.copyOf(findings);
        }

        /** Selected-order scan; beam comparisons do not construct warning metadata. */
        public static int offendingMoveCount(BoardState board, OrderPlan plan) {
            return occurrences(board, plan).size();
        }

        static boolean stationaryCollision(BoardState board, Order move, Order stationary) {
            return active(board, move) && active(board, stationary)
                    && move.orderType() == OrderType.MOVE && move.target() != null
                    && stationaryOrder(stationary) && !stationary.unit().equals(move.unit())
                    && stationary.owner() == move.owner()
                    && Province.canonical(move.target())
                    == Province.canonical(board.locationOf(stationary.unit()));
        }

        static boolean foreignAttackPair(BoardState board, Order first, Order second) {
            if (!active(board, first) || !active(board, second)
                    || first.orderType() != OrderType.MOVE || second.orderType() != OrderType.MOVE
                    || first.target() == null || first.target() != second.target()
                    || first.unit().equals(second.unit()) || first.owner() != second.owner())
                return false;
            UnitId occupant = unitAt(board, first.target());
            return occupant != null && occupant.owner() != first.owner();
        }

        private static List<Occurrence> occurrences(BoardState board, OrderPlan plan) {
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(plan, "plan");

            List<Order> selected = plan.orders().stream().filter(order -> active(board, order))
                    .sorted(orderOrder(board)).toList();
            List<Order> stationary = selected.stream().filter(TacticalBias::stationaryOrder).toList();
            List<Order> moves = selected.stream().filter(order ->
                    order.orderType() == OrderType.MOVE && order.target() != null).toList();
            List<Occurrence> result = new ArrayList<>();
            for (Order move : moves) {
                Order target = stationary.stream().filter(order -> stationaryCollision(board, move, order))
                        .findFirst().orElse(null);
                if (target != null) {
                    result.add(new Occurrence(move, target, stationaryCategory(target)));
                    continue;
                }
                Order peer = moves.stream().filter(order -> foreignAttackPair(board, move, order))
                        .filter(order -> supportMoveAlternative(board, move, order) != null)
                        .findFirst().orElseGet(() -> moves.stream()
                                .filter(order -> foreignAttackPair(board, move, order))
                                .findFirst().orElse(null));
                if (peer != null)
                    result.add(new Occurrence(move, peer,
                            TacticalPrinciple.Category.FRIENDLY_PROVINCE_ATTACK));
            }
            return List.copyOf(result);
        }

        private static Order suggestedAlternative(BoardState board, Occurrence occurrence) {
            return switch (occurrence.category()) {
                case FRIENDLY_SUPPORT_UNIT, FRIENDLY_CONVOY_FLEET ->
                        Order.supportHold(occurrence.move().unit(),
                                board.locationOf(occurrence.related().unit()));
                case FRIENDLY_HOLD_UNIT ->
                        supportHoldAlternative(board, occurrence.move(), occurrence.related());
                case FRIENDLY_PROVINCE_ATTACK ->
                        supportMoveAlternative(board, occurrence.move(), occurrence.related());
            };
        }

        private static Order supportHoldAlternative(BoardState board, Order move, Order stationary) {
            return valid(board, Order.supportHold(move.unit(), board.locationOf(stationary.unit())))
                    ? Order.supportHold(move.unit(), board.locationOf(stationary.unit())) : null;
        }

        private static Order supportMoveAlternative(BoardState board, Order move, Order peer) {
            Order alternative = Order.supportMove(move.unit(), board.locationOf(peer.unit()), peer.target());
            return valid(board, alternative) ? alternative : null;
        }

        private static boolean valid(BoardState board, Order order) {
            return Orders.orderIsValid(new adjudication.Order(order, board.locationOf(order.unit())));
        }

        private static TacticalPrinciple.Category stationaryCategory(Order order) {
            return switch (order.orderType()) {
                case HOLD -> TacticalPrinciple.Category.FRIENDLY_HOLD_UNIT;
                case CONVOY -> TacticalPrinciple.Category.FRIENDLY_CONVOY_FLEET;
                case SUPPORT -> TacticalPrinciple.Category.FRIENDLY_SUPPORT_UNIT;
                default -> throw new IllegalArgumentException("Order is not stationary: " + order);
            };
        }

        private static boolean stationaryOrder(Order order) {
            return order.orderType() == OrderType.HOLD || order.orderType() == OrderType.SUPPORT
                    || order.orderType() == OrderType.CONVOY;
        }

        private static boolean active(BoardState board, Order order) {
            return board.locations().containsKey(order.unit());
        }

        private static UnitId unitAt(BoardState board, Province province) {
            return board.locations().entrySet().stream()
                    .filter(entry -> Province.canonical(entry.getValue()) == Province.canonical(province))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        private static Comparator<Order> orderOrder(BoardState board) {
            return Comparator.comparing(order -> Classifier.signature(board, List.of(order)));
        }

        private static String explanation(TacticalPrinciple.Category category) {
            return switch (category) {
                case FRIENDLY_CONVOY_FLEET ->
                        "The move targets a friendly convoying fleet's occupied province; "
                        + "it does not reinforce convoy defense and may waste an order. "
                        + "An unsuccessful friendly move does not itself disrupt the convoy. "
                        + "Supporting the stationary fleet may be worth comparing.";
                case FRIENDLY_SUPPORT_UNIT ->
                        "The move targets a friendly supporting unit's occupied province; "
                        + "it does not reinforce that unit and may waste an order. "
                        + "Supporting the stationary unit may be worth comparing.";
                case FRIENDLY_HOLD_UNIT ->
                        "The move targets a friendly holding unit's occupied province; "
                        + "it does not reinforce that hold and may waste an order. "
                        + "Supporting the held unit may be worth comparing.";
                case FRIENDLY_PROVINCE_ATTACK ->
                        "The move joins another friendly move against a foreign unit's occupied province; "
                        + "replacing one attack with support may be worth comparing.";
            };
        }

        private static String principleId(TacticalPrinciple.Category category) {
            return switch (category) {
                case FRIENDLY_CONVOY_FLEET -> "friendly-convoy-fleet";
                case FRIENDLY_SUPPORT_UNIT -> "friendly-support-unit";
                case FRIENDLY_HOLD_UNIT -> "friendly-hold-unit";
                case FRIENDLY_PROVINCE_ATTACK -> "friendly-province-attack";
            };
        }

        private static String findingId(BoardState board, String principle, Occurrence occurrence) {
            List<Order> identity = occurrence.category()
                    == TacticalPrinciple.Category.FRIENDLY_PROVINCE_ATTACK
                    ? List.of(occurrence.move()) : List.of(occurrence.move(), occurrence.related());
            return principle + ":" + Classifier.signature(board, identity);
        }

        private record Occurrence(Order move, Order related, TacticalPrinciple.Category category) { }
}
