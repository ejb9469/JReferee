package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes moves targeting a unit known to be issuing support.
 *
 * <p>Recognition does not establish a support cut, dislodgement, usable
 * attack route, or hostile intent.</p>
 */
public final class AttackOnSupporterDetective extends Detective {


    // Construction \\

    public AttackOnSupporterDetective() {
        super("attack-on-supporter-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.ATTACK_ON_SUPPORTER;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Optional<UnitId> target = context.unitAt(move.target());

            if (target.isEmpty() || target.get().equals(move.unit()))
                continue;

            UnitId supporter = target.get();

            TacticalContext.KnownOrder targetEvidence =
                    context.orders().get(supporter);

            if (targetEvidence == null
                    || targetEvidence.order().orderType() != OrderType.SUPPORT)
                continue;

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    move.target(),
                    List.of(
                            new TacticMatch.Participant(
                                    TacticMatch.Role.ATTACKER, move.unit()),
                            new TacticMatch.Participant(
                                    TacticMatch.Role.SUPPORTER, supporter)),
                    Set.of()));

        }

        return findings;

    }


}