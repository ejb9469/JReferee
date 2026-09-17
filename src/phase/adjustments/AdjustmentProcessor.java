package phase.adjustments;

import domain.CoastType;
import domain.Geography;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.Processor;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Processes one winter adjustment phase.
 *
 * <p>The processor calculates balances once from the post-retreat board, then
 * applies submitted builds, waives, and voluntary disbands. It never derives
 * balance by mutating a shared per-nation counter while individual orders are
 * adjudicated.</p>
 */
public final class AdjustmentProcessor
        implements Processor<AdjustmentInput, AdjustmentResult> {

    private final BalanceCalculator balanceCalculator;
    private final AutoDisbandPolicy autoDisbandPolicy;

    public AdjustmentProcessor(
            BalanceCalculator balanceCalculator,
            AutoDisbandPolicy autoDisbandPolicy)
    {
        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator,
                "balanceCalculator");
        this.autoDisbandPolicy = Objects.requireNonNull(
                autoDisbandPolicy,
                "autoDisbandPolicy");
    }

    /**
     * Creates a processor that requires voluntary orders to satisfy every
     * mandatory disband until a civil-disorder policy is implemented.
     */
    public AdjustmentProcessor() {
        this(
                new BalanceCalculator(),
                AutoDisbandPolicy.failWhenRequired());
    }

    @Override
    public AdjustmentResult process(AdjustmentInput input) {
        Objects.requireNonNull(input, "input");

        Map<Nation, Balance> balances =
                balanceCalculator.calculate(input);

        /*
         * Begin with the complete post-retreat board. Disbands remove entries;
         * accepted builds add new, permanently distinct UnitIds.
         */
        Map<UnitId, Province> finalLocations = new LinkedHashMap<>(
                input.retreatResult().finalLocations());

        List<AdjustmentOrder> submittedOrders = input.submittedOrders();

        AdjustmentResult.Outcome.Status[] statuses =
                new AdjustmentResult.Outcome.Status[
                        submittedOrders.size()];

        markDuplicateBuildSites(submittedOrders, statuses);
        markDuplicateDisbands(submittedOrders, statuses);

        Map<Nation, Integer> remainingBuilds =
                allowanceMap(balances, true);

        Map<Nation, Integer> remainingDisbands =
                allowanceMap(balances, false);

        Set<UnitId> builtUnits = new LinkedHashSet<>();
        Set<UnitId> disbandedUnits = new LinkedHashSet<>();

        /*
         * Apply non-duplicate submitted orders in submission order.
         *
         * Valid adjustment orders consume a finite allowance. Once a nation's
         * allowance is exhausted, subsequent otherwise-valid orders are
         * rejected as excessive. This makes the behavior deterministic and
         * gives clients a straightforward way to order preferred adjustments.
         */
        for (int index = 0; index < submittedOrders.size(); index++) {

            if (statuses[index] != null) {
                continue;
            }

            AdjustmentOrder order = submittedOrders.get(index);

            if (order instanceof BuildOrder buildOrder) {
                statuses[index] = processBuild(
                        input,
                        buildOrder,
                        finalLocations,
                        remainingBuilds,
                        builtUnits);
                continue;
            }

            if (order instanceof WaiveOrder waiveOrder) {
                statuses[index] = processWaive(
                        waiveOrder,
                        remainingBuilds);
                continue;
            }

            if (order instanceof DisbandOrder disbandOrder) {
                statuses[index] = processDisband(
                        disbandOrder,
                        finalLocations,
                        remainingDisbands,
                        disbandedUnits);
                continue;
            }

            throw new IllegalArgumentException(
                    "Unsupported adjustment order type: "
                            + order.getClass().getName());

        }

        applyAutomaticDisbands(
                input,
                finalLocations,
                remainingDisbands,
                disbandedUnits);

        List<AdjustmentResult.Outcome> outcomes = resultOutcomes(submittedOrders, statuses);

        return new AdjustmentResult(
                input.retreatResult(),
                finalLocations,
                balances,
                outcomes,
                disbandedUnits,
                builtUnits);

    }

    // private (static) helper for `process(...)`
    private static List<AdjustmentResult.Outcome> resultOutcomes(
            List<AdjustmentOrder> submittedOrders,
            AdjustmentResult.Outcome.Status[] statuses) {

        List<AdjustmentResult.Outcome> outcomes =
                new ArrayList<>(submittedOrders.size());

        for (int index = 0; index < submittedOrders.size(); index++) {

            AdjustmentResult.Outcome.Status status =
                    statuses[index];

            if (status == null)
                throw new IllegalStateException(
                        "Adjustment order received no outcome: "
                                + submittedOrders.get(index));

            outcomes.add(
                    new AdjustmentResult.Outcome(
                            submittedOrders.get(index),
                            status));

        }

        return outcomes;

    }

    // private helper for `process(...)`
    private AdjustmentResult.Outcome.Status processBuild(
            AdjustmentInput input,
            BuildOrder order,
            Map<UnitId, Province> finalLocations,
            Map<Nation, Integer> remainingBuilds,
            Set<UnitId> builtUnits
    ) {

        Nation nation = order.nation();

        if (remainingBuilds.get(nation) <= 0)
            return AdjustmentResult.Outcome.Status.REJECTED_NO_BUILD_CAPACITY;

        AdjustmentResult.Outcome.Status locationStatus =
                validateBuildSite(
                        input,
                        order,
                        finalLocations);

        if (locationStatus != null)
            return locationStatus;

        UnitId builtUnit = UnitId.newlyBuilt(
                nation,
                order.unitType(),
                order.location());

        if (finalLocations.putIfAbsent(builtUnit, order.location()) != null)
            throw new IllegalStateException(
                    "Newly created UnitId already exists: " + builtUnit);

        builtUnits.add(builtUnit);

        remainingBuilds.put(nation,
                remainingBuilds.get(nation) - 1);

        return AdjustmentResult.Outcome.Status.ACCEPTED;

    }

    private AdjustmentResult.Outcome.Status validateBuildSite(
            AdjustmentInput input,
            BuildOrder order,
            Map<UnitId, Province> finalLocations
    ) {

        Province suppliedLocation = order.location();
        Province territory = canonicalProvince(suppliedLocation);

        if (territory.homeowner != order.nation())
            return AdjustmentResult.Outcome.Status.REJECTED_NOT_HOME_SUPPLY_CENTER;

        if (input.ownerOf(suppliedLocation) != order.nation())
            return AdjustmentResult.Outcome.Status.REJECTED_SUPPLY_CENTER_NOT_CONTROLLED;

        if (isOccupied(suppliedLocation, finalLocations))
            return AdjustmentResult.Outcome.Status.REJECTED_BUILD_LOCATION_OCCUPIED;

        if (!isLegalBuildUnitType(order.unitType(), suppliedLocation))
            return AdjustmentResult.Outcome.Status.REJECTED_ILLEGAL_UNIT_TYPE;

        return null;

    }

    private AdjustmentResult.Outcome.Status processWaive(
            WaiveOrder order,
            Map<Nation, Integer> remainingBuilds
    ) {

        Nation nation = order.nation();

        if (remainingBuilds.get(nation) <= 0)
            return AdjustmentResult.Outcome.Status.REJECTED_WAIVE_NOT_ALLOWED;

        remainingBuilds.put(
                nation,
                remainingBuilds.get(nation) - 1);

        return AdjustmentResult.Outcome.Status.ACCEPTED;

    }

    private AdjustmentResult.Outcome.Status processDisband(
            DisbandOrder order,
            Map<UnitId, Province> finalLocations,
            Map<Nation, Integer> remainingDisbands,
            Set<UnitId> disbandedUnits
    ) {

        Nation nation = order.nation();
        UnitId unit = order.unit();

        if (remainingDisbands.get(nation) <= 0)
            return AdjustmentResult.Outcome.Status.REJECTED_NO_DISBAND_REQUIREMENT;

        if (unit.owner() != nation)
            return AdjustmentResult.Outcome.Status.REJECTED_UNIT_NOT_OWNED;

        if (!finalLocations.containsKey(unit))
            return AdjustmentResult.Outcome.Status.REJECTED_UNIT_NOT_ON_BOARD;

        finalLocations.remove(unit);
        disbandedUnits.add(unit);

        remainingDisbands.put(nation,
                remainingDisbands.get(nation) - 1);

        return AdjustmentResult.Outcome.Status.ACCEPTED;

    }

    private void applyAutomaticDisbands(
            AdjustmentInput input,
            Map<UnitId, Province> finalLocations,
            Map<Nation, Integer> remainingDisbands,
            Set<UnitId> disbandedUnits
    ) {

        for (Nation nation : Nation.values()) {

            int numberRequired = remainingDisbands.get(nation);

            if (numberRequired == 0)
                continue;

            Collection<UnitId> selected =
                    autoDisbandPolicy.select(
                        input,
                        Map.copyOf(finalLocations),
                        nation,
                        numberRequired);

            if (selected == null)
                throw new IllegalStateException(
                        "AutoDisbandPolicy returned null for " + nation);

            Set<UnitId> uniqueSelections = new LinkedHashSet<>(selected);

            if (uniqueSelections.size() != numberRequired)
                throw new IllegalStateException(
                        "AutoDisbandPolicy must select exactly " + numberRequired
                                + " unique unit(s) for " + nation);

            for (UnitId unit : uniqueSelections) {
                if (unit.owner() != nation)
                    throw new IllegalStateException(
                            "AutoDisbandPolicy selected another nation's unit: " + unit);
                if (!finalLocations.containsKey(unit))
                    throw new IllegalStateException(
                            "AutoDisbandPolicy selected absent unit: " + unit);
                finalLocations.remove(unit);
                disbandedUnits.add(unit);
            }

            remainingDisbands.put(nation, 0);

        }

    }

    private void markDuplicateBuildSites(
            List<AdjustmentOrder> orders,
            AdjustmentResult.Outcome.Status[] statuses
    ) {

        Map<Province, List<Integer>> indexesByLocation = new LinkedHashMap<>();

        for (int index = 0; index < orders.size(); index++) {

            AdjustmentOrder order = orders.get(index);

            if (!(order instanceof BuildOrder buildOrder))
                continue;

            Province territory = canonicalProvince(
                    buildOrder.location());

            indexesByLocation.computeIfAbsent(
                    territory,
                    ignored -> new ArrayList<>()
            ).add(index);

        }

        for (List<Integer> indexes : indexesByLocation.values()) {
            if (indexes.size() <= 1)
                continue;
            for (int index : indexes)
                statuses[index] =
                        AdjustmentResult.Outcome.Status.REJECTED_DUPLICATE_BUILD_LOCATION;
        }

    }

    private void markDuplicateDisbands(
            List<AdjustmentOrder> orders,
            AdjustmentResult.Outcome.Status[] statuses
    ) {

        Map<UnitId, List<Integer>> indexesByUnit = new LinkedHashMap<>();

        for (int index = 0; index < orders.size(); index++) {

            AdjustmentOrder order = orders.get(index);

            if (!(order instanceof DisbandOrder disbandOrder))
                continue;

            indexesByUnit.computeIfAbsent(
                    disbandOrder.unit(),
                    ignored -> new ArrayList<>()
            ).add(index);

        }

        for (List<Integer> indexes : indexesByUnit.values()) {
            if (indexes.size() <= 1)
                continue;
            for (int index : indexes)
                statuses[index] =
                        AdjustmentResult.Outcome.Status.REJECTED_DUPLICATE_UNIT_ORDER;
        }

    }

    private static Map<Nation, Integer> allowanceMap(
            Map<Nation, Balance> balances,
            boolean builds
    ) {

        Map<Nation, Integer> allowances = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values()) {

            Balance balance = balances.get(nation);

            if (balance == null)
                throw new IllegalArgumentException(
                        "Missing adjustment balance for " + nation);

            allowances.put(
                    nation,
                    builds
                            ? balance.availableBuilds()
                            : balance.requiredDisbands());

        }

        return allowances;

    }

    private static boolean isOccupied(
            Province province,
            Map<UnitId, Province> finalLocations
    ) {
        for (Province occupiedLocation : finalLocations.values())
            if (Province.equalsIgnoreCoast(occupiedLocation, province))
                return true;
        return false;
    }

    private static boolean isLegalBuildUnitType(
            UnitType unitType,
            Province location
    ) {

        if (unitType == UnitType.ARMY) {
            /*
             * An army is built in a territory, not on a selected coast. For
             * split-coast provinces, callers must use the parent territory.
             */
            return ( location.geography != Geography.WATER
                    && location.parent == null );
        }

        if (unitType == UnitType.FLEET) {
            /*
             * Generic St Petersburg, Spain, and Bulgaria are represented as
             * inland parent territories in the current map model. A fleet
             * build must name the appropriate explicit coast.
             */
            return ( location.geography == Geography.COASTAL
                    && location.coastType != CoastType.NONE );
        }

        throw new IllegalArgumentException(
                "Unsupported unit type: " + unitType);

    }

    private static Province canonicalProvince(Province province) {
        return province.parent == null
                ? province
                : province.parent;
    }

}