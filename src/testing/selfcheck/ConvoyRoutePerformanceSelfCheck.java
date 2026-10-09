package testing.selfcheck;

import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import analysis.tactics.detective.MultiRouteConvoyDetective;
import domain.Geography;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;

import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * Full standard-map route enumeration benchmark and exact route-union check.
 * An independent indexed reference counts all simple paths without saturation.
 * Every ordered pair of distinct canonical coastal endpoints is exercised with
 * all 19 sea fleets, the maximal available graph for any fleet subset.
 */
public final class ConvoyRoutePerformanceSelfCheck {


    // Constants \\

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);

    private static final int WARMED_REPETITIONS = 200;


    // Construction \\

    private ConvoyRoutePerformanceSelfCheck() { }


    // Application entry point \\

    public static void main(String[] args) {

        List<Province> seas = Arrays.stream(Province.values())
                .filter(province -> province.geography == Geography.WATER)
                .toList();

        List<Province> endpoints = Arrays.stream(Province.values())
                .filter(province -> Province.canonical(province) == province
                        && province.hasCoast())
                .toList();

        boolean[][] adjacent = new boolean[seas.size()][seas.size()];

        for (int from = 0; from < seas.size(); from++)
            for (int to = 0; to < seas.size(); to++)
                adjacent[from][to] = seas.get(from).isAdjacentTo(seas.get(to));

        MultiRouteConvoyDetective detective = new MultiRouteConvoyDetective();
        int pairs = 0;
        long paths = 0;
        long maximumPaths = -1;
        long productionNanos = 0;
        long maximumColdNanos = 0;
        Province worstOrigin = null;
        Province worstDestination = null;
        TacticalContext worstContext = null;

        long matrixStart = System.nanoTime();

        for (Province origin : endpoints) {

            for (Province destination : endpoints) {

                if (origin == destination)
                    continue;

                Reference reference = countPaths(origin, destination, seas, adjacent);
                TacticalContext context = context(origin, destination, seas);
                long start = System.nanoTime();
                List<TacticMatch> findings = detective.investigate(context);
                long elapsed = System.nanoTime() - start;

                productionNanos += elapsed;
                maximumColdNanos = Math.max(maximumColdNanos, elapsed);
                verify(origin, destination, seas, reference, findings);
                pairs++;
                paths += reference.paths;

                if (reference.paths > maximumPaths) {

                    maximumPaths = reference.paths;
                    worstOrigin = origin;
                    worstDestination = destination;
                    worstContext = context;

                }

            }

        }

        long matrixNanos = System.nanoTime() - matrixStart;
        long[] warmedNanos = new long[WARMED_REPETITIONS];

        for (int iteration = 0; iteration < WARMED_REPETITIONS; iteration++) {

            long start = System.nanoTime();
            detective.investigate(worstContext);
            warmedNanos[iteration] = System.nanoTime() - start;

        }

        Arrays.sort(warmedNanos);

        System.out.printf(
                "Convoy route performance self-check passed: %d seas; %d coastal endpoints; "
                        + "%d ordered pairs; %,d total reference paths.%n",
                seas.size(), endpoints.size(), pairs, paths);

        System.out.printf(
                "Maximum simple paths: %,d (%s -> %s). "
                        + "Production total %.3f ms; matrix with reference %.3f ms; "
                        + "largest initial call %.3f ms.%n",
                maximumPaths, worstOrigin, worstDestination,
                millis(productionNanos), millis(matrixNanos), millis(maximumColdNanos));

        System.out.printf(
                "Worst-path pair warmed (%d repetitions): median %.3f ms; "
                        + "p95 %.3f ms; maximum %.3f ms.%n",
                WARMED_REPETITIONS, millis(warmedNanos[WARMED_REPETITIONS / 2]),
                millis(warmedNanos[WARMED_REPETITIONS * 95 / 100]),
                millis(warmedNanos[WARMED_REPETITIONS - 1]));

    }


    // Independent unsaturated indexed reference \\

    private static Reference countPaths(
            Province origin,
            Province destination,
            List<Province> seas,
            boolean[][] adjacent
    ) {

        Reference result = new Reference(seas.size());
        boolean[] starts = contacts(origin, seas);
        boolean[] finishes = contacts(destination, seas);

        for (int start = 0; start < seas.size(); start++)
            if (starts[start])
                count(start, 0L, finishes, adjacent, result);

        return result;

    }

    private static void count(
            int current,
            long visited,
            boolean[] finishes,
            boolean[][] adjacent,
            Reference result
    ) {

        long path = visited | (1L << current);

        if (finishes[current]) {

            result.paths++;

            for (int node = 0; node < finishes.length; node++)
                if ((path & (1L << node)) != 0)
                    result.used[node] = true;

        }

        for (int next = 0; next < finishes.length; next++)
            if (adjacent[current][next] && (path & (1L << next)) == 0)
                count(next, path, finishes, adjacent, result);

    }

    private static boolean[] contacts(Province endpoint, List<Province> seas) {

        boolean[] contacts = new boolean[seas.size()];

        for (int index = 0; index < seas.size(); index++)
            for (Province coast : Province.values())
                if (Province.canonical(coast) == endpoint
                        && seas.get(index).isAdjacentTo(coast))
                    contacts[index] = true;

        return contacts;

    }


    // Complete fleet contexts and exact evidence checks \\

    private static TacticalContext context(
            Province origin,
            Province destination,
            List<Province> seas
    ) {

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        Map<UnitId, TacticalContext.KnownOrder> orders = new LinkedHashMap<>();
        UnitId army = unit(UnitType.ARMY, origin);

        locations.put(army, origin);
        orders.put(army, known(Order.move(army, destination)));

        for (Province sea : seas) {

            UnitId fleet = unit(UnitType.FLEET, sea);

            locations.put(fleet, sea);
            orders.put(fleet, known(Order.convoy(fleet, origin, destination)));

        }

        return new TacticalContext("convoy-performance-v1", MOMENT,
                new BoardState(locations, Map.of()), orders);

    }

    private static void verify(
            Province origin,
            Province destination,
            List<Province> seas,
            Reference reference,
            List<TacticMatch> findings
    ) {

        if (findings.size() != (reference.paths >= 2 ? 1 : 0))
            throw new IllegalStateException("Wrong route multiplicity: " + origin + " -> " + destination);

        if (findings.isEmpty())
            return;

        Set<UnitId> expected = new HashSet<>();

        for (int index = 0; index < seas.size(); index++)
            if (reference.used[index])
                expected.add(unit(UnitType.FLEET, seas.get(index)));

        TacticMatch match = findings.getFirst();

        if (match.focus() != destination || !match.completePattern()
                || !match.units(TacticMatch.Role.CONVOYED_ARMY)
                        .equals(List.of(unit(UnitType.ARMY, origin)))
                || !new HashSet<>(match.units(TacticMatch.Role.CONVOYING_UNIT)).equals(expected))
            throw new IllegalStateException("Wrong route participants: " + origin + " -> " + destination);

    }


    // Fixtures and time presentation \\

    private static UnitId unit(UnitType type, Province origin) {

        return new UnitId(UUID.nameUUIDFromBytes(
                (type + ":" + origin).getBytes(StandardCharsets.UTF_8)),
                Nation.ENGLAND, type, origin);

    }

    private static TacticalContext.KnownOrder known(Order order) {

        return new TacticalContext.KnownOrder(order, TacticalContext.Provenance.SUBMITTED);

    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }


    // Reference state \\

    private static final class Reference {

        private long paths;
        private final boolean[] used;

        private Reference(int nodes) {
            used = new boolean[nodes];
        }

    }


}
