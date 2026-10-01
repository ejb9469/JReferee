package testing;

import adjudication.util.OrderComparator;
import adjudication.util.Orders;
import analysis.openings.Classifier;
import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.adjustments.WaiveOrder;
import phase.retreats.RetreatOrder;

import java.util.*;


public class OrderFormTestCase implements TestCase {


    private TestEvaluation evaluation;


    @Override
    public void eval(boolean print) {

        TestEvaluation.Builder checks = new TestEvaluation.Builder();

        UnitId army = new UnitId(UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        BoardState board = new BoardState(Map.of(army, Province.Bur), Map.of());
        Order move = Order.move(army, Province.Mun);

        checks.expect("Creation origin remains unchanged", Province.Par, move.origin());
        checks.expect("Movement notation uses the supplied board location",
                "Fr A Bur - Mun", OrderForm.format(move, board.locationOf(army)));

        adjudication.Order work = new adjudication.Order(move, board.locationOf(army));

        checks.expect("Work-order origin", Province.Bur, work.origin());
        checks.expect("Notation agrees across representations",
                OrderForm.format(move, Province.Bur), work.toString());

        RetreatOrder retreat = new RetreatOrder(army, Province.Gas);
        DisbandOrder disband = new DisbandOrder(Nation.FRANCE, army);
        WaiveOrder waive = new WaiveOrder(Nation.FRANCE);
        BuildOrder build = new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre);

        checks.expect("Retreat away from birthplace",
                "Fr A Bur R Gas", OrderForm.format(retreat, Province.Bur));
        checks.expect("Disband away from birthplace",
                "Fr A Bur DESTROY", OrderForm.format(disband, Province.Bur));
        checks.expect("Waive notation", "Fr WAIVE", OrderForm.format(waive, null));
        checks.expect("Waive has no origin", null, waive.origin());
        checks.expect("Build has no target", null, build.target());
        checks.expect("Disband has no target", null, disband.target());
        checks.expect("Build notation", "Fr F Bre BUILD", OrderForm.format(build, build.location()));
        checks.expect("Unit-only notation", "Fr A Bur", OrderForm.format(army, Province.Bur));

        checks.expect("Support-hold notation",
                "Fr A Bur S Mar H",
                OrderForm.format(Order.supportHold(army, Province.Mar), Province.Bur));

        UnitId fleet = new UnitId(UUID.randomUUID(), Nation.RUSSIA, UnitType.FLEET, Province.Sev);

        checks.expect("Split coast retained",
                "Ru F BLA - Bul/ec",
                OrderForm.format(Order.move(fleet, Province.BulEC), Province.BLA));

        List<OrderForm> mixed = List.of(move, army, waive, retreat);

        checks.expect("Generic filtering retains the original objects",
                List.of(move), new ArrayList<>(Orders.pruneForOrderType(OrderType.MOVE, mixed)));
        checks.expect("Unit-only forms can be filtered explicitly",
                List.of(army), new ArrayList<>(Orders.pruneForOrderType(null, mixed)));

        OrderComparator comparator = new OrderComparator(
                form -> form instanceof Order order
                        ? board.locationOf(order.unit()) : form.origin());

        checks.expect("Board-aware comparison across representations",
                0, comparator.compare(move, work));

        adjudication.Order otherTarget = new adjudication.Order(
                Nation.FRANCE, UnitType.ARMY, Province.Bur, OrderType.MOVE, Province.Ruh);

        checks.expect("Targets participate in comparison",
                true, comparator.compare(work, otherTarget) < 0);

        checks.expect("Nullable fields sort without throwing",
                true, new OrderComparator().compare(army, waive) < 0);

        work.takeSnapshot();
        String key = Orders.keyOf(work);
        work.orderType = OrderType.HOLD;
        work.pos1 = null;

        checks.expect("Original-order keys survive rewriting", key, Orders.keyOf(work));

        checkOpeningIdentity(checks);

        evaluation = checks.build();

        if (print)
            printEval();

    }

    private static void checkOpeningIdentity(TestEvaluation.Builder checks) {

        BoardState firstBoard = StandardGameFactory.create1901().board();
        Map<UnitId, Province> units = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : firstBoard.locations().entrySet()) {
            UnitId old = entry.getKey();
            units.put(new UnitId(UUID.randomUUID(), old.owner(), old.unitType(), Province.Swi),
                    entry.getValue());
        }

        BoardState secondBoard = new BoardState(units, firstBoard.owners());

        List<Order> firstOrders = new ArrayList<>();
        List<Order> secondOrders = new ArrayList<>();

        for (UnitId unit : firstBoard.locations().keySet())
            if (unit.owner() == Nation.ENGLAND)
                firstOrders.add(Order.hold(unit));

        for (UnitId unit : secondBoard.locations().keySet())
            if (unit.owner() == Nation.ENGLAND)
                secondOrders.add(Order.hold(unit));

        GameMoment moment = new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

        checks.expect("Opening signatures ignore UUID and creation origin",
                new Classifier(moment, firstBoard, firstOrders).signature(Nation.ENGLAND),
                new Classifier(moment, secondBoard, secondOrders).signature(Nation.ENGLAND));

    }


    @Override
    public String getName() {
        return "OrderForm conventions";
    }

    @Override
    public String getEval() {
        return evaluation == null ? null : evaluation.format(getName());
    }

    @Override
    public int getScore() {
        return evaluation == null ? 0 : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null ? 0 : evaluation.size();
    }

    @Override
    public void printEval() {
        System.out.println(evaluation == null ? "UNEVALUATED " + getName() : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), getName());
    }

    public static void main(String[] args) {

        OrderFormTestCase test = new OrderFormTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("OrderForm tests failed");

    }

}