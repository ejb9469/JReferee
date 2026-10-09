package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * Two known reciprocal army MOVEs each have their own known matching
 * sea-fleet candidate route. One occurrence per unordered army pair;
 * the lexically first endpoint is the canonical focus. No success claim.
 */
public final class ConvoySwapDetective extends Detective {

    // Constants \\

    private static final String VERSION = "reciprocal-candidate-convoys-v1";


    // Construction \\

    public ConvoySwapDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CONVOY_SWAP;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();
        List<ConvoyRelationships.ArmyMove> moves = ConvoyRelationships.moves(context);

        for (int first = 0; first < moves.size(); first++) {

            ConvoyRelationships.ArmyMove a = moves.get(first);

            if (!CandidateConvoyGraph.validEndpoints(a.origin(), a.destination()))
                continue;

            for (int second = first + 1; second < moves.size(); second++) {

                ConvoyRelationships.ArmyMove b = moves.get(second);

                if (a.origin() != b.destination() || a.destination() != b.origin())
                    continue;

                CandidateConvoyGraph.Routes aRoutes = routes(context, a);
                CandidateConvoyGraph.Routes bRoutes = routes(context, b);

                if (aRoutes.countBound() == 0 || bRoutes.countBound() == 0)
                    continue;

                List<TacticMatch.Participant> participants = new ArrayList<>(
                        ConvoyRelationships.participants(a.army(), aRoutes.fleets()));

                participants.addAll(ConvoyRelationships.participants(b.army(), bRoutes.fleets()));

                Province focus = a.origin().name().compareTo(b.origin().name()) < 0
                        ? a.origin() : b.origin();

                findings.add(new TacticMatch(kind(), version(), context, focus, participants, Set.of()));

            }

        }

        return findings;

    }


    // Candidate route evidence \\

    private static CandidateConvoyGraph.Routes routes(
            TacticalContext context,
            ConvoyRelationships.ArmyMove move
    ) {

        Map<Province, UnitId> fleets = CandidateConvoyGraph.matchingFleets(
                context, move.origin(), move.destination());

        return CandidateConvoyGraph.routes(move.origin(), move.destination(), fleets);

    }

}
