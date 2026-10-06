package analysis.tactics;

import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes competing moves into an occupied territory.<br><br>
 *
 * A match requires an initial occupant and at least two other active
 * units with known MOVE orders targeting that territory.
 *
 * <p>Incoming movers are included regardless of nationality.
 * The occupant may be moving, stationary, or have an unknown order.</p>
 *
 * <p>Recognition does not establish that the occupant survives or that
 * competing attacks preserve the garrison. Those are assessment claims.</p>
 */
public final class BeleagueredGarrisonDetective extends Detective {


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.BELEAGUERED_GARRISON;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Map<Province, List<UnitId>> incoming = new EnumMap<>(Province.class);

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Province destination = Province.canonical(move.target());

            Optional<UnitId> occupant = context.unitAt(destination);

            if (occupant.isEmpty())
                continue;

            // An order to the issuer's own territory is not another attacker.
            if (occupant.get().equals(move.unit()))
                continue;

            incoming.computeIfAbsent(
                    destination, ignored -> new ArrayList<>()
            ).add(move.unit());

        }

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<Province, List<UnitId>> entry : incoming.entrySet()) {

            List<UnitId> attackers = entry.getValue();

            if (attackers.size() < 2)
                continue;

            Province destination = entry.getKey();

            // Entries are created only for occupied territories.
            UnitId defender = context.unitAt(destination).orElseThrow();

            List<TacticMatch.Participant> participants = new ArrayList<>();

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.DEFENDER,
                    defender));

            for (UnitId attacker : attackers)
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.ATTACKER,
                        attacker));

            /*
             * The defender's order is not a structural prerequisite.
             * Its survival is established only when assessing a complete
             * scenario, not by assuming that the defender holds.
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