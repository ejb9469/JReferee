package testing;

import analysis.JointOrders;
import analysis.OrderPlan;
import analysis.RoutePrediction;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.util.*;


public class JointOrdersSelfCheck {

    private JointOrdersSelfCheck() {  }


    public static void main(String[] args) {

        BoardState board = StandardGameFactory.create1901().board();

        UnitId london = unitAt(board, Province.Lon);
        UnitId edinburgh = unitAt(board, Province.Edi);
        UnitId liverpool = unitAt(board, Province.Lvp);

        Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();

        predictions.put(london, prediction(
                Order.move(london, Province.NTH), 3,
                Order.move(london, Province.ENG), 1));

        predictions.put(edinburgh, prediction(
                Order.move(edinburgh, Province.NWG), 3,
                Order.hold(edinburgh), 1));

        predictions.put(liverpool, new RoutePrediction(
                "POSITION", 0, Map.of(Order.hold(liverpool), 4L)));

        JointOrders search = new JointOrders(2, 8);

        List<OrderPlan> plans = search.generate(board, Nation.ENGLAND, predictions);

        require(plans.size() == 4, "Expected four candidate combinations");

        for (OrderPlan plan : plans) {

            require(plan.orders().size() == 3, "Plan does not cover all English units");

            Set<UnitId> issuers = new HashSet<>();

            for (Order order : plan.orders()) {
                require(order.owner() == Nation.ENGLAND, "Wrong nation in plan");
                require(issuers.add(order.unit()), "Duplicate issuer in plan");
            }

        }

        OrderPlan best = plans.getFirst();

        require(orderFor(best, london).target() == Province.NTH,
                "Expected the more frequent London move");
        require(orderFor(best, edinburgh).target() == Province.NWG,
                "Expected the more frequent Edinburgh move");

        require(Math.abs(best.logPreference() - Math.log(0.75 * 0.75)) < 1e-12,
                "Incorrect preference score");

        for (int index = 1; index < plans.size(); index++)
            require(plans.get(index - 1).logPreference() >= plans.get(index).logPreference(),
                    "Plans are not ranked by preference");

        Map<UnitId, RoutePrediction> reversed = new LinkedHashMap<>();
        reversed.put(liverpool, predictions.get(liverpool));
        reversed.put(edinburgh, predictions.get(edinburgh));
        reversed.put(london, predictions.get(london));

        require(plans.equals(search.generate(board, Nation.ENGLAND, reversed)),
                "Input map order changed the result");

        require(new JointOrders(2, 2).generate(board, Nation.ENGLAND, predictions).size() == 2,
                "Beam limit was not respected");

        Map<UnitId, RoutePrediction> missing = new HashMap<>(predictions);
        missing.remove(liverpool);

        require(search.generate(board, Nation.ENGLAND, missing).isEmpty(),
                "Missing evidence must not silently become a hold");

        Map<UnitId, RoutePrediction> wrongIssuer = new HashMap<>(predictions);
        wrongIssuer.put(london, new RoutePrediction(
                "POSITION", 0, Map.of(Order.hold(edinburgh), 1L)));

        require(rejects(() -> search.generate(board, Nation.ENGLAND, wrongIssuer)),
                "Mismatched prediction issuer was accepted");

        List<Order> opponents = new ArrayList<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() != Nation.ENGLAND)
                opponents.add(Order.hold(unit));

        MovementResult result = search.evaluate(
                board, best, opponents, new MovementProcessor());

        require(result.finalLocations().get(london) == Province.NTH,
                "London move was not resolved");
        require(result.finalLocations().get(edinburgh) == Province.NWG,
                "Edinburgh move was not resolved");

        require(board.locationOf(london) == Province.Lon,
                "Evaluation mutated the starting board");

        require(orderFor(best, london).target() == Province.NTH,
                "Evaluation mutated the submitted plan");

        require(rejects(() -> search.evaluate(
                        board, best, List.of(), new MovementProcessor())),
                "Missing opponent orders were silently synthesized");

        List<Order> duplicateOpponent = new ArrayList<>(opponents);
        duplicateOpponent.add(opponents.getFirst());

        require(rejects(() -> search.evaluate(
                        board, best, duplicateOpponent, new MovementProcessor())),
                "Duplicate opponent issuer was accepted");

        OrderPlan incomplete = new OrderPlan(
                Nation.ENGLAND, List.of(Order.hold(london)), 0);

        require(rejects(() -> search.evaluate(
                        board, incomplete, opponents, new MovementProcessor())),
                "Incomplete national plan was accepted");

        System.out.println("JointOrders checks passed.");

    }


    private static RoutePrediction prediction(
            Order first, long firstCount, Order second, long secondCount) {

        Map<Order, Long> counts = new LinkedHashMap<>();
        counts.put(first, firstCount);
        counts.put(second, secondCount);

        return new RoutePrediction("POSITION", 0, counts);

    }

    private static UnitId unitAt(BoardState board, Province province) {

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (entry.getValue() == province)
                return entry.getKey();

        throw new IllegalArgumentException("No unit at " + province);

    }

    private static Order orderFor(OrderPlan plan, UnitId unit) {

        for (Order order : plan.orders())
            if (order.unit().equals(unit))
                return order;

        throw new IllegalArgumentException("Plan has no order for unit");

    }

    private static boolean rejects(Runnable action) {

        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return true;
        }

        return false;

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}