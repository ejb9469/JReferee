package phase.adjustments;

import domain.Province;
import phase.PhaseResult;
import phase.UnitId;
import phase.retreats.RetreatResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable post-winter adjustment result.
 *
 * <p>The final board becomes the input board for the next Spring movement
 * phase. Built units are identified using a UnitId whose origin is the build
 * location. This matches the current per-movement-phase UnitId convention.</p>
 */
public final class AdjustmentResult implements PhaseResult {

    private final RetreatResult retreatResult;
    private final Map<UnitId, Province> finalLocations;
    private final Map<domain.Nation, Balance> balances;
    private final List<Outcome> outcomes;
    private final Set<UnitId> disbandedUnits;
    private final Set<UnitId> builtUnits;

    /*
     * Package-private intentionally: `AdjustmentProcessor` will construct a fully validated
     * result after applying accepted orders and automatic disband policy.
     */
    AdjustmentResult(
            RetreatResult retreatResult,
            Map<UnitId, Province> finalLocations,
            Map<domain.Nation, Balance> balances,
            List<Outcome> outcomes,
            Set<UnitId> disbandedUnits,
            Set<UnitId> builtUnits
    ) {

        this.retreatResult = Objects.requireNonNull(
                retreatResult,
                "retreatResult");

        this.finalLocations = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                finalLocations,
                                "finalLocations")));

        this.balances = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                balances,
                                "balances")));

        this.outcomes = List.copyOf(
                new ArrayList<>(
                        Objects.requireNonNull(
                                outcomes,
                                "outcomes")));

        this.disbandedUnits = Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        Objects.requireNonNull(
                                disbandedUnits,
                                "disbandedUnits")));

        this.builtUnits = Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        Objects.requireNonNull(
                                builtUnits,
                                "builtUnits")));

        verifyBoardConsistency();

    }

    public RetreatResult retreatResult() {
        return retreatResult;
    }

    /**
     * Complete board after builds and disbands.
     */
    @Override
    public Map<UnitId, Province> finalLocations() {
        return finalLocations;
    }

    /**
     * Winter balance calculated from the post-retreat board before adjustment
     * orders were applied.
     */
    public Map<domain.Nation, Balance> balances() {
        return balances;
    }

    /**
     * Results for submitted build, disband, and waive orders.
     */
    public List<Outcome> outcomes() {
        return outcomes;
    }

    /**
     * Units voluntarily or automatically removed during winter.
     */
    public Set<UnitId> disbandedUnits() {
        return disbandedUnits;
    }

    /**
     * Units created by accepted build orders.
     */
    public Set<UnitId> builtUnits() {
        return builtUnits;
    }

    private void verifyBoardConsistency() {
        for (UnitId disbandedUnit : disbandedUnits)
            if (finalLocations.containsKey(disbandedUnit))
                throw new IllegalArgumentException(
                        "Disbanded unit remains on final board: "
                                + disbandedUnit);

        for (UnitId builtUnit : builtUnits)
            if (!finalLocations.containsKey(builtUnit))
                throw new IllegalArgumentException(
                        "Built unit is absent from final board: "
                                + builtUnit);

        List<Map.Entry<UnitId, Province>> locations =
                new ArrayList<>(finalLocations.entrySet());

        for (int i = 0; i < locations.size(); i++) {
            for (int j = i + 1; j < locations.size(); j++) {
                if (Province.equalsIgnoreCoast(
                        locations.get(i).getValue(),
                        locations.get(j).getValue())
                )
                    throw new IllegalArgumentException(
                            "Multiple units occupy one final territory: "
                                    + locations.get(i).getKey()
                                    + " and "
                                    + locations.get(j).getKey()
                                    + " at "
                                    + locations.get(i).getValue());
            }
        }

    }

    /**
     * (!)Player-visible(!) adjudication outcome.
     */
    public record Outcome(
            AdjustmentOrder order,
            Status status
    ) {

        public Outcome {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(status, "status");
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }

        public enum Status {
            ACCEPTED,
            //REJECTED_DUPLICATE_UNIT_ORDER,  // should not necessarily be rejected, `BUILD_LOCATION_OCCUPIED` if it is
            //REJECTED_DUPLICATE_BUILD_LOCATION,  // should not necessarily be rejected, `UNIT_NOT_ON_BOARD` if it is
            REJECTED_UNIT_NOT_ON_BOARD,
            REJECTED_UNIT_NOT_OWNED,
            REJECTED_NOT_HOME_SUPPLY_CENTER,
            REJECTED_SUPPLY_CENTER_NOT_CONTROLLED,
            REJECTED_BUILD_LOCATION_OCCUPIED,
            REJECTED_ILLEGAL_UNIT_TYPE,
            REJECTED_NO_BUILD_CAPACITY,
            REJECTED_NO_DISBAND_REQUIREMENT,
            REJECTED_TOO_MANY_ADJUSTMENTS,
            REJECTED_WAIVE_NOT_ALLOWED
        }

    }

}