package analysis.tactics.detective;

import analysis.tactics.Detective;
import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes same-power moves competing for one territory.<br><br>
 *
 * A match contains every known mover of one nation targeting the same
 * canonical destination, provided there are at least two such units.
 *
 * <p>This is a self-bounce candidate, not an adjudicated bounce.
 * Route validity, support strength, destination occupancy, and external
 * interference are left to assessment.</p>
 */
public final class SelfBounceDetective extends Detective {


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SELF_BOUNCE;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Map<Competition, List<UnitId>> competitions = new LinkedHashMap<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Competition competition = new Competition(
                    move.owner(),
                    Province.canonical(move.target()));

            competitions.computeIfAbsent(
                    competition, ignored -> new ArrayList<>()
            ).add(move.unit());

        }

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<Competition, List<UnitId>> entry
                : competitions.entrySet()) {

            List<UnitId> movers = entry.getValue();

            if (movers.size() < 2)
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();

            for (UnitId unit : movers)
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.ATTACKER,
                        unit));

            /*
             * All local prerequisites are already known: at least two
             * same-power moves target this territory.
             *
             * Unknown surrounding orders may affect the outcome or add
             * further participants when the context is examined again.
             */
            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    entry.getKey().destination(),
                    participants,
                    Set.of()));

        }

        return findings;

    }


    // Competition identity \\

    /**
     * Groups incoming moves by owner and canonical destination.
     */
    private record Competition(
            Nation nation,
            Province destination
    ) { }


}