package phase;

import domain.Nation;
import domain.Province;

import java.util.*;
import java.util.function.Predicate;


/**
 * <b>Coast-aware lookups</b> using actual board locations, not UnitId.origin().
 *
 * <p>Iteration order is preserved. Callers requiring a unique unit must use
 * matchingAt(...) and enforce their own missing/ambiguous-result policy.</p>
 */
public abstract class UnitLookup {


    private UnitLookup() {  }


    public static UnitId firstAt(Map<UnitId, Province> locations, Province province) {
        return firstAt(locations, province, unit -> true);
    }

    public static UnitId firstAt(
            Map<UnitId, Province> locations,
            Province province,
            Nation owner
    ) {
        return firstAt(locations, province, unit -> unit.owner() == owner);
    }

    public static UnitId firstAt(
            Map<UnitId, Province> locations,
            Province province,
            Predicate<UnitId> filter
    ) {

        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(filter, "filter");

        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            if (filter.test(entry.getKey())
                    && Province.equalsIgnoreCoast(entry.getValue(), province))
                return entry.getKey();
        }

        return null;

    }

    public static List<UnitId> matchingAt(
            Map<UnitId, Province> locations,
            Province province,
            Predicate<UnitId> filter
    ) {

        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(filter, "filter");

        List<UnitId> matches = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            if (filter.test(entry.getKey())
                    && Province.equalsIgnoreCoast(entry.getValue(), province))
                matches.add(entry.getKey());
        }

        return List.copyOf(matches);

    }


}