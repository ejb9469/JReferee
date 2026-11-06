package phase.movement;

import adjudication.Judge;
import adjudication.Justice;
import adjudication.SzykmanJustice;
import adjudication.util.OrderComparator;
import contracts.OrderForm;
import domain.OrderType;
import domain.Province;
import phase.MovementInput;
import phase.Order;
import phase.Processor;
import phase.UnitId;
import performance.PerformanceStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Normalizes movement submissions and converts adjudicator output for Game.
 * The default retains SzykmanJustice. JUDGE is an explicit single-run option
 * and can disagree with the reference policy in ambiguous positions.
 */
public final class MovementProcessor
        implements Processor<MovementInput, MovementResult> {

    private static final PerformanceStats.Scope UNTIMED = () -> { };

    private final Policy policy;
    private final int trialCount;
    private final long shuffleSeed;
    private final Diagnostics diagnostics;


    public MovementProcessor(Policy policy, int trialCount, long shuffleSeed) {
        this(policy, trialCount, shuffleSeed, null);
    }

    /**
     * Supplies an adjudicator and optional caller-owned diagnostics.
     * JUDGE uses canonical order and ignores trialCount and shuffleSeed.
     * A non-null collector may be shared by sequential root and rollout games.
     */
    public MovementProcessor(Policy policy, int trialCount, long shuffleSeed,
                             Diagnostics diagnostics) {

        this.policy = Objects.requireNonNull(policy, "policy");

        if (trialCount < 1)
            throw new IllegalArgumentException("trialCount must be at least 1");

        this.trialCount = trialCount;
        this.shuffleSeed = shuffleSeed;
        this.diagnostics = diagnostics;

    }

    public MovementProcessor() {
        this(
                Policy.SZYKMAN_JUSTICE,
                Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT);
    }


    @Override
    public MovementResult process(MovementInput input) {

        Judge producer = null;
        boolean completed = false;
        try (var timing = diagnostics == null ? UNTIMED : diagnostics.timings.measure("movement")) {
            Objects.requireNonNull(input, "input");

            Map<UnitId, Province> startingLocations = input.startingLocations();
            List<NormalizedOrder> normalizedOrders =
                    normalize(startingLocations, input.submittedOrders());

            List<adjudication.Order> workOrders = new ArrayList<>(normalizedOrders.size());

            for (NormalizedOrder normalized : normalizedOrders) {
                Province currentLocation = startingLocations.get(normalized.unit());
                workOrders.add(new adjudication.Order(normalized, currentLocation));
            }

            producer = createJudge(workOrders);
            producer.judge();
            MovementResult result = MovementResult.from(startingLocations, normalizedOrders, producer);
            completed = true;
            return result;
        } finally {
            if (diagnostics != null)
                diagnostics.record(producer, completed);
        }

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
            case JUDGE -> {
                List<adjudication.Order> ordered = new ArrayList<>(workOrders);
                ordered.sort(new OrderComparator());
                yield new Judge(ordered);
            }
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
        SZYKMAN_JUSTICE,
        JUDGE
    }

    /**
     * Single-threaded movement counters, independent of Game's rollback.
     * Calls count attempts.
     * Internal Judge passes are included in elapsed time,
     *      but only top-level adjudicator invocations receive separate counters.
     */
    public static final class Diagnostics {
        private final PerformanceStats timings;
        private long movements;
        private long failures;
        private long plainCalls;
        private long baselineCalls;
        private long shuffledTrials;
        private long juryProbes;
        private long fallbackCalls;

        public Diagnostics(PerformanceStats timings) {
            this.timings = Objects.requireNonNull(timings, "timings");
        }

        private void record(Judge judge, boolean completed) {
            movements++;
            if (!completed)
                failures++;
            if (judge instanceof Justice justice) {
                Justice.Workload work = justice.workload();
                baselineCalls += work.baselineCalls();
                shuffledTrials += work.shuffledTrials();
                juryProbes += work.juryProbes();
                fallbackCalls += work.fallbackCalls();
            } else if (judge != null) {
                plainCalls++;
            }
        }

        public Counts snapshot() {
            return new Counts(movements, failures, plainCalls, baselineCalls,
                    shuffledTrials, juryProbes, fallbackCalls);
        }
    }

    public record Counts(long movements, long failures, long plainCalls,
                         long baselineCalls, long shuffledTrials,
                         long juryProbes, long fallbackCalls) { }

}