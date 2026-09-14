package adjudication;

import java.util.Collections;
import java.util.IdentityHashMap;
//import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, branch-local adjudication context.<br><br>
 * <p>
 * This class holds temporary assumptions that must not be written directly to
 * shared Order instances while `Judge.resolve()` is exploring recursive branches.<br><br>
 * <p>
 * Order identity is intentional. Two distinct Order objects may compare equal
 * as submitted orders, but a context applies to the specific objects being
 * resolved in one Judge invocation.<br><br>
 * <p>
 * Currently only contains Suppressed H2H Orders.
 */
public record ResolutionContext(Set<Order> suppressedHeadToHeadOrders) {

    public ResolutionContext {  // ensures immutability
        Objects.requireNonNull(suppressedHeadToHeadOrders);
        Set<Order> copy = Collections.newSetFromMap(
                new IdentityHashMap<>());
        copy.addAll(suppressedHeadToHeadOrders);
        suppressedHeadToHeadOrders = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns an empty context with no temporary assumptions.
     */
    public static ResolutionContext empty() {
        return new ResolutionContext(
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Returns whether normal H2H adjudication is suppressed for an
     * order in this branch.
     */
    public boolean suppressesHeadToHead(Order order) {
        return this.suppressedHeadToHeadOrders.contains(order);
    }

    /**
     * Returns a new context in which both members of a convoy swap are treated
     * as non-head-to-head movers.
     * <p>
     * The original context is not mutated.
     */
    public ResolutionContext withHeadToHeadSuppressed(
            Order first,
            Order second
    ) {
        Set<Order> copy = Collections.newSetFromMap(
                new IdentityHashMap<>());
        copy.addAll(this.suppressedHeadToHeadOrders);
        copy.add(first);
        copy.add(second);
        return new ResolutionContext(copy);
    }

    /**
     * Returns a diagnostic-only count of the currently suppressed orders.
     */
    public int suppressedHeadToHeadCount() {
        return this.suppressedHeadToHeadOrders.size();
    }

}