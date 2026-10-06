package phase.movement;

import adjudication.Judge;
import adjudication.Justice;
import adjudication.SzykmanJustice;
import contracts.OrderForm;
import domain.OrderType;
import domain.Province;
import phase.MovementInput;
import phase.Order;
import phase.Processor;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class MovementProcessor
        implements Processor<MovementInput, MovementResult> {

    private final Policy policy;
    private final int trialCount;
    private final long shuffleSeed;


    public MovementProcessor(Policy policy, int trialCount, long shuffleSeed) {

        this.policy = Objects.requireNonNull(policy, "policy");

        if (trialCount < 1)
            throw new IllegalArgumentException("trialCount must be at least 1");

        this.trialCount = trialCount;
        this.shuffleSeed = shuffleSeed;

    }

    public MovementProcessor() {
        this(
                Policy.SZYKMAN_JUSTICE,
                Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT);
    }


    @Override
    public MovementResult process(MovementInput input) {

        Objects.requireNonNull(input, "input");

        Map<UnitId, Province> startingLocations = input.startingLocations();
        List<NormalizedOrder> normalizedOrders =
                normalize(startingLocations, input.submittedOrders());

        List<adjudication.Order> workOrders = new ArrayList<>(normalizedOrders.size());

        for (NormalizedOrder normalized : normalizedOrders) {
            Province currentLocation = startingLocations.get(normalized.unit());
            workOrders.add(new adjudication.Order(normalized, currentLocation));
        }

        Judge producer = createJudge(workOrders);
        producer.judge();

        return MovementResult.from(startingLocations, normalizedOrders, producer);

    }

    private List<NormalizedOrder> normalize(
            Map<UnitId, Province> startingLocations, Collection<Order> submittedOrders) {

        Map<UnitId, Order> ordersByUnit = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Order submitted : submittedOrders) {

            requireMovementOrder(submitted);

            UnitId unit = submitted.unit();

            if (!startingLocations.containsKey(unit)) {
                errors.add("Order names a unit absent from the starting board: " + submitted);
                continue;
            }

            if (ordersByUnit.putIfAbsent(unit, submitted) != null)
                errors.add("Multiple orders submitted for unit: " + unit);

        }

        if (!errors.isEmpty())
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));

        List<NormalizedOrder> normalized = new ArrayList<>(startingLocations.size());

        for (UnitId activeUnit : startingLocations.keySet()) {

            Order submitted = ordersByUnit.get(activeUnit);

            // Missing submissions remain distinguishable from explicit holds.
            normalized.add(new NormalizedOrder(
                    activeUnit,
                    submitted == null ? Order.hold(activeUnit) : submitted,
                    submitted != null));

        }

        return normalized;

    }

    private Judge createJudge(Collection<adjudication.Order> workOrders) {
        return switch (policy) {
            case JUSTICE -> new Justice(workOrders, trialCount, shuffleSeed);
            case SZYKMAN_JUSTICE -> new SzykmanJustice(workOrders, trialCount, shuffleSeed);
        };
    }

    private void requireMovementOrder(OrderForm order) {

        Objects.requireNonNull(order, "order");

        if (order.orderType() != OrderType.MOVE
                && order.orderType() != OrderType.HOLD
                && order.orderType() != OrderType.SUPPORT
                && order.orderType() != OrderType.CONVOY)
            throw new IllegalArgumentException(
                    "MovementProcessor cannot process " + order.orderType() + ": " + order);

    }


    public enum Policy {
        JUSTICE,
        SZYKMAN_JUSTICE
    }

}