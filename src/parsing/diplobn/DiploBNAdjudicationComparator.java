package parsing.diplobn;

import adjudication.Order;
import adjudication.util.Convoys;
import adjudication.util.Orders;
import domain.*;
import phase.UnitId;
import phase.UnitLookup;

import java.util.*;
import java.util.function.Predicate;


public class DiploBNAdjudicationComparator {


    private final DiploBNPhase phase;
    private final List<Entry> entries;


    private DiploBNAdjudicationComparator(DiploBNPhase phase, List<Entry> entries) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }


    // Comparison \\

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

        List<Order> orders = List.copyOf(adjudicatedOrders);
        List<Order> submissions = completeUntransformedSubmissions(phase, orders);
        Set<Province> invalidMoveHoldSupport =
                invalidMoveHoldSupportDifferences(phase, orders, submissions);
        List<Entry> entries = new ArrayList<>(orders.size());

        for (Order order : orders) {

            DiploBNOrderResolution source =
                    locateResolution(order, phase.resolutions());

            Boolean sourceSuccessful = source == null
                    ? null
                    : source.successful();

            entries.add(new Entry(
                    order,
                    sourceSuccessful,
                    source == null ? null : source.reason(),
                    differenceOf(order, sourceSuccessful, phase, orders, submissions,
                            invalidMoveHoldSupport)));

        }

        return new DiploBNAdjudicationComparator(phase, entries);

    }

    private static Difference differenceOf(
            Order order,
            Boolean sourceSuccessful,
            DiploBNPhase phase,
            Collection<Order> orders,
            List<Order> submissions,
            Set<Province> invalidMoveHoldSupport
    ) {

        if (sourceSuccessful == null || order.verdict == sourceSuccessful)
            return Difference.NONE;

        if (invalidMoveHoldSupport.contains(Province.canonical(order.pos0)))
            return Difference.INVALID_MOVE_HOLD_SUPPORT;

        if (sourceAcceptedInvalidDestinationConvoy(order, sourceSuccessful))
            return Difference.INVALID_CONVOY_DESTINATION;

        if (coastInformationMissing(order, phase, orders))
            return Difference.COAST_AMBIGUOUS;

        if (supportForInvalidMove(order, sourceSuccessful, orders))
            return Difference.SUPPORT_FOR_INVALID_MOVE;

        if (supportToOwnProvince(order, sourceSuccessful, phase, orders))
            return Difference.SUPPORT_TO_OWN_PROVINCE;

        if (ineffectiveSupport(order, sourceSuccessful, orders))
            return Difference.INEFFECTIVE_SUPPORT;

        if (ineffectiveConvoy(order, sourceSuccessful, orders, submissions))
            return Difference.INEFFECTIVE_CONVOY;

        if (convoyForFailedMove(order, sourceSuccessful, phase, orders, submissions))
            return Difference.CONVOY_FOR_FAILED_MOVE;

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


    // Accessors and counts \\

    public DiploBNPhase phase() {
        return phase;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int comparedCount() {
        return count(Entry::compared);
    }

    public int matchingCount() {
        return count(Entry::matches);
    }

    public int coastAmbiguousCount() {
        return count(Entry::coastAmbiguous);
    }

    public int supportForInvalidMoveCount() {
        return count(Entry::supportForInvalidMove);
    }

    public int supportToOwnProvinceCount() {
        return count(Entry::supportToOwnProvince);
    }

    public int ineffectiveSupportCount() {
        return count(Entry::ineffectiveSupport);
    }

    public int ineffectiveConvoyCount() {
        return count(Entry::ineffectiveConvoy);
    }

    public int convoyForFailedMoveCount() {
        return count(Entry::convoyForFailedMove);
    }

    public int invalidMoveHoldSupportCount() {
        return count(Entry::invalidMoveHoldSupport);
    }

    public int rulesPolicyDifferenceCount() {
        return count(Entry::rulesPolicyDifference);
    }

    public int compatibilityDifferenceCount() {
        return count(Entry::compatibilityDifference);
    }

    public int mismatchingCount() {
        return count(Entry::mismatches);
    }

    public boolean matchesCompletely() {
        return mismatchingCount() == 0;
    }

    public boolean hasDefiniteMismatch() {
        return mismatchingCount() > 0;
    }

    private int count(Predicate<Entry> predicate) {

        int count = 0;

        for (Entry entry : entries)
            if (predicate.test(entry))
                count++;

        return count;

    }


    // Coast ambiguity \\

    private static boolean coastInformationMissing(
            Order order,
            DiploBNPhase phase,
            Collection<Order> orders
    ) {

        if (order.unitType == UnitType.FLEET && unspecifiedSplitCoast(order.pos0))
            return true;

        if (order.orderType == OrderType.MOVE) {

            if (order.unitType == UnitType.FLEET && unspecifiedSplitCoast(order.pos1))
                return true;

            return supportedByCoastAmbiguousSupport(order, phase, orders);

        }

        if (order.orderType != OrderType.SUPPORT)
            return false;

        UnitId supportedUnit =
                UnitLookup.firstAt(phase.board().locations(), order.pos1);

        if (supportedUnit == null || supportedUnit.unitType() != UnitType.FLEET)
            return false;

        return unspecifiedSplitCoast(order.pos1)
                || (order.pos2 != null && unspecifiedSplitCoast(order.pos2));

    }

    private static boolean supportedByCoastAmbiguousSupport(
            Order move,
            DiploBNPhase phase,
            Collection<Order> orders
    ) {

        for (Order support : orders) {

            if (support.orderType != OrderType.SUPPORT || support.pos2 == null)
                continue;

            if (!Province.equalsIgnoreCoast(support.pos1, move.pos0)
                    || !Province.equalsIgnoreCoast(support.pos2, move.pos1))
                continue;

            if (coastInformationMissing(support, phase, List.of()))
                return true;

        }

        return false;

    }

    private static boolean unspecifiedSplitCoast(Province province) {
        return province == Province.Stp
                || province == Province.Spa
                || province == Province.Bul;
    }

    private static boolean isCoastalProvince(Province province) {
        return province != null && province.hasCoast();
    }


    // Invalid-move hold-support policy \\

    private static Set<Province> invalidMoveHoldSupportDifferences(
            DiploBNPhase phase,
            List<Order> orders,
            List<Order> submissions
    ) {

        Set<Province> differences = new HashSet<>();

        if (submissions == null)
            return differences;

        for (Order defender : orders) {

            if (defender.orderType != OrderType.MOVE
                    || defender.pos0 == null
                    || defender.pos1 == null
                    || defender.pos2 != null
                    || defender.verdict)
                continue;

            DiploBNOrderResolution sourceDefender =
                    locateResolution(defender, phase.resolutions());

            if (sourceDefender == null
                    || !Boolean.FALSE.equals(sourceDefender.successful()))
                continue;

            boolean hasExcludedHoldSupport = false;
            boolean hasDislodgementDifference = false;

            for (Order order : orders) {

                DiploBNOrderResolution source =
                        locateResolution(order, phase.resolutions());

                if (source == null)
                    continue;

                if (order.orderType == OrderType.SUPPORT
                        && order.pos2 == null
                        && Province.equalsIgnoreCoast(order.pos1, defender.pos0)
                        && !order.verdict
                        && Boolean.TRUE.equals(source.successful())
                        && Orders.orderIsValid(order))
                    hasExcludedHoldSupport = true;

                if (order.orderType == OrderType.MOVE
                        && order.owner != defender.owner
                        && Province.equalsIgnoreCoast(order.pos1, defender.pos0)
                        && order.verdict
                        && Boolean.FALSE.equals(source.successful()))
                    hasDislodgementDifference = true;

            }

            if (!hasExcludedHoldSupport
                    || !hasDislodgementDifference
                    || !invalidMoveWithoutPossibleBoardRoute(defender, phase))
                continue;

            List<DiploBNOrder> sourceOrders = new ArrayList<>();

            for (phase.Order order : phase.movementOrders())
                sourceOrders.add(DiploBNOrder.from(order));

            DiploBNAdjudicationOrderTranslator translator =
                    new DiploBNAdjudicationOrderTranslator(true);

            DiploBNAdjudicator alternate = new DiploBNAdjudicator(
                    phase.board(),
                    sourceOrders,
                    (source, board) -> {

                        Order translated = translator.translate(source, board);

                        if (translated.owner == defender.owner
                                && Province.equalsIgnoreCoast(
                                translated.pos0, defender.pos0)) {
                            translated.orderType = OrderType.HOLD;
                            translated.pos1 = null;
                            translated.pos2 = null;
                        }

                        return translated;

                    });

            alternate.judge();

            differences.addAll(verifiedHoldSupportDifferences(
                    defender,
                    phase,
                    orders,
                    alternate.getOrders()));

        }

        return differences;

    }

    private static boolean invalidMoveWithoutPossibleBoardRoute(
            Order move,
            DiploBNPhase phase
    ) {

        if (!Orders.orderIsValid(move))
            return true;

        if (move.unitType != UnitType.ARMY
                || move.pos0.isAdjacentTo(move.pos1)
                || !isCoastalProvince(move.pos0)
                || !isCoastalProvince(move.pos1))
            return false;

        List<Order> possibleConvoys = new ArrayList<>();

        for (var entry : phase.board().locations().entrySet()) {

            if (entry.getKey().unitType() != UnitType.FLEET
                    || entry.getValue().geography != Geography.WATER)
                continue;

            possibleConvoys.add(new Order(
                    entry.getKey().owner(),
                    UnitType.FLEET,
                    entry.getValue(),
                    OrderType.CONVOY,
                    move.pos0,
                    move.pos1));

        }

        // Use every fleet already at sea, regardless of its submitted order.
        // Missing convoy orders alone must not establish an invalid-move policy.
        return Convoys.drawConvoyPath(move, possibleConvoys).isEmpty();

    }

    private static Set<Province> verifiedHoldSupportDifferences(
            Order defender,
            DiploBNPhase phase,
            List<Order> orders,
            Collection<Order> alternateOrders
    ) {

        if (alternateOrders.size() != orders.size())
            return Set.of();

        Map<Province, Order> alternateByOrigin = new HashMap<>();

        for (Order alternate : alternateOrders) {

            if (alternate.pos0 == null
                    || !alternate.resolved
                    || alternate.visited
                    || alternate.getSnapshot() != null
                    || alternateByOrigin.putIfAbsent(
                    Province.canonical(alternate.pos0), alternate) != null)
                return Set.of();

        }

        Set<Province> differences = new HashSet<>();
        boolean supportChanged = false;
        boolean attackChanged = false;

        for (Order original : orders) {

            Province origin = Province.canonical(original.pos0);
            Order alternate = alternateByOrigin.get(origin);

            if (alternate == null)
                return Set.of();

            if (original == defender) {

                Order expectedHold = new Order(
                        defender.owner,
                        defender.unitType,
                        defender.pos0,
                        OrderType.HOLD);

                if (!expectedHold.equals(alternate) || !alternate.verdict)
                    return Set.of();

                // The submitted MOVE remains unsuccessful in the real results.
                // The alternate HOLD verdict describes survival, not movement.
                continue;

            }

            if (!original.equals(alternate))
                return Set.of();

            DiploBNOrderResolution source =
                    locateResolution(original, phase.resolutions());

            // Conservatively require every other movement result to agree.
            // An unrelated unresolved movement discrepancy disables this tag.
            if (original.orderType == OrderType.MOVE
                    && (source == null
                    || source.successful() == null
                    || alternate.verdict != source.successful()))
                return Set.of();

            if (original.verdict == alternate.verdict)
                continue;

            if (source == null
                    || source.successful() == null
                    || alternate.verdict != source.successful())
                return Set.of();

            if (original.orderType == OrderType.SUPPORT
                    && original.pos2 == null
                    && Province.equalsIgnoreCoast(original.pos1, defender.pos0)
                    && !original.verdict
                    && alternate.verdict
                    && Orders.orderIsValid(original)) {

                supportChanged = true;

            } else if (original.orderType == OrderType.MOVE
                    && original.owner != defender.owner
                    && Province.equalsIgnoreCoast(original.pos1, defender.pos0)
                    && original.verdict
                    && !alternate.verdict) {

                attackChanged = true;

            } else {

                // Do not automatically exempt downstream or unrelated changes.
                return Set.of();

            }

            differences.add(origin);

        }

        return supportChanged && attackChanged
                ? differences
                : Set.of();

    }


    // Ineffective orders \\

    private static boolean supportForInvalidMove(
            Order support,
            Boolean sourceSuccessful,
            Collection<Order> orders
    ) {

        if (!Boolean.FALSE.equals(sourceSuccessful)
                || !support.verdict
                || support.orderType != OrderType.SUPPORT
                || support.pos0 == null
                || support.pos1 == null
                || support.pos2 == null)
            return false;

        if (!support.resolved || support.visited || support.getSnapshot() != null)
            return false;

        if (!Orders.orderIsValid(support))
            return false;

        Order move = Orders.locateCorresponding(support, orders);

        if (move == null
                || move.orderType != OrderType.MOVE
                || move.pos0 == null
                || move.pos1 == null
                || !move.resolved
                || move.visited
                || move.verdict
                || move.getSnapshot() != null)
            return false;

        return !Orders.orderIsValid(move);

    }

    private static boolean supportToOwnProvince(
            Order support,
            Boolean sourceSuccessful,
            DiploBNPhase phase,
            Collection<Order> orders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || support.verdict
                || support.orderType != OrderType.SUPPORT
                || support.pos0 == null
                || support.pos1 == null
                || support.pos2 == null)
            return false;

        if (!support.resolved || support.visited || support.getSnapshot() != null)
            return false;

        if (!Province.equalsIgnoreCoast(support.pos0, support.pos2))
            return false;

        Order move = Orders.locateCorresponding(support, orders);

        if (move == null
                || move.orderType != OrderType.MOVE
                || move.pos0 == null
                || move.pos1 == null
                || !move.resolved
                || move.visited
                || move.verdict
                || move.getSnapshot() != null)
            return false;

        DiploBNOrderResolution sourceMove =
                locateResolution(move, phase.resolutions());

        if (sourceMove == null || !Boolean.FALSE.equals(sourceMove.successful()))
            return false;

        for (Order incoming : orders) {

            if (incoming.orderType == OrderType.MOVE
                    && Province.equalsIgnoreCoast(incoming.pos1, support.pos0)
                    && (!incoming.resolved
                    || incoming.visited
                    || incoming.getSnapshot() != null))
                return false;

        }

        return !dislodgedByEnemy(support, orders);

    }

    private static boolean ineffectiveSupport(
            Order support,
            Boolean sourceSuccessful,
            Collection<Order> orders
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || support.verdict
                || support.orderType != OrderType.SUPPORT)
            return false;

        return Orders.locateCorresponding(support, orders) == null;

    }

    private static boolean ineffectiveConvoy(
            Order convoy,
            Boolean sourceSuccessful,
            Collection<Order> orders,
            List<Order> submissions
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || convoy.verdict
                || convoy.orderType != OrderType.CONVOY
                || submissions == null)
            return false;

        Order submitted =
                Orders.locateUnitAtPosition(convoy.pos0, submissions);

        if (submitted == null
                || !submitted.equals(convoy)
                || !Orders.orderIsValid(submitted))
            return false;

        if (!isCoastalProvince(submitted.pos2))
            return false;

        if (isCoastalProvince(submitted.pos1)) {

            Order passenger =
                    Orders.locateUnitAtPosition(submitted.pos1, submissions);

            if (passenger == null || passenger.unitType != UnitType.ARMY)
                return false;

            for (Order move : submissions) {

                if (move.orderType != OrderType.MOVE)
                    continue;

                if (Province.equalsIgnoreCoast(move.pos0, submitted.pos1)
                        && Province.equalsIgnoreCoast(move.pos1, submitted.pos2))
                    return false;

            }

        } else {

            if (submitted.unitType != UnitType.FLEET
                    || submitted.pos0 == null
                    || submitted.pos0.geography != Geography.WATER
                    || submitted.pos1 == null)
                return false;

            // Preserve the exact matching used by the noncoastal-origin policy.
            if (Orders.locateCorresponding(submitted, submissions) != null)
                return false;

        }

        return !dislodgedByEnemy(convoy, orders);

    }

    private static boolean convoyForFailedMove(
            Order convoy,
            Boolean sourceSuccessful,
            DiploBNPhase phase,
            Collection<Order> orders,
            List<Order> submissions
    ) {

        if (!Boolean.FALSE.equals(sourceSuccessful)
                || !convoy.verdict
                || convoy.orderType != OrderType.CONVOY
                || convoy.unitType != UnitType.FLEET
                || convoy.pos0 == null
                || convoy.pos0.geography != Geography.WATER
                || submissions == null)
            return false;

        if (!isCoastalProvince(convoy.pos1)
                || !isCoastalProvince(convoy.pos2)
                || !Orders.orderIsValid(convoy))
            return false;

        Order move = Orders.locateCorresponding(convoy, orders);

        if (move == null
                || move.orderType != OrderType.MOVE
                || move.unitType != UnitType.ARMY
                || move.pos0 == null
                || move.pos1 == null
                || move.verdict
                || !Orders.orderIsValid(move))
            return false;

        DiploBNOrderResolution sourceMove =
                locateResolution(move, phase.resolutions());

        if (sourceMove == null || !Boolean.FALSE.equals(sourceMove.successful()))
            return false;

        List<Order> available = new ArrayList<>();

        for (Order candidate : orders) {

            if (candidate.orderType != OrderType.CONVOY
                    || candidate.unitType != UnitType.FLEET
                    || candidate.pos0 == null
                    || candidate.pos0.geography != Geography.WATER
                    || candidate.pos1 != move.pos0
                    || candidate.pos2 != move.pos1
                    || !candidate.verdict
                    || !Orders.orderIsValid(candidate))
                continue;

            if (convoyFleetSurvivesInBothResults(candidate, phase, orders))
                available.add(candidate);

        }

        if (!available.contains(convoy))
            return false;

        for (Order first : available) {

            if (!first.pos0.isAdjacentTo(move.pos0))
                continue;

            if (convoyRouteThroughFleet(
                    move,
                    convoy,
                    first,
                    available,
                    new ArrayList<>()))
                return true;

        }

        return false;

    }

    private static boolean convoyFleetSurvivesInBothResults(
            Order convoy,
            DiploBNPhase phase,
            Collection<Order> orders
    ) {

        for (Order incoming : orders) {

            if (incoming.orderType != OrderType.MOVE
                    || !Province.equalsIgnoreCoast(incoming.pos1, convoy.pos0))
                continue;

            if (incoming.verdict)
                return false;

            DiploBNOrderResolution sourceIncoming =
                    locateResolution(incoming, phase.resolutions());

            if (sourceIncoming == null
                    || !Boolean.FALSE.equals(sourceIncoming.successful()))
                return false;

        }

        return true;

    }

    private static boolean convoyRouteThroughFleet(
            Order move,
            Order requiredFleet,
            Order current,
            List<Order> available,
            List<Order> path
    ) {

        if (path.contains(current))
            return false;

        path.add(current);

        if (path.contains(requiredFleet)
                && current.pos0.isAdjacentTo(move.pos1)
                && Convoys.convoyPathIsValid(move, path)) {
            path.remove(path.size() - 1);
            return true;
        }

        for (Order next : available) {

            if (path.contains(next)
                    || !current.pos0.isAdjacentTo(next.pos0))
                continue;

            if (convoyRouteThroughFleet(
                    move,
                    requiredFleet,
                    next,
                    available,
                    path)) {
                path.remove(path.size() - 1);
                return true;
            }

        }

        path.remove(path.size() - 1);
        return false;

    }

    private static boolean dislodgedByEnemy(
            Order stationaryOrder,
            Collection<Order> orders
    ) {

        for (Order move : orders) {

            if (move.orderType != OrderType.MOVE
                    || move.owner == stationaryOrder.owner)
                continue;

            if (move.verdict
                    && Province.equalsIgnoreCoast(move.pos1, stationaryOrder.pos0))
                return true;

        }

        return false;

    }


    // Submission safeguards \\

    // Null disables convoy and hold-policy exemptions for incomplete or transformed results.
    private static List<Order> completeUntransformedSubmissions(
            DiploBNPhase phase,
            Collection<Order> orders
    ) {

        Map<UnitId, Province> locations = phase.board().locations();

        if (phase.movementOrders().size() != locations.size()
                || orders.size() != locations.size())
            return null;

        Map<Province, Order> submissions = new LinkedHashMap<>();
        Set<UnitId> submittedUnits = new HashSet<>();

        for (phase.Order submitted : phase.movementOrders()) {

            Province location = locations.get(submitted.unit());

            if (location == null || !submittedUnits.add(submitted.unit()))
                return null;

            Order original = new Order(
                    submitted.owner(),
                    submitted.unitType(),
                    location,
                    submitted.orderType(),
                    submitted.target(),
                    submitted.auxiliaryTarget());

            if (submissions.putIfAbsent(Province.canonical(location), original) != null)
                return null;

        }

        if (!submittedUnits.equals(locations.keySet()))
            return null;

        Set<Province> adjudicatedPositions = new HashSet<>();

        for (Order order : orders) {

            if (!order.resolved
                    || order.visited
                    || order.getSnapshot() != null
                    || order.pos0 == null)
                return null;

            Province position = Province.canonical(order.pos0);

            if (!adjudicatedPositions.add(position))
                return null;

            Order submitted = submissions.get(position);

            if (submitted == null || !submitted.equals(order))
                return null;

        }

        if (!adjudicatedPositions.equals(submissions.keySet()))
            return null;

        return List.copyOf(submissions.values());

    }


    // Invalid convoy destination annotation \\

    // Annotation-only exemption; intentionally does not establish fleet survival.
    private static boolean sourceAcceptedInvalidDestinationConvoy(
            Order order,
            Boolean sourceSuccessful
    ) {

        if (!Boolean.TRUE.equals(sourceSuccessful)
                || order.verdict
                || order.orderType != OrderType.CONVOY
                || order.unitType != UnitType.FLEET)
            return false;

        if (!order.resolved || order.visited || order.getSnapshot() != null)
            return false;

        if (order.pos0 == null || order.pos0.geography != Geography.WATER)
            return false;

        return order.pos2 != null && !isCoastalProvince(order.pos2);

    }


    // Result types \\

    public enum Difference {

        NONE,
        COAST_AMBIGUOUS,
        SUPPORT_FOR_INVALID_MOVE,
        SUPPORT_TO_OWN_PROVINCE,
        INEFFECTIVE_SUPPORT,
        INEFFECTIVE_CONVOY,
        CONVOY_FOR_FAILED_MOVE,
        INVALID_CONVOY_DESTINATION,
        INVALID_MOVE_HOLD_SUPPORT,
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

        public boolean supportForInvalidMove() {
            return difference == Difference.SUPPORT_FOR_INVALID_MOVE;
        }

        public boolean supportToOwnProvince() {
            return difference == Difference.SUPPORT_TO_OWN_PROVINCE;
        }

        public boolean ineffectiveSupport() {
            return difference == Difference.INEFFECTIVE_SUPPORT;
        }

        public boolean ineffectiveConvoy() {
            return difference == Difference.INEFFECTIVE_CONVOY;
        }

        public boolean convoyForFailedMove() {
            return difference == Difference.CONVOY_FOR_FAILED_MOVE;
        }

        public boolean invalidConvoyDestination() {
            return difference == Difference.INVALID_CONVOY_DESTINATION;
        }

        public boolean invalidMoveHoldSupport() {
            return difference == Difference.INVALID_MOVE_HOLD_SUPPORT;
        }

        public boolean rulesPolicyDifference() {
            return invalidMoveHoldSupport();
        }

        public boolean compatibilityDifference() {
            return coastAmbiguous()
                    || supportForInvalidMove()
                    || supportToOwnProvince()
                    || ineffectiveSupport()
                    || ineffectiveConvoy()
                    || convoyForFailedMove()
                    || invalidConvoyDestination()
                    || rulesPolicyDifference();
        }

        public boolean mismatches() {
            return difference == Difference.DEFINITE_MISMATCH;
        }

    }


}