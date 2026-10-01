package phase;

import domain.Nation;
import domain.Province;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared validation and lookup operations for a Diplomacy unit board.
 *
 * <p>Unit locations retain an explicit coast where relevant, but occupancy
 * validation is territory-based: two split coasts of the same province cannot
 * contain separate units.</p>
 */
public abstract class BoardRules {


    // private constructor
    private BoardRules() {  }


    /**
     * Creates an insertion-ordered immutable unit board.
     *
     * <p>Every unit and location must be non-null. Only one unit may occupy a
     * territory; split-coast variants are treated as the same territory.</p>
     *
     * @param suppliedLocations source unit-location map
     * @param argumentName name used in validation error messages
     * @return an immutable validated copy of {@code suppliedLocations}
     */
    // shadows `Objects.requireNonNull(...)`
    public static Map<UnitId, Province> unitLocations(Map<UnitId, Province> suppliedLocations, String argumentName) {

        Objects.requireNonNull(suppliedLocations, argumentName);
        Objects.requireNonNull(argumentName, "argumentName");

        Map<UnitId, Province> copied = new LinkedHashMap<>();
        Set<Province> occupiedTerritories = new LinkedHashSet<>();

        for (Map.Entry<UnitId, Province> entry : suppliedLocations.entrySet()) {

            UnitId unit = Objects.requireNonNull(
                    entry.getKey(),
                    argumentName + " contains a null unit");

            Province location = Objects.requireNonNull(
                    entry.getValue(),
                    argumentName + " contains a null location");

            Province territory = Province.canonical(location);

            if (!occupiedTerritories.add(territory))
                throw new IllegalArgumentException("Multiple units occupy one territory: " + territory);

            copied.put(unit, location);

        }

        return Collections.unmodifiableMap(copied);

    }

    // shadows `Objects.requireNonNull(...)`
    public static Map<Province, Nation> scOwners(Map<Province, Nation> suppliedOwners, String argumentName) {

        Objects.requireNonNull(argumentName, "argumentName");
        Objects.requireNonNull(suppliedOwners, argumentName);

        Map<Province, Nation> copied = new LinkedHashMap<>();

        for (Map.Entry<Province, Nation> entry : suppliedOwners.entrySet()) {

            Province province = Objects.requireNonNull(
                    entry.getKey(),
                    argumentName + " contains a null province");

            Nation owner = Objects.requireNonNull(
                    entry.getValue(),
                    argumentName + " contains a null owner");

            Province territory = Province.canonical(province);

            if (!territory.supplyCenter)
                throw new IllegalArgumentException(
                        "Ownership supplied for non-supply-center: "
                                + province);

            Nation previousOwner = copied.putIfAbsent(territory, owner);

            if (previousOwner != null && previousOwner != owner)
                throw new IllegalArgumentException(
                        "Conflicting owners supplied for "
                                + territory);

        }

        return Collections.unmodifiableMap(copied);

    }

    // shadows `Objects.requireNonNull(...)`
    public static boolean isOccupied(Province province, Map<UnitId, Province> unitLocations) {

        Objects.requireNonNull(province, "province");
        Objects.requireNonNull(unitLocations, "unitLocations");

        for (Province occupiedLocation : unitLocations.values()) {
            if (Province.canonical(occupiedLocation) == Province.canonical(province))
                return true;
        }

        return false;

    }


}