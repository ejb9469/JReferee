package analysis.tactics.detective;

import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import domain.Geography;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes matching convoy submissions that do not establish a sea route.
 *
 * <p>A finding requires a known army MOVE between distinct coastal
 * territories and at least one matching CONVOY submitted by a fleet
 * currently occupying a sea province.</p>
 *
 * <p>The known matching sea fleets must fail to connect the army's origin
 * to its destination. Fleet orders naming another army or destination,
 * armies issuing convoy orders, and fleets in coastal provinces are not
 * usable route nodes.</p>
 *
 * <p>This is an absence of a route in the known submissions, not proof
 * that every completion of unknown orders will fail. A connected candidate
 * route is not proof that the convoy survives adjudication.</p>
 */
public final class IncompleteConvoyChainDetective extends Detective {


    // Constants \\

    private static final String VERSION = "known-convoy-chain-v1";


    // Construction \\

    public IncompleteConvoyChainDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.INCOMPLETE_CONVOY_CHAIN;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE
                    || move.unitType() != UnitType.ARMY)
                continue;

            Province origin = Province.canonical(
                    context.board().locationOf(move.unit()));

            Province destination = Province.canonical(move.target());

            /*
             * Invalid endpoint geography is a different diagnostic.
             * Split-coast parent provinces are handled by hasCoast().
             */
            if (origin == destination
                    || !origin.hasCoast()
                    || !destination.hasCoast())
                continue;

            Map<Province, UnitId> fleets =
                    matchingFleets(context, origin, destination);

            /*
             * Do not diagnose every unsupported coastal army move as an
             * incomplete convoy. Require an actual matching convoy submission.
             */
            if (fleets.isEmpty())
                continue;

            if (hasRoute(origin, destination, fleets.keySet()))
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.CONVOYED_ARMY,
                    move.unit()));

            for (UnitId fleet : fleets.values())
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.CONVOYING_UNIT,
                        fleet));

            /*
             * The known graph has been fully examined.
             * Unknown fleet orders are not guessed into that graph, and
             * arbitrary missing bridge units are not invented as participants.
             *
             * completePattern() therefore describes this known-graph finding,
             * not a complete scenario or an inevitable failed convoy.
             */
            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    destination,
                    participants,
                    Set.of()));

        }

        return findings;

    }


    // Matching route nodes \\

    private static Map<Province, UnitId> matchingFleets(
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

            if (Province.canonical(convoy.target()) != origin)
                continue;

            if (Province.canonical(convoy.auxiliaryTarget()) != destination)
                continue;

            fleets.put(location, convoy.unit());

        }

        return fleets;

    }


    // Known-route search \\

    /**
     * Searches the matching sea-fleet graph without adjudicating any order.
     *
     * <p>Only sea provinces are graph nodes. Coastal provinces are endpoints,
     * never intermediate stepping stones.</p>
     */
    private static boolean hasRoute(
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

                if (visited.contains(next))
                    continue;

                if (!current.isAdjacentTo(next))
                    continue;

                visited.add(next);
                frontier.addLast(next);

            }

        }

        return false;

    }

    /**
     * Checks whether a sea province touches any coast of an army endpoint.
     *
     * <p>Armies occupy canonical territories rather than a particular
     * fleet coast. Exact source orders remain unchanged in the context.</p>
     */
    private static boolean touchesCoast(
            Province sea,
            Province endpoint
    ) {

        Province territory = Province.canonical(endpoint);

        if (sea.isAdjacentTo(territory))
            return true;

        for (Province coast : Province.values())
            if (coast.parent == territory && sea.isAdjacentTo(coast))
                return true;

        return false;

    }


}