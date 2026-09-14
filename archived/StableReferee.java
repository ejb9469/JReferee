package adjudication;

import domain.Order;
import adjudication.util.Orders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects a deterministic result from `Justice` and `SzykmanJustice` runs across
 * a fixed sequence of shuffle seeds.<br><br>
 *
 * A stable `SzykmanJustice` result is preferred. If `SzykmanJustice` is unstable
 * but `Justice` is stable, the stable ordinary `Justice` result is selected.
 */
public final class StableReferee {


    // Constants \\

    public static final int NUM_TRIALS_DEFAULT =
            Referee.NUM_TRIALS_DEFAULT;

    public static final List<Long> STABILITY_SEEDS = List.of(
            0L, 1L, 2L, 3L, 4L,
            5L, 6L, 7L, 8L, 9L
    );


    // Core state \\

    private final List<Order> submittedOrders;
    private final int numTrials;
    private final List<Long> seeds;

    private Collection<Order> orders;


    // Constructors \\

    public StableReferee(Collection<Order> orders) {
        this(
                orders,
                NUM_TRIALS_DEFAULT,
                STABILITY_SEEDS
        );
    }

    public StableReferee(
            Collection<Order> orders,
            int numTrials,
            Collection<Long> seeds
    ) {
        this.submittedOrders = new ArrayList<>(
                Orders.deepCopy(orders)
        );
        this.numTrials = numTrials;
        this.seeds = new ArrayList<>(seeds);
        this.orders = new ArrayList<>();
    }


    // Public accessors \\

    public Collection<Order> getOrders() {
        return new ArrayList<>(Orders.deepCopy(this.orders));
    }


    // `judge()` method \\

    public void judge() {

        Map<String, Collection<Order>> szykmanOutcomes =
                this.collectSzykmanOutcomes();

        if (szykmanOutcomes.size() == 1) {
            this.orders = new ArrayList<>(
                    Orders.deepCopy(
                            szykmanOutcomes.values().iterator().next()
                    )
            );
            return;
        }

        Map<String, Collection<Order>> refereeOutcomes =
                this.collectRefereeOutcomes();

        if (refereeOutcomes.size() == 1) {
            this.orders = new ArrayList<>(
                    Orders.deepCopy(
                            refereeOutcomes.values().iterator().next()
                    )
            );
            return;
        }

        /*
         * Preserve the current SzykmanJustice baseline if neither policy is
         * stable across the fixed seed sequence.
         */
        SzykmanReferee fallback = new SzykmanReferee(
                this.copySubmittedOrders(),
                this.numTrials,
                Referee.SHUFFLE_SEED_DEFAULT
        );

        fallback.judge();

        this.orders = new ArrayList<>(
                Orders.deepCopy(fallback.getOrders())
        );

    }


    // Outcome collection \\

    private Map<String, Collection<Order>> collectSzykmanOutcomes() {

        Map<String, Collection<Order>> outcomes = new LinkedHashMap<>();

        for (long seed : this.seeds) {
            SzykmanReferee referee = new SzykmanReferee(
                    this.copySubmittedOrders(),
                    this.numTrials,
                    seed
            );

            referee.judge();

            this.recordOutcome(outcomes, referee.getOrders());
        }

        return outcomes;

    }

    private Map<String, Collection<Order>> collectRefereeOutcomes() {

        Map<String, Collection<Order>> outcomes = new LinkedHashMap<>();

        for (long seed : this.seeds) {
            Referee referee = new Referee(
                    this.copySubmittedOrders(),
                    this.numTrials,
                    seed
            );

            referee.judge();

            this.recordOutcome(outcomes, referee.getOrders());
        }

        return outcomes;

    }

    private void recordOutcome(
            Map<String, Collection<Order>> outcomes,
            Collection<Order> outcome
    ) {
        outcomes.putIfAbsent(
                this.outcomeKey(outcome),
                new ArrayList<>(Orders.deepCopy(outcome))
        );
    }

    private List<Order> copySubmittedOrders() {
        return new ArrayList<>(
                Orders.deepCopy(this.submittedOrders)
        );
    }


    // Outcome identity \\

    private String outcomeKey(Collection<Order> outcome) {

        List<Order> orderedOutcome = Orders.conformOrder(
                outcome,
                this.submittedOrders
        );

        List<String> entries = new ArrayList<>();

        for (Order order : orderedOutcome) {
            entries.add(
                    Referee.orderIdentityKey(
                            Referee.originalOrderOf(order)
                    )
                            + "\u001Fresolved=" + order.resolved
                            + "\u001Fverdict=" + order.verdict
                            + "\u001Fsnapshot="
                            + (order.getSnapshot() != null)
            );
        }

        return String.join("\n", entries);

    }


}