package parsing.diplobn;

import adjudication.Order;
import domain.Province;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;


/**
 * Compares DiploBN's recorded movement-order results with verdicts produced by
 * JReferee after independently adjudicating the imported movement submissions.
 *
 * <p>This comparison concerns movement orders only. DiploBN retreat and winter
 * adjustment data require their respective JReferee phase processors and a
 * chronological replay context.</p>
 */
public final class DiploBNAdjudicationComparison {


    private final DiploBNPhase phase;
    private final List<Entry> entries;


    private DiploBNAdjudicationComparison(
            DiploBNPhase phase,
            List<Entry> entries
    ) {

        this.phase = Objects.requireNonNull(phase, "phase");

        this.entries = List.copyOf(
                Objects.requireNonNull(entries, "entries"));

    }


    /**
     * Independently adjudicates a DiploBN phase and compares each translated
     * movement order against DiploBN's recorded result annotation.
     *
     * <p>Orders without a DiploBN result annotation are retained with a
     * {@code null} source verdict and do not count as matches or mismatches.</p>
     */
    public static DiploBNAdjudicationComparison compare(
            DiploBNPhase phase
    ) {

        Objects.requireNonNull(phase, "phase");

        DiploBNMovementAdjudicator adjudicator =
                new DiploBNMovementAdjudicator(phase);

        adjudicator.judge();

        return compare(
                phase,
                adjudicator.getOrders());

    }

    /**
     * Compares already-adjudicated movement orders against the historical
     * DiploBN result annotations held by one imported phase.
     */
    public static DiploBNAdjudicationComparison compare(
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(
                adjudicatedOrders,
                "adjudicatedOrders");

        List<Entry> entries = new ArrayList<>();

        for (Order order : adjudicatedOrders) {

            DiploBNOrderResolution sourceResolution =
                    locateResolution(order, phase.resolutions());

            Boolean sourceSuccessful = sourceResolution == null
                    ? null
                    : sourceResolution.successful();

            entries.add(
                    new Entry(
                            order,
                            sourceSuccessful,
                            sourceResolution == null
                                    ? null
                                    : sourceResolution.reason()
                    ));

        }

        return new DiploBNAdjudicationComparison(
                phase,
                entries);

    }


    /**
     * Imported source phase whose movement results were compared.
     */
    public DiploBNPhase phase() {
        return phase;
    }

    /**
     * One comparison entry for each independently adjudicated movement order.
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Number of source orders that had a DiploBN result annotation.
     */
    public int comparedCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.sourceSuccessful() != null)
                count++;
        }

        return count;

    }

    /**
     * Number of annotated source orders whose source result and JReferee
     * verdict agree.
     */
    public int matchingCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.matches())
                count++;
        }

        return count;

    }

    /**
     * Number of annotated source orders whose source result and JReferee
     * verdict differ.
     */
    public int mismatchingCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.compared() && !entry.matches())
                count++;
        }

        return count;

    }

    /**
     * Returns whether every available DiploBN result annotation agrees with
     * JReferee's independently adjudicated verdict.
     */
    public boolean matchesCompletely() {
        return mismatchingCount() == 0;
    }


    private static DiploBNOrderResolution locateResolution(
            Order order,
            List<DiploBNOrderResolution> resolutions
    ) {

        for (DiploBNOrderResolution resolution : resolutions) {

            if (resolution.retreatOrder())
                continue;

            if (resolution.nation() != order.owner)
                continue;

            if (!Province.equalsIgnoreCoast(
                    resolution.issuingProvince(),
                    order.pos0))
                continue;

            return resolution;

        }

        return null;

    }


    /**
     * Source and independently adjudicated outcome for one submitted movement
     * order.
     */
    public record Entry(
            Order adjudicatedOrder,
            Boolean sourceSuccessful,
            String sourceReason
    ) {

        public Entry {
            Objects.requireNonNull(
                    adjudicatedOrder,
                    "adjudicatedOrder");
        }

        /**
         * Returns whether DiploBN supplied an outcome annotation.
         */
        public boolean compared() {
            return sourceSuccessful != null;
        }

        /**
         * Returns whether JReferee's verdict matches the DiploBN outcome.
         *
         * <p>Returns false when DiploBN did not supply an outcome annotation;
         * callers may use {@link #compared()} to distinguish an unavailable
         * comparison from a genuine mismatch.</p>
         */
        public boolean matches() {
            return compared()
                    && adjudicatedOrder.verdict
                    == sourceSuccessful;
        }

    }

}