package adjudication;

import contracts.StrictState;
import domain.Order;
import adjudication.util.Orders;

import java.util.Collection;

public class StrictJudge extends Judge implements StrictState {

    protected Collection<Order> voidedOrders = null;


    public StrictJudge(Collection<Order> orders) {
        super(orders);
        enforceStasis();
    }

    public StrictJudge() {
        super();
        enforceStasis();
    }


    @Override
    public void judge() {

        enforceStasis();
        super.judge();

    }

    @Override
    public void enforceStasis() {

        this.cleanseOrders();
        if (this.voidedOrders == null || this.voidedOrders.isEmpty())
            System.err.printf("`%s:enforceStasis()`: No orders voided / cleansed\n",
                    this.getClass().getSimpleName());

    }

    protected void cleanseOrders() {
        // `Orders.cleanse()` is a mutator method,
        // It returns the invalid orders now removed from `this.orders`
        this.voidedOrders = Orders.cleanse(this.orders);
    }

}