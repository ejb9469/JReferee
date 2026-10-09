package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * One ordered incoming/departing pair: both are known non-self moves and the
 * incoming destination is the resident's current canonical territory.
 * Reciprocal moves qualify; successful vacancy or replacement is not inferred.
 */
abstract class VacancyRelationshipDetective extends Detective {


    // Construction \\

    protected VacancyRelationshipDetective(String version) {
        super(version);
    }


    // Examination \\

    @Override
    protected final List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order incoming = evidence.order();

            if (incoming.orderType() != OrderType.MOVE)
                continue;

            Optional<UnitId> resident = context.unitAt(incoming.target());

            if (resident.isEmpty() || resident.get().equals(incoming.unit()))
                continue;

            UnitId departing = resident.get();
            TacticalContext.KnownOrder known = context.orders().get(departing);

            if (known == null || known.order().orderType() != OrderType.MOVE)
                continue;

            Province location = Province.canonical(context.board().locationOf(departing));

            if (Province.canonical(known.order().target()) == location)
                continue;

            findings.add(new TacticMatch(kind(), version(), context, location,
                    List.of(
                            new TacticMatch.Participant(
                                    TacticMatch.Role.ISSUER, incoming.unit()),
                            new TacticMatch.Participant(
                                    TacticMatch.Role.REFERENCED_UNIT, departing)),
                    Set.of()));

        }

        return findings;

    }


}
