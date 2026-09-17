package testing;

import adjudication.Judge;
import adjudication.SzykmanJustice;
import adjudication.Order;
import adjudication.util.Orders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DATCAdjTestCase extends AdjudicatorTestCase {


    public DATCAdjTestCase(String name, Order... orders) {
        super(name, orders);
    }

    public DATCAdjTestCase(String name, List<Order> orders) {
        super(name, orders);
    }

    public DATCAdjTestCase(AdjudicatorTestCase testCase) {
        super(testCase);
    }

    @Override
    protected void judge() {

        Judge judge;
        if (!orders.isEmpty())
            judge = new SzykmanJustice(new ArrayList<>(orders));
        else
            judge = new SzykmanJustice();

        judge.judge();
        Collection<Order> adjudicatedOrders =
                Orders.conformOrder(judge.getOrders(), this.orders);

        for (Order order : adjudicatedOrders)
            actualFields.add(new boolean[]{order.verdict});  // Could expand with more fields later

    }


}