package parsing.diplobn;

import adjudication.Order;
import adjudication.util.Orders;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Compares DiploBN's recorded movement-order results with verdicts produced by
 * JReferee after independently adjudicating the imported movement submissions.
 *
 * <p>This comparison concerns movement orders only. DiploBN retreat and winter
 * adjustment data require their respective JReferee phase processors and a
 * chronological replay context.</p>
 *
 * <p>DiploBN supports split-coast locations, but an imported game can contain
 * a generic Stp, Spa, or Bul location where a fleet order requires a specific
 * coast. When that missing coast information could explain a disagreement, the
 * comparison reports the order as coast-ambiguous rather than a definitive
 * mismatch.</p>
 *
 * <p>DiploBN can also report an uncut support as successful even when it does
 * not correspond to the supported unit's submitted order. JReferee instead
 * records whether the support applies to a corresponding order. Those result
 * annotation differences are reported as ineffective supports rather than
 * definitive mismatches.</p>
 */
public class DiploBNAdjudicationComparator {


    private final DiploBNPhase phase;
    private final List<Entry> entries;


    private DiploBNAdjudicationComparator(
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
    public static DiploBNAdjudicationComparator compare(
            DiploBNPhase phase
    ) {

        Objects.requireNonNull(phase, "phase");

        DiploBNAdjudicator adjudicator =
                new DiploBNAdjudicator(phase);

        adjudicator.judge();

        return compare(
                phase,
                adjudicator.getOrders());

    }

    /**
     * Compares already-adjudicated movement orders against the historical
     * DiploBN result annotations held by one imported phase.
     */
    public static DiploBNAdjudicationComparator compare(
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(
                adjudicatedOrders,
                "adjudicatedOrders");

        List<Order> copiedOrders = List.copyOf(adjudicatedOrders);

        List<Entry> entries = new ArrayList<>();

        for (Order order : copiedOrders) {

            DiploBNOrderResolution sourceResolution =
                    locateResolution(order, phase.resolutions());

            Boolean sourceSuccessful = sourceResolution == null
                    ? null
                    : sourceResolution.successful();

            Difference difference = differenceOf(
                    order,
                    sourceSuccessful,
                    phase,
                    copiedOrders);

            entries.add(
                    new Entry(
                            order,
                            sourceSuccessful,
                            sourceResolution == null
                                    ? null
                                    : sourceResolution.reason(),
                            difference
                    ));

        }

        return new DiploBNAdjudicationComparator(
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
            if (entry.compared())
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
     * Number of disagreements for which generic split-coast information in the
     * DiploBN source could explain JReferee's different verdict.
     */
    public int coastAmbiguousCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.coastAmbiguous())
                count++;
        }

        return count;

    }

