package analysis.tactics.detective;

import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
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
                    CandidateConvoyGraph.matchingFleets(context, origin, destination);

            /*
             * Do not diagnose every unsupported coastal army move as an
             * incomplete convoy. Require an actual matching convoy submission.
             */
            if (fleets.isEmpty())
                continue;

            if (CandidateConvoyGraph.hasRoute(origin, destination, fleets.keySet()))
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


}