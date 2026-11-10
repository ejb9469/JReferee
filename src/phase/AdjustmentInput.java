package phase;

import domain.Nation;
import domain.Province;
import phase.adjustments.AdjustmentOrder;
import phase.retreats.RetreatResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static phase.BoardRules.scOwners;
import static domain.Constants.STARTING_YEAR;

/**
 * Immutable input for one winter adjustment phase.
 * The year distinguishes rebuilt units while keeping repeated resolutions identical.
 *
 * <p>The post-retreat board is supplied by `RetreatResult`. Supply-center
 * ownership is deliberately supplied separately: a center's controlling
 * nation is game state derived after Fall movement, not a property of the
 * unit currently occupying that province.</p>
 *
 * <p>Uncontrolled supply centers are omitted from {@code supplyCenterOwners}.
 * All stored ownership keys are canonical territories: split-coast variants
 * such as "Spain(nc)" normalize to "Spain".</p>
 */
public record AdjustmentInput(int year,
                              RetreatResult retreatResult,
                              Map<Province, Nation> supplyCenterOwners,
                              List<AdjustmentOrder> submittedOrders)
        implements PhaseInput {


    public AdjustmentInput(
            int year,
            RetreatResult retreatResult,
            Map<Province, Nation> supplyCenterOwners,
            List<AdjustmentOrder> submittedOrders
    ) {
        if (year < STARTING_YEAR)
            throw new IllegalArgumentException("Adjustment year must be at least " + STARTING_YEAR);
        this.year = year;
        this.retreatResult = Objects.requireNonNull(
                retreatResult, "retreatResult");
        this.supplyCenterOwners = scOwners(
                supplyCenterOwners, "supplyCenterOwners");
        this.submittedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders, "submittedOrders"));
    }

    /**
     * Returns each currently controlled supply center and its controlling
     * nation. Uncontrolled centers are absent.
     */
    public Map<Province, Nation> owners() {
        return supplyCenterOwners;
    }

    /**
     * Returns the controlling nation for a supply center, or null when it is neutral/uncontrolled.
     */
    public Nation ownerOf(Province province) {
        Objects.requireNonNull(province, "province");
        return supplyCenterOwners.get(Province.canonical(province));
    }


}