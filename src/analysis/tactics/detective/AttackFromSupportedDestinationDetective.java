package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * One supported non-self move whose destination occupant has a known non-self
 * MOVE. Includes the outgoing ATTACKER, incoming SUPPORTED_UNIT, and all
 * compatible SUPPORTERs. The outgoing move need not target a supporter.
 * This is origin evidence only, not a support-cut outcome or rule exception.
 */
public final class AttackFromSupportedDestinationDetective extends Detective {


    // Construction \\

    public AttackFromSupportedDestinationDetective() {
        super("attack-from-supported-destination-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.ATTACK_FROM_SUPPORTED_DESTINATION;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<UnitId, List<UnitId>> entry
                : StructuralSupportPatterns.movingGroups(context).entrySet()) {

            UnitId incoming = entry.getKey();
            Province destination = Province.canonical(
                    context.orders().get(incoming).order().target());
            Optional<UnitId> resident = context.unitAt(destination);

            if (resident.isEmpty() || resident.get().equals(incoming))
                continue;

            UnitId outgoing = resident.get();
            TacticalContext.KnownOrder departure = context.orders().get(outgoing);

            if (departure == null || departure.order().orderType() != OrderType.MOVE
                    || Province.canonical(departure.order().target()) == destination)
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();
            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.ATTACKER, outgoing));
            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.SUPPORTED_UNIT, incoming));

            for (UnitId supporter : entry.getValue())
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTER, supporter));

            findings.add(new TacticMatch(kind(), version(), context,
                    destination, participants, Set.of()));

        }

        return findings;

    }


}
