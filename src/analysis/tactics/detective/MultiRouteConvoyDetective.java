package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * At least two distinct simple ordered sea paths connect a known army MOVE's
 * distinct coastal endpoints using matching known sea-fleet CONVOYs.
 * Paths may overlap: this is not a disjointness or resilience claim.
 * One occurrence includes the union of fleets on all candidate paths.
 */
public final class MultiRouteConvoyDetective extends Detective {

    // Constants \\

    private static final String VERSION = "simple-candidate-routes-v1";


    // Construction \\

    public MultiRouteConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MULTI_ROUTE_CONVOY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (ConvoyRelationships.ArmyMove move : ConvoyRelationships.moves(context)) {

            if (!CandidateConvoyGraph.validEndpoints(move.origin(), move.destination()))
                continue;

            Map<Province, UnitId> fleets = CandidateConvoyGraph.matchingFleets(
                    context, move.origin(), move.destination());

            CandidateConvoyGraph.Routes routes = CandidateConvoyGraph.routes(
                    move.origin(), move.destination(), fleets);

            if (routes.countBound() >= 2)
                findings.add(new TacticMatch(kind(), version(), context, move.destination(),
                        ConvoyRelationships.participants(move.army(), routes.fleets()), Set.of()));

        }

        return findings;

    }

}
