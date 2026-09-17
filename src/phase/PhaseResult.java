package phase;

import domain.Province;

import java.util.Map;

/**
 * Immutable (& externally-visible) result of one completed game phase,
 * i.e. Movement, Retreats, and Adjustments/Builds.
 *
 * <p>The post-phase board is the common contract between phases. More specific
 * facts—movement dislodgements, retreat collisions, winter adjustment
 * balances, etc., belong on the relevant subtype.</p>
 */
public interface PhaseResult {

    /**
     * Complete board after this phase.
     *
     * <p>A key is a stable unit identity. A value is that surviving unit's
     * location after this phase. Units removed in the phase are absent.</p>
     */
    Map<UnitId, Province> finalLocations();

    /**
     * Returns the post-phase location of a unit, or {@code null} if the unit
     * is absent from the resulting board.
     */
    default Province locationOf(UnitId unit) {
        return finalLocations().get(unit);
    }

    /**
     * Returns whether the unit remains on the board after this phase.
     */
    default boolean contains(UnitId unit) {
        return finalLocations().containsKey(unit);
    }

}