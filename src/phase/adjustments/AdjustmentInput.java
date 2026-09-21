package phase.adjustments;

import domain.Nation;
import domain.Province;
import phase.PhaseInput;
import phase.retreats.RetreatResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static phase.BoardRules.scOwners;

/**
 * Immutable input for one winter adjustment phase.
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
public final class AdjustmentInput
    implements PhaseInput {

    private final RetreatResult retreatResult;
    private final Map<Province, Nation> supplyCenterOwners;
    private final List<AdjustmentOrder> submittedOrders;

    public AdjustmentInput(
            RetreatResult retreatResult,
            Map<Province, Nation> supplyCenterOwners,
            List<AdjustmentOrder> submittedOrders
    ) {
        this.retreatResult = Objects.requireNonNull(
                retreatResult, "retreatResult");
        this.supplyCenterOwners = scOwners(
                supplyCenterOwners, "supplyCenterOwners");
        this.submittedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders, "submittedOrders"));
    }

    public RetreatResult retreatResult() {
        return retreatResult;
    }

    /**
     * Returns each currently controlled supply center and its controlling
     * nation. Uncontrolled centers are absent.
     */
    public Map<Province, Nation> owners() {
        return supplyCenterOwners;
    }

    public List<AdjustmentOrder> submittedOrders() {
        return submittedOrders;
    }

    /**
     * Returns the controlling nation for a supply center, or null when it is neutral/uncontrolled.
     */
    public Nation ownerOf(Province province) {
        Objects.requireNonNull(province, "province");
        return supplyCenterOwners.get(Province.canonical(province));
    }

}