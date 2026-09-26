package parsing.diplobn;

import adjudication.Order;
import adjudication.util.Orders;
import domain.*;
import phase.UnitId;

import java.util.*;


/**
 * Compares DiploBN's recorded movement-order results with verdicts produced by
 * JReferee after independently adjudicating the imported movement submissions.
 *
 * <p>This comparison concerns movement orders only. DiploBN retreat and winter
 * adjustment data require their respective JReferee phase processors and a
 * chronological replay context.</p>
 *
 * <p>Missing split-coast information and recognized support/convoy annotation
 * differences are reported separately from definite mismatches. Compatibility
 * differences do not count as exact matches and never change the underlying
 * adjudication verdicts.</p>
 */
public class DiploBNAdjudicationComparator {


    // Core state \\

    private final DiploBNPhase phase;
    private final List<Entry> entries;


    // Constructors \\

    private DiploBNAdjudicationComparator(
            DiploBNPhase phase,
            List<Entry> entries
    ) {

        this.phase = Objects.requireNonNull(phase, "phase");

        this.entries = List.copyOf(
                Objects.requireNonNull(entries, "entries"));

    }


    // Comparison entry points \\

    /**
     * Independently adjudicates a DiploBN phase and compares each translated
     * movement order against its historical result annotation.
     *
     * <p>Orders without a source result annotation remain in the comparison,
     * but do not count as matches, compatibility differences, or mismatches.</p>
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
        Objects.requireNonNull(adjudicatedOrders, "adjudicatedOrders");

        List<Order> copiedOrders = List.copyOf(adjudicatedOrders);

        /*
         * Reconstruct the original submissions once for the convoy checks.
         *
         * A null result disables the ineffective-convoy exemption:
         * incomplete, unresolved, or transformed results are not sufficient
         * evidence for inferring a convoy fleet's survival.
         */
        List<Order> submittedOrders =
                completeUntransformedSubmissions(phase, copiedOrders);

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
                    copiedOrders,
                    submittedOrders
            );

            entries.add(
                    new Entry(
                            order,
                            sourceSuccessful,
                            sourceResolution == null
                                    ? null
                                    : sourceResolution.reason(),
                            difference
                    )
            );

        }

        return new DiploBNAdjudicationComparator(
                phase,
                entries);

    }


    // Public accessors \\

    public DiploBNPhase phase() {
        return phase;
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * Number of orders with a DiploBN result annotation.
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
     * Number of annotated orders whose source and JReferee verdicts agree.
     */
    public int matchingCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.matches())
                count++;
        }

        return count;

    }

    public int coastAmbiguousCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.coastAmbiguous())
                count++;
        }

        return count;

    }

    public int ineffectiveSupportCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.ineffectiveSupport())
                count++;
        }

        return count;

    }

    public int ineffectiveConvoyCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.ineffectiveConvoy())
                count++;
        }

        return count;

    }

    /**
     * Total number of recognized compatibility differences.
     *
     * <p>Compatibility differences remain separate from exact matches.</p>
     */
    public int compatibilityDifferenceCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.compatibilityDifference())
                count++;
        }

        return count;

    }

    public int mismatchingCount() {

        int count = 0;

        for (Entry entry : entries) {
            if (entry.mismatches())
                count++;
        }

        return count;

    }

    /**
     * Returns true when no definite mismatch remains.
     *
     * <p>This accepts recognized compatibility differences; it does not mean
     * that every order is an exact match or has a source result annotation.</p>
     */
    public boolean matchesCompletely() {
        return mismatchingCount() == 0;
    }

    public boolean hasDefiniteMismatch() {
        return mismatchingCount() > 0;
    }


    // Difference classification \\

    private static Difference differenceOf(
            Order order,
            Boolean sourceSuccessful,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders,
            List<Order> submittedOrders
    ) {

        if (sourceSuccessful == null)
            return Difference.NONE;

        if (order.verdict == sourceSuccessful)
            return Difference.NONE;

        /*
         * An explicit sea destination is not missing coast information and
         * is not the same as a valid but unused convoy.
         *
         * Keep this disagreement in the definite-mismatch total while
         * identifying its specific cause.
         */
        if (sourceAcceptedSeaDestinationConvoy(order, sourceSuccessful))
            return Difference.INVALID_CONVOY_DESTINATION;

        if (coastInformationMissing(order, phase, adjudicatedOrders))
            return Difference.COAST_AMBIGUOUS;

        if (ineffectiveSupport(order, sourceSuccessful, adjudicatedOrders))
            return Difference.INEFFECTIVE_SUPPORT;

        if (ineffectiveConvoy(
                order,
                sourceSuccessful,
                adjudicatedOrders,
                submittedOrders))
            return Difference.INEFFECTIVE_CONVOY;

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


    // Coast-ambiguity helpers \\

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

        UnitId supportedUnit = unitAt(order.pos1, phase);

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

    private static boolean unspecifiedSplitCoast(Province province) {

        return province == Province.Stp
                || province == Province.Spa
                || province == Province.Bul;

    }


    // Ineffective-support helpers \\

    /**
     * Preserves the existing ineffective-support classification.
     */
    private static boolean ineffectiveSupport(
            Order supportOrder,
            Boolean sourceSuccessful,
            Collection<Order> adjudicatedOrders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful))
            return false;

        if (supportOrder.verdict)
            return false;

        if (supportOrder.orderType != OrderType.SUPPORT)
            return false;

        return Orders.locateCorresponding(
                supportOrder,
                adjudicatedOrders) == null;

    }


    // Ineffective-convoy helpers \\

    /**
     * Recognizes a valid, non-dislodged convoy fleet whose specified army move
     * was not submitted, but whose source annotation nevertheless says success.
     *
     * <p>This is an annotation classification only. The convoy's JReferee
     * verdict remains false.</p>
     */
    private static boolean ineffectiveConvoy(
            Order convoyOrder,
            Boolean sourceSuccessful,
            Collection<Order> adjudicatedOrders,
            List<Order> submittedOrders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || convoyOrder.verdict)
            return false;

        if (convoyOrder.orderType != OrderType.CONVOY)
            return false;

        // No exemption without complete, resolved, untransformed evidence.
        if (submittedOrders == null)
            return false;

        Order submittedConvoy =
                Orders.locateUnitAtPosition(
                        convoyOrder.pos0,
                        submittedOrders);

        if (submittedConvoy == null
                || !submittedConvoy.equals(convoyOrder))
            return false;

        if (!Orders.orderIsValid(submittedConvoy))
            return false;

        /*
         * Orders.orderIsValid(...) checks the fleet's type, its sea location,
         * and distinct non-null endpoints. Be stricter for this exemption:
         * the proposed army journey must also have coastal endpoints.
         */
        if (Province.canonical(submittedConvoy.pos1).geography
                != Geography.COASTAL)
            return false;

        if (Province.canonical(submittedConvoy.pos2).geography
                != Geography.COASTAL)
            return false;

        Order passenger =
                Orders.locateUnitAtPosition(
                        submittedConvoy.pos1,
                        submittedOrders);

        if (passenger == null
                || passenger.unitType != UnitType.ARMY)
            return false;

        /*
         * Search original submissions rather than mutable adjudicated orders.
         * Compare provinces ignoring coasts so a coast-notation discrepancy
         * cannot masquerade as an absent corresponding move.
         */
        for (Order submitted : submittedOrders) {

            if (submitted.orderType != OrderType.MOVE)
                continue;

            if (Province.equalsIgnoreCoast(
                    submitted.pos0,
                    submittedConvoy.pos1)
                    && Province.equalsIgnoreCoast(
                    submitted.pos1,
                    submittedConvoy.pos2))
                return false;

        }

        /*
         * A convoy fleet remains stationary. A successful enemy move into its
         * province indicates dislodgement, which must remain a mismatch.
         *
         * Resolution completeness was checked before entering this helper.
         */
        for (Order adjudicated : adjudicatedOrders) {

            if (adjudicated.orderType != OrderType.MOVE)
                continue;

            if (adjudicated.owner == convoyOrder.owner)
                continue;

            if (adjudicated.verdict
                    && Province.equalsIgnoreCoast(
                    adjudicated.pos1,
                    convoyOrder.pos0))
                return false;

        }

        return true;

    }

    /**
     * Reconstructs original movement submissions using current board locations.
     *
     * <p>Returns null when the input is not sufficient for the conservative
     * ineffective-convoy exemption. In particular, every board unit must have
     * exactly one submitted order and one resolved, unchanged result.</p>
     *
     * <p>Any snapshot disables the exemption for this phase, even if the
     * current fields happen to equal the original fields. This avoids treating
     * a transformed convoy-paradox result as a simple annotation difference.</p>
     */
    private static List<Order> completeUntransformedSubmissions(
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        Map<UnitId, Province> locations = phase.board().locations();

        if (phase.movementOrders().size() != locations.size()
                || adjudicatedOrders.size() != locations.size())
            return null;

        Map<Province, Order> submittedByPosition = new LinkedHashMap<>();
        Set<UnitId> submittedUnits = new HashSet<>();

        for (phase.Order submitted : phase.movementOrders()) {

            Province currentLocation = locations.get(submitted.unit());

            if (currentLocation == null)
                return null;

            if (!submittedUnits.add(submitted.unit()))
                return null;

            Order original = new Order(
                    submitted.owner(),
                    submitted.unitType(),
                    currentLocation,
                    submitted.type(),
                    submitted.target(),
                    submitted.auxiliaryTarget()
            );

            Province position = Province.canonical(currentLocation);

            if (submittedByPosition.putIfAbsent(position, original) != null)
                return null;

        }

        if (!submittedUnits.equals(locations.keySet()))
            return null;

        Set<Province> adjudicatedPositions = new HashSet<>();

        for (Order adjudicated : adjudicatedOrders) {

            if (!adjudicated.resolved
                    || adjudicated.visited
                    || adjudicated.getSnapshot() != null)
                return null;

            if (adjudicated.pos0 == null)
                return null;

            Province position = Province.canonical(adjudicated.pos0);

            if (!adjudicatedPositions.add(position))
                return null;

            Order submitted = submittedByPosition.get(position);

            /*
             * Order.equals(...) compares all submitted-order fields while
             * ignoring resolution metadata. Do not use originalOf(...) here:
             * silently restoring a snapshot would hide a transformation.
             */
            if (submitted == null
                    || !submitted.equals(adjudicated))
                return null;

        }

        if (!adjudicatedPositions.equals(submittedByPosition.keySet()))
            return null;

        return List.copyOf(submittedByPosition.values());

    }


    // Board lookup helpers \\

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


    /**
     * Identifies a source-success annotation for a failed convoy whose
     * explicitly specified army destination is a sea province.
     *
     * <p>This remains a definite mismatch. The category explains the observed
     * disagreement; it does not establish that the source's annotation is
     * harmless or that the convoy fleet survived.</p>
     */
    private static boolean sourceAcceptedSeaDestinationConvoy(
            Order order,
            Boolean sourceSuccessful
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || order.verdict)
            return false;

        if (order.orderType != OrderType.CONVOY)
            return false;

        /*
         * Do not diagnose an original submission from an unresolved or
         * transformed work order.
         */
        if (!order.resolved
                || order.visited
                || order.getSnapshot() != null)
            return false;

        if (order.pos2 == null)
            return false;

        return order.pos2.geography == Geography.WATER;

    }


    // Result types \\

    public enum Difference {

        NONE,
        COAST_AMBIGUOUS,
        INEFFECTIVE_SUPPORT,
        INEFFECTIVE_CONVOY,
        INVALID_CONVOY_DESTINATION,
        DEFINITE_MISMATCH

    }

    /**
     * Source and independently adjudicated outcome for one submitted order.
     *
     * <p>Compatibility differences preserve the differing verdicts but are
     * accepted by the comparison policy. They do not count as exact matches.</p>
     */
    public record Entry(
            Order adjudicatedOrder,
            Boolean sourceSuccessful,
            String sourceReason,
            Difference difference
    ) {

        public Entry {

            Objects.requireNonNull(adjudicatedOrder, "adjudicatedOrder");
            Objects.requireNonNull(difference, "difference");

        }

        public boolean compared() {
            return sourceSuccessful != null;
        }

        public boolean matches() {
            return difference == Difference.NONE
                    && compared();
        }

        public boolean coastAmbiguous() {
            return difference == Difference.COAST_AMBIGUOUS;
        }

        public boolean ineffectiveSupport() {
            return difference == Difference.INEFFECTIVE_SUPPORT;
        }

        public boolean ineffectiveConvoy() {
            return difference == Difference.INEFFECTIVE_CONVOY;
        }

        public boolean invalidConvoyDestination() {
            return difference == Difference.INVALID_CONVOY_DESTINATION;
        }

        public boolean compatibilityDifference() {

            return coastAmbiguous()
                    || ineffectiveSupport()
                    || ineffectiveConvoy()
                    || invalidConvoyDestination();

        }

        public boolean mismatches() {
            return difference == Difference.DEFINITE_MISMATCH;
        }

    }


}