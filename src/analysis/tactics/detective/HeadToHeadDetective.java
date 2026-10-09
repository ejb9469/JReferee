package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes two known reciprocal moves between occupied territories.
 *
 * <p>These are candidates only. Convoy participation, legality, nationality,
 * and adjudicated head-to-head behavior are not resolved here.</p>
 */
public final class HeadToHeadDetective extends Detective {


    // Construction \\

    public HeadToHeadDetective() {
        super("reciprocal-movement-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.HEAD_TO_HEAD_CANDIDATE;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order first = evidence.order();

            if (first.orderType() != OrderType.MOVE)
                continue;

            Province origin = Province.canonical(
                    context.board().locationOf(first.unit()));

            Optional<UnitId> destinationUnit = context.unitAt(first.target());

            if (destinationUnit.isEmpty())
                continue;

            UnitId other = destinationUnit.get();

            if (other.equals(first.unit()))
                continue;

            TacticalContext.KnownOrder otherEvidence =
                    context.orders().get(other);

            if (otherEvidence == null)
                continue;

            Order second = otherEvidence.order();

            if (second.orderType() != OrderType.MOVE
                    || Province.canonical(second.target()) != origin)
                continue;

            Province otherOrigin = Province.canonical(
                    context.board().locationOf(other));

            // Emit the pair only from its lexicographically first origin.
            if (origin.name().compareTo(otherOrigin.name()) >= 0)
                continue;

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    origin,
                    List.of(
                            new TacticMatch.Participant(
                                    TacticMatch.Role.ATTACKER, first.unit()),
                            new TacticMatch.Participant(
                                    TacticMatch.Role.ATTACKER, other)),
                    Set.of()));

        }

        return findings;

    }


}