package phase.movement;

import adjudication.Judge;
import adjudication.Justice;
import adjudication.SzykmanJustice;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.Processor;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts immutable movement submissions into private mutable work orders,
 * adjudicates them, and exposes an immutable movement result.
 */
public final class MovementProcessor
        implements Processor<MovementInput, MovementResult> {

    private final Policy policy;
    private final int trialCount;
    private final long shuffleSeed;

    public MovementProcessor(
            Policy policy,
            int trialCount,
            long shuffleSeed
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (trialCount < 1)
            throw new IllegalArgumentException(
                    "trialCount must be at least 1");
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

        Map<UnitId, Province> startingLocations =
                input.startingLocations();

        List<NormalizedOrder> normalizedOrders =
                normalize(startingLocations, input.submittedOrders());

        List<adjudication.Order> workOrders = new ArrayList<>(normalizedOrders.size());

        for (NormalizedOrder normalized : normalizedOrders) {
            Province currentLocation = startingLocations.get(
                    normalized.unit());
            workOrders.add(
                    toWorkOrder(
                            normalized.order(),
                            currentLocation));
        }

        Judge producer = createJudge(workOrders);
        producer.judge();

        return MovementResult.from(
                startingLocations,
                normalizedOrders,
                producer);

    }

    /**
     * Produces exactly one order for every unit on the starting board:
     *
     * <ul>
     *     <li>unknown order issuers cause processing to fail;</li>
     *     <li>duplicate orders for one unit cause processing to fail;</li>
     *     <li>missing orders become synthesized HOLD orders.</li>
     * </ul>
     */
    private List<NormalizedOrder> normalize(
            Map<UnitId, Province> startingLocations,
            Collection<Order> submittedOrders
    ) {

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
                errors.add(
                        "Multiple orders submitted for unit: " + unit);

        }

        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    String.join(System.lineSeparator(), errors));

        List<NormalizedOrder> normalized = new ArrayList<>(startingLocations.size());

        for (UnitId activeUnit : startingLocations.keySet()) {
            Order submitted = ordersByUnit.get(activeUnit);

            if (submitted == null) {
                normalized.add(
                        new NormalizedOrder(
                                activeUnit,
                                Order.hold(activeUnit),
                                false));
            } else {
                normalized.add(
                        new NormalizedOrder(
                                activeUnit,
                                submitted,
                                true));
            }
        }

        return normalized;

    }

    private Judge createJudge(Collection<adjudication.Order> workOrders) {
        return switch (policy) {
            case JUSTICE -> new Justice(
                    workOrders,
                    trialCount,
                    shuffleSeed);
            case SZYKMAN_JUSTICE -> new SzykmanJustice(
                    workOrders,
                    trialCount,
                    shuffleSeed);
        };
    }

    /**
     * Creates a private mutable work order for the adjudication engine.
     */
    private adjudication.Order toWorkOrder(
            Order order,
            Province currentLocation
    ) {
        return new adjudication.Order(
                order.owner(),
                order.unitType(),
                currentLocation,
                order.type(),
                order.target(),
                order.auxiliaryTarget());
    }

    private void requireMovementOrder(Order order) {
        if (order.type() != OrderType.MOVE
                && order.type() != OrderType.HOLD
                && order.type() != OrderType.SUPPORT
                && order.type() != OrderType.CONVOY)
            throw new IllegalArgumentException(
                    "MovementProcessor cannot process " + order.type() + ": " + order);
    }

    public enum Policy {
        JUSTICE,
        SZYKMAN_JUSTICE
    }

}