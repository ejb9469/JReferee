package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * One maximal open path of at least two known non-self movers. Each mover
 * targets the next mover's current territory; the final mover targets empty,
 * unknown-order, or known nonmoving occupancy. Unknown residents are not path
 * members. Branches produce distinct maximal paths, never suffix subgroups.
 * Cycles and paths feeding cycles are excluded; no successful advance is inferred.
 */
public final class ChainAdvanceDetective extends Detective {


    // Construction \\

    public ChainAdvanceDetective() {
        super("chain-advance-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CHAIN_ADVANCE;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Set<UnitId> movers = new LinkedHashSet<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {
            Order move = evidence.order();
            if (move.orderType() == OrderType.MOVE
                    && Province.canonical(move.target())
                    != Province.canonical(context.board().locationOf(move.unit())))
                movers.add(move.unit());
        }

        Map<UnitId, UnitId> next = new LinkedHashMap<>();
        Set<UnitId> hasPredecessor = new HashSet<>();

        for (UnitId mover : movers) {
            Optional<UnitId> occupant =
                    context.unitAt(context.orders().get(mover).order().target());
            if (occupant.isPresent() && movers.contains(occupant.get())) {
                next.put(mover, occupant.get());
                hasPredecessor.add(occupant.get());
            }
        }

        List<TacticMatch> findings = new ArrayList<>();

        for (UnitId start : movers) {

            if (hasPredecessor.contains(start))
                continue;

            Set<UnitId> path = new LinkedHashSet<>();
            UnitId current = start;

            while (current != null && path.add(current))
                current = next.get(current);

            if (current != null || path.size() < 2)
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();

            for (UnitId mover : path)
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.ISSUER, mover));

            findings.add(new TacticMatch(kind(), version(), context,
                    StructuralSupportPatterns.firstLocation(context, path),
                    participants, Set.of()));

        }

        return findings;

    }


}
