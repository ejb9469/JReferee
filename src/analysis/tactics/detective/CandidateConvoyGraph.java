package analysis.tactics.detective;

import analysis.tactics.TacticalContext;
import domain.Geography;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Known matching sea-fleet graph. Coast-bearing submissions are preserved;
 * army endpoints are canonical territories. No unknown order is a graph node.
 */
final class CandidateConvoyGraph {

    // Construction \\

    private CandidateConvoyGraph() {  }


    // Known matching sea nodes \\

    static Map<Province, UnitId> matchingFleets(
            TacticalContext context,
            Province origin,
            Province destination
    ) {

        Map<Province, UnitId> fleets = new EnumMap<>(Province.class);

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order convoy = evidence.order();

            if (convoy.orderType() != OrderType.CONVOY
                    || convoy.unitType() != UnitType.FLEET)
                continue;

            Province location = context.board().locationOf(convoy.unit());

            if (location.geography != Geography.WATER)
                continue;

            if (Province.canonical(convoy.target()) != origin
                    || Province.canonical(convoy.auxiliaryTarget()) != destination)
                continue;

            fleets.put(location, convoy.unit());

        }

        return fleets;

    }


    // Endpoint geography \\

    static boolean validEndpoints(Province origin, Province destination) {

        return origin != destination && origin.hasCoast() && destination.hasCoast();

    }


    // Known-route existence \\

    static boolean hasRoute(
            Province origin,
            Province destination,
            Set<Province> fleetLocations
    ) {

        Set<Province> visited = EnumSet.noneOf(Province.class);
        Deque<Province> frontier = new ArrayDeque<>();

        for (Province sea : fleetLocations) {

            if (!touchesCoast(sea, origin))
                continue;

            visited.add(sea);
            frontier.addLast(sea);

        }

        while (!frontier.isEmpty()) {

            Province current = frontier.removeFirst();

            if (touchesCoast(current, destination))
                return true;

            for (Province next : fleetLocations) {

                if (visited.contains(next) || !current.isAdjacentTo(next))
                    continue;

                visited.add(next);
                frontier.addLast(next);

            }

        }

        return false;

    }


    // Army endpoint coast contact \\

    static boolean touchesCoast(Province sea, Province endpoint) {

        Province territory = Province.canonical(endpoint);

        if (sea.isAdjacentTo(territory))
            return true;

        for (Province coast : Province.values())
            if (coast.parent == territory && sea.isAdjacentTo(coast))
                return true;

        return false;

    }


    // Simple candidate path enumeration \\

    /**
     * Enumerates all simple directed sea paths, never repeating a node.
     * The count saturates at two (the only distinction consumers need);
     * the fleet union includes every candidate path, not disconnected nodes.
     * A fleet touching the destination may end a path or continue to another
     * endpoint-adjacent fleet, yielding a distinct, longer simple path.
     */
    static Routes routes(
            Province origin,
            Province destination,
            Map<Province, UnitId> fleets
    ) {

        RouteAccumulator result = new RouteAccumulator();

        for (Province start : fleets.keySet())
            if (touchesCoast(start, origin))
                enumerate(start, destination, fleets.keySet(),
                        EnumSet.noneOf(Province.class), result);

        List<UnitId> participants = new ArrayList<>();

        for (Province sea : result.used)
            participants.add(fleets.get(sea));

        return new Routes(result.count, List.copyOf(participants));

    }

    private static void enumerate(
            Province current,
            Province destination,
            Set<Province> nodes,
            Set<Province> path,
            RouteAccumulator result
    ) {

        path.add(current);

        if (touchesCoast(current, destination)) {

            result.count = Math.min(2, result.count + 1);
            result.used.addAll(path);

        }

        for (Province next : nodes)
            if (!path.contains(next) && current.isAdjacentTo(next))
                enumerate(next, destination, nodes, path, result);

        path.remove(current);

    }


    // Route summary and traversal state \\

    record Routes(int countBound, List<UnitId> fleets) { }


    private static final class RouteAccumulator {

        private int count;
        private final Set<Province> used = EnumSet.noneOf(Province.class);

        private RouteAccumulator() {  }

    }

}
