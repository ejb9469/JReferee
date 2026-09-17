package phase.adjustments;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.UnitId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Standard-Diplomacy civil-disorder disband selector.
 *
 * <p>Uses the modern rules reflected by DATC:</p>
 *
 * <ol>
 *     <li>Do not remove a unit on a supply center while a unit not on a
 *         supply center remains eligible.</li>
 *     <li>Among eligible units, remove units farthest from a supply center
 *         currently owned by that nation.</li>
 *     <li>Distance is measured using absolute province adjacency, regardless
 *         of whether the unit could legally move through each area.</li>
 *     <li>For equal distance, remove fleets before armies.</li>
 *     <li>For all remaining ties, remove the unit in the alphabetically
 *         earliest province first.</li>
 * </ol>
 *
 * <p>Distances account for both explicit coast representations of an owned
 * split-coast supply center. For example, a fleet's distance to St Petersburg
 * considers both St Petersburg(nc) and St Petersburg(sc).</p>
 */
public final class StandardAutoDisbandPolicy
        implements AutoDisbandPolicy {

    @Override
    public Collection<UnitId> select(
            AdjustmentInput input,
            Map<UnitId, Province> currentLocations,
            Nation nation,
            int numberRequired
    ) {

        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(currentLocations, "currentLocations");
        Objects.requireNonNull(nation, "nation");

        if (numberRequired < 0)
            throw new IllegalArgumentException(
                    "numberRequired cannot be negative");

        if (numberRequired == 0)
            return List.of();

        List<Candidate> candidates = candidatesFor(
                currentLocations,
                nation,
                distancesToOwnedSupplyCenters(input, nation));

        if (candidates.size() < numberRequired)
            throw new IllegalStateException(
                    "Cannot automatically disband "
                            + numberRequired
                            + " unit(s) for "
                            + nation
                            + ": only "
                            + candidates.size()
                            + " surviving unit(s) are available");

        candidates.sort(candidateComparator());

        List<UnitId> selected = new ArrayList<>(numberRequired);

        for (int index = 0; index < numberRequired; index++)
            selected.add(candidates.get(index).unit());

        return List.copyOf(selected);

    }

    /**
     * Produces distance-to-nearest-owned-center values with a multi-source
     * breadth-first search.
     *
     * <p>Every graph edge has cost one. Unit type, occupancy, and convoy
     * availability are intentionally ignored: civil-disorder distance uses
     * absolute adjacency rather than normal movement legality.</p>
     */
    private Map<Province, Integer> distancesToOwnedSupplyCenters(
            AdjustmentInput input,
            Nation nation
    ) {

        Set<Province> ownedCenters = ownedCenters(
                input,
                nation);

        Map<Province, Integer> distances = new HashMap<>();
        Deque<Province> frontier = new ArrayDeque<>();  // clever use of Deque; for distance-based algorithm

        for (Province ownedCenter : ownedCenters) {
            distances.put(ownedCenter, 0);
            frontier.addLast(ownedCenter);
        }

        Map<Province, Province[]> adjacency = Province.getAdjacencyMapCopy();

        while (!frontier.isEmpty()) {

            Province current = frontier.removeFirst();
            int currentDistance = distances.get(current);

            Province[] neighbors = adjacency.get(current);

            if (neighbors == null)
                throw new IllegalStateException(
                        "Province has no adjacency entry: " + current);

            for (Province neighbor : neighbors) {
                if (distances.containsKey(neighbor))
                    continue;
                distances.put(neighbor, currentDistance + 1);
                frontier.addLast(neighbor);
            }

        }

        return distances;

    }

    /**
     * Returns every enum representation of every supply center currently
     * controlled by the nation. A split-coast center is deliberately expanded
     * to its parent and explicit coast values.
     */
    private Set<Province> ownedCenters(
            AdjustmentInput input,
            Nation nation
    ) {

        Set<Province> locations = new LinkedHashSet<>();

        for (Map.Entry<Province, Nation> entry :
                input.owners().entrySet()) {

            if (entry.getValue() != nation)
                continue;

            Province ownedTerritory = canonicalProvince(entry.getKey());

            for (Province candidate : Province.values())
                if (canonicalProvince(candidate) == ownedTerritory)
                    locations.add(candidate);

        }

        return locations;

    }

    private List<Candidate> candidatesFor(
            Map<UnitId, Province> currentLocations,
            Nation nation,
            Map<Province, Integer> distances
    ) {

        List<Candidate> candidates = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry :
                currentLocations.entrySet()) {

            UnitId unit = entry.getKey();
            Province location = entry.getValue();

            if (unit.owner() != nation)
                continue;

            candidates.add(
                    new Candidate(
                            unit,
                            location,
                            isSupplyCenter(location),
                            distanceOf(location, distances)));
        }

        return candidates;

    }

    /**
     * Sorts the first unit to be removed first.
     */
    private Comparator<Candidate> candidateComparator() {

        return Comparator
                /*
                 * false sorts before true, so units off supply centers are
                 * always selected before units on supply centers.
                 */
                .comparing(Candidate::occupiesSupplyCenter)

                /*
                 * Greater distance is removed first. Integer.MAX_VALUE,
                 * representing an unreachable owned center, naturally sorts
                 * as farthest.
                 */
                .thenComparing(
                        Candidate::distanceToNearestOwnedCenter,
                        Comparator.reverseOrder())

                /*
                 * false sorts before true, so fleets come before armies.
                 */
                .thenComparing(
                        candidate -> candidate.unit().unitType()
                                != UnitType.FLEET)

                /*
                 * The standard tie-break is the alphabetically earliest
                 * province name. Coast suffixes do not make a different
                 * territory for this purpose.
                 */
                .thenComparing(
                        candidate -> canonicalProvince(
                                candidate.location()
                        ).fullName)

                /*
                 * This final comparator should not normally be reached:
                 * two units cannot legally occupy one territory. It makes
                 * an inconsistent input deterministic rather than allowing
                 * map iteration order to decide.
                 */
                .thenComparing(
                        candidate -> candidate.unit().origin().name());

    }

    private int distanceOf(
            Province location,
            Map<Province, Integer> distances
    ) {

        Integer distance = distances.get(location);

        if (distance != null)
            return distance;

        /*
         * A caller may hold a parent representation while shortest paths
         * naturally reach one of its explicit split-coast representations.
         * Take the closest equivalent map location.
         */
        Province territory = canonicalProvince(location);
        int shortestEquivalentDistance = Integer.MAX_VALUE;

        for (Province candidate : Province.values()) {

            if (canonicalProvince(candidate) != territory)
                continue;

            Integer candidateDistance = distances.get(candidate);

            if (candidateDistance != null)
                shortestEquivalentDistance = Math.min(
                        shortestEquivalentDistance,
                        candidateDistance);
        }

        return shortestEquivalentDistance;

    }

    private boolean isSupplyCenter(Province province) {
        return canonicalProvince(province).supplyCenter;
    }

    private Province canonicalProvince(Province province) {
        return province.parent == null
                ? province
                : province.parent;
    }

    private record Candidate(
            UnitId unit,
            Province location,
            boolean occupiesSupplyCenter,
            int distanceToNearestOwnedCenter
    ) {
        private Candidate {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(location, "location");
        }
    }

}