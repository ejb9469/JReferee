package phase.adjustments;

import domain.Nation;
import phase.UnitId;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates winter build/disband balances from immutable post-retreat state.
 *
 * <p>This class has no knowledge of submitted adjustment orders.</p>
 */
public final class BalanceCalculator {

    public Map<Nation, Balance> calculate(AdjustmentInput input) {

        Objects.requireNonNull(input, "input");

        Map<Nation, Integer> supplyCenterCounts =
                countsByNation();

        Map<Nation, Integer> unitCounts =
                countsByNation();

        for (Nation owner : input.owners().values())
            supplyCenterCounts.merge(owner, 1, Integer::sum);

        for (UnitId unit : input.retreatResult().finalLocations().keySet())
            unitCounts.merge(unit.owner(), 1, Integer::sum);

        Map<Nation, Balance> balances =
                new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values())
            balances.put(nation,
                    new Balance(nation,
                            supplyCenterCounts.get(nation),
                            unitCounts.get(nation))
            );

        return Collections.unmodifiableMap(balances);

    }

    private static Map<Nation, Integer> countsByNation() {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            counts.put(nation, 0);
        return counts;
    }

}