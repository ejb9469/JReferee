package phase.adjustments;

import domain.Nation;
import domain.Province;
import phase.UnitId;

import java.util.Collection;
import java.util.Map;

/**
 * Selects units automatically removed when a nation has not submitted enough
 * valid disband orders.
 *
 * <p>A standard-Diplomacy implementation can later choose units farthest from
 * the nearest home supply center, with the ruleset's army/fleet tie-breakers.
 * `AdjustmentProcessor` validates every selected unit before applying it.</p>
 */
@FunctionalInterface
public interface AutoDisbandPolicy {

    /**
     * Selects exactly {@code numberRequired} surviving units owned by
     * {@code nation}.
     */
    Collection<UnitId> select(
            AdjustmentInput input,
            Map<UnitId, Province> currentLocations,
            Nation nation,
            int numberRequired
    );

    /**
     * Default transition policy: require the caller to supply enough voluntary
     * disbands until a real civil-disorder policy exists.
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