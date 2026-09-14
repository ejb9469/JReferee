package adjudication;

import domain.Order;
import adjudication.util.Orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an ordinary resolution; 1 non-speculative resolution pass.
 */
public record OrdinaryResolution(
        Map<String, ResolutionState> states,
        List<List<Order>> unresolvedComponents
) implements Resolution {

    public OrdinaryResolution(
            Map<String, ResolutionState> states,
            List<List<Order>> unresolvedComponents
    ) {
        this.states = Collections.unmodifiableMap(
                new LinkedHashMap<>(states));
        List<List<Order>> copiedComponents = new ArrayList<>();
        for (List<Order> component : unresolvedComponents) {
            copiedComponents.add(List.copyOf(  // make sure to return immutable (`List.of`)
                    Orders.deepCopy(component)));
        }
        this.unresolvedComponents = List.copyOf(  // make sure to return immutable (`List.of`)
                copiedComponents);
    }


    @Override
    public ResolutionState stateOf(Order order) {
        return this.states.getOrDefault(
                Orders.keyOf(order),
                ResolutionState.UNKNOWN);
    }

    @Override
    public boolean isComplete() {
        return this.unresolvedComponents.isEmpty();
    }

}