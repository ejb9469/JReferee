package phase.adjustments;

import domain.Nation;
import domain.Province;
import phase.UnitId;

import java.util.Collection;
import java.util.Map;

/**
 * Selects units automatically removed when a nation has not submitted enough
 * valid voluntary disband orders.
 *
 * <p>The standard policy selects from units not on supply centers before units
 * on supply centers, then uses distance from the nearest supply center owned
 * by the nation, fleet-before-army priority, and alphabetical location
 * tie-breaking. AdjustmentProcessor validates all selected units before
 * applying removals.</p>
 */
@FunctionalInterface
public interface AutoDisbandPolicy {

    /**
     * Selects exactly numberRequired surviving units owned by nation.
     */
    Collection<UnitId> select(
            AdjustmentInput input,
            Map<UnitId, Province> currentLocations,
            Nation nation,
            int numberRequired
    );

    /**
     * Transitional policy for callers that intentionally require all mandatory
     * disbands to be submitted explicitly.
     */
    static AutoDisbandPolicy failWhenRequired() {
        return (input, locations, nation, numberRequired) -> {
            throw new IllegalStateException(
                    "Civil-disorder auto-disband selection is not implemented "
                            + "for "
                            + nation
                            + "; still requires "
                            + numberRequired
                            + " disband(s)");
        };
    }
}