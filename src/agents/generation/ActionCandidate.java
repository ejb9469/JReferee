package agents.generation;

import contracts.OrderForm;
import domain.Nation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * One complete action for a nation.
 *
 * <p>`logPrior` is the natural logarithm of the complete action's probability
 * under the anchor policy for the current state.<br>
 * For an autoregressive policy, it includes the conditional log probabilities of every order in the action.<br>
 * Negative inf. is a sentinel that represents 0 prior probability.</p>
 */
public record ActionCandidate(
        Nation power,
        List<OrderForm> orders,
        double logPrior,
        Source source
) {

    public ActionCandidate {
        Objects.requireNonNull(power, "power");
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        Objects.requireNonNull(source, "source");

        if (Double.isNaN(logPrior) || logPrior > 0.0)
            throw new IllegalArgumentException(
                    "logPrior must be at most zero");

        for (OrderForm order : orders)
            if (order.owner() != power)
                throw new IllegalArgumentException(
                        "Action contains an order for another nation: " + order);
    }

    public ActionCandidate(
            Nation power,
            Collection<? extends OrderForm> orders,
            double logPrior,
            Source source
    ) {
        this(power, copyOrders(orders), logPrior, source);
    }

    /**
     * Produces a shallow copy of a list of Orders.
     */
    // TODO: Create an `OrderForm` abstract class akin to `adjudication.util.Orders`, & implement deep & shallow copy methods there
    @Deprecated  // see above
    private static List<OrderForm> copyOrders(
            Collection<? extends OrderForm> orders
    ) {
        Objects.requireNonNull(orders, "orders");
        List<OrderForm> copy = new ArrayList<>(orders.size());
        copy.addAll(orders);
        return copy;
    }

    /**
     * Method used to produce the action candidate.
     */
    public enum Source {
        POLICY,
        POLICY_SAMPLE,
        BEAM_SEARCH,
        HEURISTIC,
        EXPLORATION
    }

}