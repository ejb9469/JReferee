package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Maximal destination groups of known non-self moves, with a power threshold.
 * Same-power additional movers remain members; no pairwise subgroups are emitted.
 */
abstract class PowerContestDetective extends Detective {


    // Core state \\

    private final int minimumPowers;


    // Construction \\

    protected PowerContestDetective(String version, int minimumPowers) {
        super(version);
        this.minimumPowers = minimumPowers;
    }


    // Examination \\

    @Override
    protected final List<TacticMatch> examine(TacticalContext context) {

        Map<Province, List<UnitId>> groups = new EnumMap<>(Province.class);

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {
            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Province destination = Province.canonical(move.target());

            if (destination != Province.canonical(context.board().locationOf(move.unit())))
                groups.computeIfAbsent(destination, ignored -> new ArrayList<>())
                        .add(move.unit());
        }

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<Province, List<UnitId>> group : groups.entrySet()) {

            Set<Nation> powers = EnumSet.noneOf(Nation.class);
            List<TacticMatch.Participant> participants = new ArrayList<>();

            for (UnitId mover : group.getValue()) {
                powers.add(mover.owner());
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.ATTACKER, mover));
            }

            if (powers.size() >= minimumPowers)
                findings.add(new TacticMatch(kind(), version(), context,
                        group.getKey(), participants, Set.of()));

        }

        return findings;

    }


}
