package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.Order;

import java.util.*;


/**
 * A known army MOVE with matching fleet CONVOYs also has known support
 * naming that army's actual territory and the same canonical destination.
 * One occurrence per supporter includes every matching convoy fleet.
 * This is order correspondence, not support legality or a viable sea route.
 */
public final class SupportedConvoyLandingDetective extends Detective {

    // Constants \\

    private static final String VERSION = "supported-convoy-correspondence-v1";


    // Construction \\

    public SupportedConvoyLandingDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORTED_CONVOY_LANDING;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (ConvoyRelationships.ArmyMove move : ConvoyRelationships.moves(context)) {

            for (TacticalContext.KnownOrder evidence : context.orders().values()) {

                Order support = evidence.order();

                if (support.orderType() != OrderType.SUPPORT || support.auxiliaryTarget() == null
                        || support.unit().equals(move.army())
                        || Province.canonical(support.target()) != move.origin()
                        || Province.canonical(support.auxiliaryTarget()) != move.destination())
                    continue;

                List<TacticMatch.Participant> participants =
                        ConvoyRelationships.participants(move.army(), move.fleets());

                participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTED_UNIT, move.army()));
                participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTER, support.unit()));

                findings.add(new TacticMatch(kind(), version(), context, move.destination(),
                        participants, Set.of()));

            }

        }

        return findings;

    }

}
