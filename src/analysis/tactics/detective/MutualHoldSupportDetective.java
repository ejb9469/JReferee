package analysis.tactics.detective;

import analysis.tactics.*;
import phase.UnitId;

import java.util.*;


/**
 * One finding per reciprocal pair of known compatible hold-support orders.
 * Each unit has both roles. Unknown orders and self-support are excluded.
 */
public final class MutualHoldSupportDetective extends Detective {


    // Construction \\

    public MutualHoldSupportDetective() {
        super("mutual-hold-support-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MUTUAL_HOLD_SUPPORT;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Map<UnitId, UnitId> recipients = new LinkedHashMap<>();

        for (TacticMatch edge : StructuralSupportPatterns.complete(context, false))
            recipients.put(StructuralSupportPatterns.supporter(edge),
                    StructuralSupportPatterns.recipient(edge));

        List<TacticMatch> findings = new ArrayList<>();
        Set<UnitId> emitted = new HashSet<>();

        for (Map.Entry<UnitId, UnitId> edge : recipients.entrySet()) {

            UnitId first = edge.getKey();
            UnitId second = edge.getValue();

            if (emitted.contains(first) || !first.equals(recipients.get(second)))
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();

            for (UnitId unit : List.of(first, second)) {
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTER, unit));
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTED_UNIT, unit));
            }

            findings.add(new TacticMatch(kind(), version(), context,
                    StructuralSupportPatterns.firstLocation(context, List.of(first, second)),
                    participants, Set.of()));

            emitted.add(first);
            emitted.add(second);

        }

        return findings;

    }


}
