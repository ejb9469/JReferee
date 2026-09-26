package parsing.diplobn;

import adjudication.Order;
import adjudication.util.Orders;
import domain.*;
import phase.UnitId;
import phase.UnitLookup;

import java.util.*;


/**
 * Compares recorded movement-order outcomes with independent adjudication.
 *
 * <p>Compatibility differences retain both verdicts, do not count as exact
 * matches, and never modify the underlying adjudication result.</p>
 */
public class DiploBNAdjudicationComparator {


    private final DiploBNPhase phase;
    private final List<Entry> entries;


    private DiploBNAdjudicationComparator(DiploBNPhase phase, List<Entry> entries) {

        this.phase = Objects.requireNonNull(phase, "phase");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));

    }


    // Comparison entry points \\

    public static DiploBNAdjudicationComparator compare(DiploBNPhase phase) {

        Objects.requireNonNull(phase, "phase");

        DiploBNAdjudicator adjudicator = new DiploBNAdjudicator(phase);
        adjudicator.judge();

        return compare(phase, adjudicator.getOrders());

    }

    public static DiploBNAdjudicationComparator compare(
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(adjudicatedOrders, "adjudicatedOrders");

        List<Order> copiedOrders = List.copyOf(adjudicatedOrders);

        // Null disables the conservative ineffective-convoy exemption.
        List<Order> submittedOrders =
                completeUntransformedSubmissions(phase, copiedOrders);

        List<Entry> entries = new ArrayList<>();

        for (Order order : copiedOrders) {

            DiploBNOrderResolution sourceResolution =
                    locateResolution(order, phase.resolutions());

            Boolean sourceSuccessful =
                    sourceResolution == null ? null : sourceResolution.successful();

            Difference difference = differenceOf(
                    order, sourceSuccessful, phase, copiedOrders, submittedOrders);

            entries.add(new Entry(
                    order,
                    sourceSuccessful,
                    sourceResolution == null ? null : sourceResolution.reason(),
                    difference));

        }

        return new DiploBNAdjudicationComparator(phase, entries);

    }


    // Accessors and counts \\

    public DiploBNPhase phase() {
        return phase;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int comparedCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.compared())
                count++;

        return count;

    }

    public int matchingCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.matches())
                count++;

        return count;

    }

    public int coastAmbiguousCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.coastAmbiguous())
                count++;

        return count;

    }

    public int ineffectiveSupportCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.ineffectiveSupport())
                count++;

        return count;

    }

    public int ineffectiveConvoyCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.ineffectiveConvoy())
                count++;

        return count;

    }

    public int compatibilityDifferenceCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.compatibilityDifference())
                count++;

        return count;

    }

    public int mismatchingCount() {

        int count = 0;

        for (Entry entry : entries)
            if (entry.mismatches())
                count++;

        return count;

    }

    /**
     * Accepts recognized compatibility differences, not just exact matches.
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

        if (sourceSuccessful == null || order.verdict == sourceSuccessful)
            return Difference.NONE;

        // Accepted annotation difference under the current comparison policy.
        if (sourceAcceptedSeaDestinationConvoy(order, sourceSuccessful))
            return Difference.INVALID_CONVOY_DESTINATION;

        if (coastInformationMissing(order, phase, adjudicatedOrders))
            return Difference.COAST_AMBIGUOUS;

        if (ineffectiveSupport(order, sourceSuccessful, adjudicatedOrders))
            return Difference.INEFFECTIVE_SUPPORT;

        if (ineffectiveConvoy(order, sourceSuccessful, adjudicatedOrders, submittedOrders))
            return Difference.INEFFECTIVE_CONVOY;

        return Difference.DEFINITE_MISMATCH;

    }

    private static DiploBNOrderResolution locateResolution(
            Order order,
            List<DiploBNOrderResolution> resolutions
    ) {

        for (DiploBNOrderResolution resolution : resolutions) {

            if (resolution.retreatOrder() || resolution.nation() != order.owner)
                continue;

            if (Province.equalsIgnoreCoast(resolution.issuingProvince(), order.pos0))
                return resolution;

        }

        return null;

    }


    // Coast ambiguity \\

    private static boolean coastInformationMissing(
            Order order,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        if (order.unitType == UnitType.FLEET && unspecifiedSplitCoast(order.pos0))
            return true;

        if (order.orderType == OrderType.MOVE) {

            if (order.unitType == UnitType.FLEET && unspecifiedSplitCoast(order.pos1))
                return true;

            return supportedByCoastAmbiguousSupport(order, phase, adjudicatedOrders);

        }

        if (order.orderType != OrderType.SUPPORT)
            return false;

        UnitId supportedUnit = UnitLookup.firstAt(phase.board().locations(), order.pos1);

        if (supportedUnit == null || supportedUnit.unitType() != UnitType.FLEET)
            return false;

        if (unspecifiedSplitCoast(order.pos1))
            return true;

        return order.pos2 != null && unspecifiedSplitCoast(order.pos2);

    }

    private static boolean supportedByCoastAmbiguousSupport(
            Order moveOrder,
            DiploBNPhase phase,
            Collection<Order> adjudicatedOrders
    ) {

        for (Order supportOrder : adjudicatedOrders) {

            if (supportOrder.orderType != OrderType.SUPPORT || supportOrder.pos2 == null)
                continue;

            if (!Province.equalsIgnoreCoast(supportOrder.pos1, moveOrder.pos0)
                    || !Province.equalsIgnoreCoast(supportOrder.pos2, moveOrder.pos1))
                continue;

            if (coastInformationMissing(supportOrder, phase, List.of()))
                return true;

        }

        return false;

    }

    private static boolean unspecifiedSplitCoast(Province province) {
        return province == Province.Stp || province == Province.Spa || province == Province.Bul;
    }


    // Ineffective support \\

    private static boolean ineffectiveSupport(
            Order supportOrder,
            Boolean sourceSuccessful,
            Collection<Order> adjudicatedOrders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || supportOrder.verdict
                || supportOrder.orderType != OrderType.SUPPORT)
            return false;

        return Orders.locateCorresponding(supportOrder, adjudicatedOrders) == null;

    }


    // Ineffective convoy \\

    private static boolean ineffectiveConvoy(
            Order convoyOrder,
            Boolean sourceSuccessful,
            Collection<Order> adjudicatedOrders,
            List<Order> submittedOrders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || convoyOrder.verdict
                || convoyOrder.orderType != OrderType.CONVOY)
            return false;

        if (submittedOrders == null)
            return false;

        Order submittedConvoy =
                Orders.locateUnitAtPosition(convoyOrder.pos0, submittedOrders);

        if (submittedConvoy == null || !submittedConvoy.equals(convoyOrder))
            return false;

        if (!Orders.orderIsValid(submittedConvoy))
            return false;

        if (Province.canonical(submittedConvoy.pos1).geography != Geography.COASTAL
                || Province.canonical(submittedConvoy.pos2).geography != Geography.COASTAL)
            return false;

        Order passenger =
                Orders.locateUnitAtPosition(submittedConvoy.pos1, submittedOrders);

        if (passenger == null || passenger.unitType != UnitType.ARMY)
            return false;

        // Use original submissions, not potentially rewritten result orders.
        for (Order submitted : submittedOrders) {

            if (submitted.orderType != OrderType.MOVE)
                continue;

            if (Province.equalsIgnoreCoast(submitted.pos0, submittedConvoy.pos1)
                    && Province.equalsIgnoreCoast(submitted.pos1, submittedConvoy.pos2))
                return false;

        }

        // A successful enemy move into the stationary fleet's province dislodges it.
        for (Order adjudicated : adjudicatedOrders) {

            if (adjudicated.orderType != OrderType.MOVE
                    || adjudicated.owner == convoyOrder.owner)
                continue;

            if (adjudicated.verdict
                    && Province.equalsIgnoreCoast(adjudicated.pos1, convoyOrder.pos0))
                return false;

        }

        return true;

    }

    /**
     * Returns original submissions only when results cover the complete board,
     * are fully resolved, and contain neither transformations nor snapshots.
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

            if (currentLocation == null || !submittedUnits.add(submitted.unit()))
                return null;

            Order original = new Order(
                    submitted.owner(),
                    submitted.unitType(),
                    currentLocation,
                    submitted.type(),
                    submitted.target(),
                    submitted.auxiliaryTarget());

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
                    || adjudicated.getSnapshot() != null
                    || adjudicated.pos0 == null)
                return null;

            Province position = Province.canonical(adjudicated.pos0);

            if (!adjudicatedPositions.add(position))
                return null;

            Order submitted = submittedByPosition.get(position);

            // Order.equals compares submitted fields, ignoring resolution metadata.
            if (submitted == null || !submitted.equals(adjudicated))
                return null;

        }

        if (!adjudicatedPositions.equals(submittedByPosition.keySet()))
            return null;

        return List.copyOf(submittedByPosition.values());

    }


    // Invalid convoy destination annotation \\

    /**
     * Accepted source-success annotation for a failed convoy into a sea province.
     * Does not change legality or establish the fleet's survival.
     */
    private static boolean sourceAcceptedSeaDestinationConvoy(
            Order order,
            Boolean sourceSuccessful
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || order.verdict
                || order.orderType != OrderType.CONVOY)
            return false;

        if (!order.resolved || order.visited || order.getSnapshot() != null)
            return false;

        return order.pos2 != null && order.pos2.geography == Geography.WATER;

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
            return difference == Difference.NONE && compared();
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