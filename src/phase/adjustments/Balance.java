package phase.adjustments;

import domain.Nation;

import java.util.Objects;

/**
 * A nation's winter adjustment allowance.
 *
 * <p>Net adjustment equals controlled supply centers minus surviving units:
 * positive means builds available, negative means disbands required.</p>
 */
public record Balance(
        Nation nation,
        int controlledSupplyCenters,
        int unitCount
) {

    public Balance {
        Objects.requireNonNull(nation, "nation");
        if (controlledSupplyCenters < 0)
            throw new IllegalArgumentException(
                    "controlledSupplyCenters cannot be negative");
        if (unitCount < 0)
            throw new IllegalArgumentException(
                    "unitCount cannot be negative");
    }

    /**
     * Positive means builds are available; negative means disbands are due.
     */
    public int netAdjustment() {
        return ( controlledSupplyCenters - unitCount );
    }

    public int availableBuilds() {
        return ( Math.max(0, netAdjustment()) );
    }

    public int requiredDisbands() {
        return ( Math.max(0, -netAdjustment()) );
    }

    public boolean isBalanced() {
        return ( netAdjustment() == 0 );
    }

}