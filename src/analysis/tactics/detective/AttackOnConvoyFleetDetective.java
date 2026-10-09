package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes moves targeting a fleet known to be issuing a convoy.
 *
 * <p>An attack alone does not establish convoy disruption. Route membership,
 * fleet survival, legality, and alternative convoy paths are not assessed.</p>
 */
public final class AttackOnConvoyFleetDetective extends Detective {


    // Construction \\

    public AttackOnConvoyFleetDetective() {
        super("attack-on-convoy-fleet-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.ATTACK_ON_CONVOY_FLEET;
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

            UnitId fleet = target.get();

            if (fleet.unitType() != UnitType.FLEET)
                continue;

            TacticalContext.KnownOrder targetEvidence =
                    context.orders().get(fleet);

            if (targetEvidence == null
                    || targetEvidence.order().orderType() != OrderType.CONVOY)
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
                                    TacticMatch.Role.CONVOYING_UNIT, fleet)),
                    Set.of()));

        }

        return findings;

    }


}