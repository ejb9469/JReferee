package analysis.tactics.detective;

import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/** Order correspondence only: unlike a candidate graph, no sea route is required. */
final class ConvoyRelationships {

    // Construction \\

    private ConvoyRelationships() {  }


    // Known army-move correspondence \\

    static List<ArmyMove> moves(TacticalContext context) {

        List<ArmyMove> moves = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE || move.unitType() != UnitType.ARMY)
                continue;

            Province origin = Province.canonical(context.board().locationOf(move.unit()));
            Province destination = Province.canonical(move.target());
            List<UnitId> fleets = new ArrayList<>();

            for (TacticalContext.KnownOrder candidate : context.orders().values()) {

                Order convoy = candidate.order();

                if (convoy.orderType() == OrderType.CONVOY
                        && convoy.unitType() == UnitType.FLEET
                        && Province.canonical(convoy.target()) == origin
                        && Province.canonical(convoy.auxiliaryTarget()) == destination)
                    fleets.add(convoy.unit());

            }

            if (!fleets.isEmpty())
                moves.add(new ArmyMove(move.unit(), origin, destination, List.copyOf(fleets)));

        }

        return List.copyOf(moves);

    }


    // Shared participant roles \\

    static List<TacticMatch.Participant> participants(
            UnitId army,
            List<UnitId> fleets
    ) {

        List<TacticMatch.Participant> participants = new ArrayList<>();

        participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYED_ARMY, army));

        for (UnitId fleet : fleets)
            participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYING_UNIT, fleet));

        return participants;

    }


    // Correspondence group \\

    record ArmyMove(UnitId army, Province origin, Province destination, List<UnitId> fleets) { }

}
