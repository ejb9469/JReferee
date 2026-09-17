package phase.movement;

import adjudication.Judge;
import adjudication.Justice;
import adjudication.SzykmanJustice;
import domain.OrderType;
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

/**
 * Converts immutable movement submissions into private mutable "work orders",
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
                Justice.SHUFFLE_SEED_DEFAULT
        );
    }


    @Override
    public MovementResult process(MovementInput input) {

        Objects.requireNonNull(input, "input");

        List<NormalizedOrder> normalizedOrders = normalize(
                input.activeUnits(),
                input.submittedOrders());

        List<adjudication.Order> workOrders = new ArrayList<>(
                normalizedOrders.size());

        for (NormalizedOrder normalized : normalizedOrders)
            workOrders.add(toWorkOrder(normalized.order()));

        Judge producer = createJudge(workOrders);
        producer.judge();

        return MovementResult.from(
                input.activeUnits(),
                normalizedOrders,
                producer);

    }


    /**
     * Produces exactly one order for every active unit:
     *
     * <ul>
     *     <li>unknown order issuers cause processing to fail;</li>
     *     <li>duplicate orders for a unit cause processing to fail;</li>
     *     <li>missing orders become 'synthesized' HOLD orders.</li>
     * </ul>
     */
    private List<NormalizedOrder> normalize(
            Collection<UnitId> activeUnits,
            Collection<Order> submittedOrders
    ) {

        Map<UnitId, Order> ordersByUnit = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Order submitted : submittedOrders) {
            requireMovementOrder(submitted);
            UnitId unit = unitOf(submitted);
            if (!activeUnits.contains(unit)) {
                errors.add("Order names an inactive unit: " + submitted);
                continue;
            }
            if (ordersByUnit.putIfAbsent(unit, submitted) != null) {
                errors.add("Multiple orders submitted for unit: " + unit);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    String.join(System.lineSeparator(), errors));
        }

        List<NormalizedOrder> normalized = new ArrayList<>(activeUnits.size());

        for (UnitId activeUnit : activeUnits) {
            Order submitted = ordersByUnit.get(activeUnit);
            if (submitted == null) {
                normalized.add(
                        new NormalizedOrder(
                                activeUnit,
                                Order.hold(
                                        activeUnit.owner(),
                                        activeUnit.unitType(),
                                        activeUnit.origin()
                                ),
                                false
                        )
                );
            } else {
                normalized.add(
                        new NormalizedOrder(activeUnit, submitted, true));
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
     * Creates a private mutable work order for the `adjudication` engine.
     */
    private adjudication.Order toWorkOrder(Order order) {
        return new adjudication.Order(
                order.owner(),
                order.unitType(),
                order.origin(),
                order.type(),
                order.target(),
                order.auxiliaryTarget());
    }

    private UnitId unitOf(Order order) {
        return new UnitId(
                order.owner(),
                order.unitType(),
                order.origin());
    }

    private void requireMovementOrder(Order order) {
        if (order.type() != OrderType.MOVE
                && order.type() != OrderType.HOLD
                && order.type() != OrderType.SUPPORT
                && order.type() != OrderType.CONVOY) {
            throw new IllegalArgumentException(
                    "MovementProcessor cannot process "
                            + order.type()
                            + ": "
                            + order);
        }
    }


    public enum Policy {
        JUSTICE,
        SZYKMAN_JUSTICE
    }


}