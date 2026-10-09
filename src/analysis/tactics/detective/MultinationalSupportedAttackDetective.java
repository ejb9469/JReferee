package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Nation;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * One known non-self move supported by at least two distinct supporter powers.
 * The mover's nationality does not count towards the threshold. All compatible
 * known supporters are included once, regardless of legality or effectiveness.
 */
public final class MultinationalSupportedAttackDetective extends Detective {


    // Construction \\

    public MultinationalSupportedAttackDetective() {
        super("multinational-supported-attack-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MULTINATIONAL_SUPPORTED_ATTACK;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<UnitId, List<UnitId>> entry
                : StructuralSupportPatterns.movingGroups(context).entrySet()) {

            UnitId mover = entry.getKey();
            Province destination = Province.canonical(
                    context.orders().get(mover).order().target());

            if (destination == Province.canonical(context.board().locationOf(mover)))
                continue;

            Set<Nation> powers = EnumSet.noneOf(Nation.class);
            List<TacticMatch.Participant> participants = new ArrayList<>();
            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.SUPPORTED_UNIT, mover));

            for (UnitId supporter : entry.getValue()) {
                powers.add(supporter.owner());
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTER, supporter));
            }

            if (powers.size() >= 2)
                findings.add(new TacticMatch(kind(), version(), context,
                        destination, participants, Set.of()));

        }

        return findings;

    }


}