    /**
     * Number of support-result disagreements where DiploBN considers an uncut
     * but non-corresponding support successful and JReferee does not.
     */
    public int ineffectiveSupportCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.ineffectiveSupport())
                count++;
        }

        return count;

    }

    /**
     * Number of annotated source orders whose source result and JReferee
     * verdict differ without an identified compatibility explanation.
     */
    public int mismatchingCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.mismatches())
                count++;
        }

        return count;

    }

    /**
     * Returns whether every available DiploBN result annotation agrees with
     * JReferee's independently adjudicated verdict.
     *
     * <p>Compatibility differences cause this method to return false because
     * they do not establish an exact source/result match.</p>
     */
    public boolean matchesCompletely() {
        return matchingCount() == comparedCount();
    }


    private static Difference differenceOf(
            Order order,
            Boolean sourceSuccessful,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        if (sourceSuccessful == null)
            return Difference.NONE;

        if (order.verdict == sourceSuccessful)
            return Difference.NONE;

        if (coastInformationMissing(
                order,
                phase,
                adjudicatedOrders))
            return Difference.COAST_AMBIGUOUS;

        if (ineffectiveSupport(
                order,
                sourceSuccessful,
                adjudicatedOrders))
            return Difference.INEFFECTIVE_SUPPORT;

        return Difference.DEFINITE_MISMATCH;

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

    private static boolean coastInformationMissing(
            Order order,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        if (order.unitType == UnitType.FLEET
                && unspecifiedSplitCoast(order.pos0))
            return true;

        if (order.orderType == OrderType.MOVE) {

            if (order.unitType == UnitType.FLEET
                    && unspecifiedSplitCoast(order.pos1))
                return true;

            return supportedByCoastAmbiguousSupport(
                    order,
                    phase,
                    adjudicatedOrders);
        }

        if (order.orderType != OrderType.SUPPORT)
            return false;

        UnitId supportedUnit = unitAt(
                order.pos1,
                phase);

        if (supportedUnit == null)
            return false;

        if (supportedUnit.unitType() != UnitType.FLEET)
            return false;

        if (unspecifiedSplitCoast(order.pos1))
            return true;

        return order.pos2 != null
                && unspecifiedSplitCoast(order.pos2);

    }

    private static boolean supportedByCoastAmbiguousSupport(
            Order moveOrder,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        for (Order supportOrder : adjudicatedOrders) {

            if (supportOrder.orderType != OrderType.SUPPORT)
                continue;

            if (supportOrder.pos2 == null)
                continue;

            if (!Province.equalsIgnoreCoast(
                    supportOrder.pos1,
                    moveOrder.pos0))
                continue;

            if (!Province.equalsIgnoreCoast(
                    supportOrder.pos2,
                    moveOrder.pos1))
                continue;

            if (coastInformationMissing(
                    supportOrder,
                    phase,
                    List.of()))
                return true;

        }

        return false;

    }

    private static boolean ineffectiveSupport(
            Order supportOrder,
            Boolean sourceSuccessful,
            Collection<Order> adjudicatedOrders
    ) {

        if (!sourceSuccessful)
            return false;

        if (supportOrder.verdict)
            return false;

        if (supportOrder.orderType != OrderType.SUPPORT)
            return false;

        return Orders.locateCorresponding(
                supportOrder,
                adjudicatedOrders) == null;

    }

    private static UnitId unitAt(
            Province province,
            DiploBNPhase phase
    ) {

        for (Map.Entry<UnitId, Province> entry :
                phase.board().locations().entrySet()) {

            if (Province.equalsIgnoreCoast(
                    entry.getValue(),
                    province))
                return entry.getKey();

        }

        return null;

    }

    private static boolean unspecifiedSplitCoast(
            Province province
    ) {

        return province == Province.Stp
                || province == Province.Spa
                || province == Province.Bul;

    }


    /**
     * Classification for one source/local adjudication disagreement.
     */
    public enum Difference {

        NONE,
        COAST_AMBIGUOUS,
        INEFFECTIVE_SUPPORT,
        DEFINITE_MISMATCH

    }

    /**
     * Source and independently adjudicated outcome for one submitted movement
     * order.
     */
    public record Entry(
            Order adjudicatedOrder,
            Boolean sourceSuccessful,
            String sourceReason,
            Difference difference
    ) {

        public Entry {
            Objects.requireNonNull(
                    adjudicatedOrder,
                    "adjudicatedOrder");

            Objects.requireNonNull(
                    difference,
                    "difference");
        }

        /**
         * Returns whether DiploBN supplied an outcome annotation.
         */
        public boolean compared() {
            return sourceSuccessful != null;
        }

        /**
         * Returns whether JReferee's verdict equals DiploBN's recorded result.
         */
        public boolean matches() {
            return difference == Difference.NONE
                    && compared();
        }

        /**
         * Returns whether generic Stp, Spa, or Bul source data could explain
         * an otherwise differing JReferee verdict.
         */
        public boolean coastAmbiguous() {
            return difference == Difference.COAST_AMBIGUOUS;
        }

        /**
         * Returns whether DiploBN marked an uncut but non-corresponding support
         * successful while JReferee did not.
         */
        public boolean ineffectiveSupport() {
            return difference == Difference.INEFFECTIVE_SUPPORT;
        }

        /**
         * Returns whether the source and local verdicts differ without a known
         * coast-information or support-annotation explanation.
         */
        public boolean mismatches() {
            return difference == Difference.DEFINITE_MISMATCH;
        }

    }

}