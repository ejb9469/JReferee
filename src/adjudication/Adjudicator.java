package adjudication;

import java.util.Collection;


/**
 * `Adjudicator` is (only) for engines that mutate Orders to produce an adjudicated Order collection.
 * (For an immutable contract, use `Resolver`.)
 */
public interface Adjudicator {

    void judge();

    Collection<Order> getOrders();

}
