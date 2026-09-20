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

import static phase.BoardRules.isOccupied;

/**
 * Processes one winter adjustment phase.
 */
public final class AdjustmentProcessor
        implements Processor<AdjustmentInput, AdjustmentResult> {


    private final BalanceCalculator balanceCalculator;
    private final AutoDisbandPolicy autoDisbandPolicy;


    public AdjustmentProcessor(
            BalanceCalculator balanceCalculator,
            AutoDisbandPolicy autoDisbandPolicy
    ) {
        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator,
                "balanceCalculator");
        this.autoDisbandPolicy = Objects.requireNonNull(
                autoDisbandPolicy,
                "autoDisbandPolicy");
    }

    /**
     * Uses the standard civil-disorder policy when voluntary disband orders do
     * not satisfy a nation's required removals.
     */
    public AdjustmentProcessor() {
        this(
                new BalanceCalculator(),
                new StandardAutoDisbandPolicy()
        );
    }


    @Override
    public AdjustmentResult process(AdjustmentInput input) {

        Objects.requireNonNull(input, "input");

        Map<Nation, Balance> balances =
                balanceCalculator.calculate(input);

        Map<UnitId, Province> finalLocations = new LinkedHashMap<>(
                input.retreatResult().finalLocations());

        List<AdjustmentOrder> submittedOrders = input.submittedOrders();

        AdjustmentResult.Outcome.Status[] statuses =
                new AdjustmentResult.Outcome.Status[
                        submittedOrders.size()];

        Map<Nation, Integer> remainingBuilds =
                allowanceMap(balances, true);

        Map<Nation, Integer> remainingDisbands =
                allowanceMap(balances, false);

        Set<UnitId> builtUnits = new LinkedHashSet<>();
        Set<UnitId> disbandedUnits = new LinkedHashSet<>();

        /*
         * Orders are evaluated in submitted order. Therefore, a second build
         * at a previously built site is rejected as occupied, and a second
         * disband of an already removed unit is rejected as absent.
         */
        for (int index = 0; index < submittedOrders.size(); index++) {

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
                    "Unsupported adjustment order type: " + order.getClass().getName());

        }

        applyAutomaticDisbands(
                input,
                finalLocations,
                remainingDisbands,
                disbandedUnits);

        return new AdjustmentResult(
                input.retreatResult(),
                finalLocations,
                balances,
                resultOutcomes(submittedOrders, statuses),
                disbandedUnits,
                builtUnits);

    }

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
                validateBuildSite(input, order, finalLocations);

        if (locationStatus != null)
            return locationStatus;

        UnitId builtUnit = UnitId.newlyBuilt(
                nation,
                order.unitType(),
                order.location());

        finalLocations.put(builtUnit, order.location());
        builtUnits.add(builtUnit);

        remainingBuilds.put(
                nation,
                remainingBuilds.get(nation) - 1);

        return AdjustmentResult.Outcome.Status.ACCEPTED;

    }

    private AdjustmentResult.Outcome.Status validateBuildSite(
            AdjustmentInput input,
            BuildOrder order,
            Map<UnitId, Province> finalLocations
    ) {

        Province location = order.location();
        Province territory = Province.canonical(location);

        if (territory.homeowner != order.nation())
            return AdjustmentResult.Outcome.Status.REJECTED_NOT_HOME_SUPPLY_CENTER;

        if (input.ownerOf(location) != order.nation())
            return AdjustmentResult.Outcome.Status.REJECTED_SUPPLY_CENTER_NOT_CONTROLLED;

        if (isOccupied(location, finalLocations))
            return AdjustmentResult.Outcome.Status.REJECTED_BUILD_LOCATION_OCCUPIED;

        if (!isLegalBuildUnitType(order.unitType(), location))
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

        remainingDisbands.put(
                nation,
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
                        "AutoDisbandPolicy must select exactly "
                                + numberRequired
                                + " unique unit(s) for "
                                + nation);

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

    private static List<AdjustmentResult.Outcome> resultOutcomes(
            List<AdjustmentOrder> submittedOrders,
            AdjustmentResult.Outcome.Status[] statuses
    ) {

        List<AdjustmentResult.Outcome> outcomes = new ArrayList<>(submittedOrders.size());

        for (int index = 0; index < submittedOrders.size(); index++) {

            AdjustmentResult.Outcome.Status status = statuses[index];

            if (status == null)
                throw new IllegalStateException(
                        "Adjustment order received no outcome: " + submittedOrders.get(index));

            outcomes.add(
                    new AdjustmentResult.Outcome(
                            submittedOrders.get(index),
                            status)
            );

        }

        return outcomes;

    }

    private static Map<Nation, Integer> allowanceMap(
            Map<Nation, Balance> balances,
            boolean builds
    ) {

        Map<Nation, Integer> allowances =
                new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values()) {

            Balance balance = balances.get(nation);

            if (balance == null)
                throw new IllegalArgumentException("Missing adjustment balance for " + nation);

            allowances.put(
                    nation,
                    builds
                            ? balance.availableBuilds()
                            : balance.requiredDisbands()
            );

        }

        return allowances;

    }

    private static boolean isLegalBuildUnitType(
            UnitType unitType,
            Province location
    ) {

        if (unitType == UnitType.ARMY)
            return ( location.geography != Geography.WATER
                    && location.parent == null );

        if (unitType == UnitType.FLEET) {
            /*
             * In the current Province model, generic Stp/Spa/Bul are INLAND
             * while their explicit coast variants are COASTAL. This therefore
             * accepts explicit coast builds and rejects ambiguous parents.
             */
            return ( location.geography == Geography.COASTAL
                    && location.coastType != CoastType.NONE );
        }

        throw new IllegalArgumentException(
                "Unsupported unit type: " + unitType);

    }


}