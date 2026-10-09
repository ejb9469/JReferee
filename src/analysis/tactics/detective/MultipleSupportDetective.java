package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * Groups compatible support relationships by recipient and tactical focus.
 *
 * <p>Concrete subclasses choose move support or hold support. At least two
 * distinct supporters are required. A group is emitted once, not once per pair.</p>
 *
 * <p>Unknown recipient orders remain missing prerequisites. Contradictory
 * known recipient orders have already been excluded by the source detective.</p>
 */
public abstract class MultipleSupportDetective extends Detective {


    // Core state \\

    private final TacticKind kind;
    private final Detective source;


    // Construction \\

    protected MultipleSupportDetective(boolean moving) {

        super("multiple-support-v1");

        this.kind = moving
                ? TacticKind.MULTIPLE_SUPPORT_TO_MOVE
                : TacticKind.MULTIPLE_SUPPORT_TO_HOLD;

        this.source = moving
                ? new SupportToMoveDetective()
                : new SupportToHoldDetective();

    }


    // Detective identity \\

    @Override
    public final TacticKind kind() {
        return kind;
    }


    // Examination \\

    @Override
    protected final List<TacticMatch> examine(TacticalContext context) {

        Map<Group, List<TacticMatch>> groups = new LinkedHashMap<>();

        for (TacticMatch match : source.investigate(context)) {

            UnitId recipient =
                    match.units(TacticMatch.Role.SUPPORTED_UNIT).getFirst();

            Group group = new Group(recipient, match.focus());

            groups.computeIfAbsent(
                    group, ignored -> new ArrayList<>()).add(match);

        }

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<Group, List<TacticMatch>> entry : groups.entrySet()) {

            Set<UnitId> supporters = new LinkedHashSet<>();
            Set<UnitId> missing = new LinkedHashSet<>();

            for (TacticMatch relationship : entry.getValue()) {

                supporters.addAll(
                        relationship.units(TacticMatch.Role.SUPPORTER));

                missing.addAll(relationship.missingOrders());

            }

            if (supporters.size() < 2)
                continue;

            List<TacticMatch.Participant> participants = new ArrayList<>();

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.SUPPORTED_UNIT,
                    entry.getKey().recipient()));

            for (UnitId supporter : supporters)
                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTER, supporter));

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    entry.getKey().focus(),
                    participants,
                    missing));

        }

        return findings;

    }


    // Group identity \\

    private record Group(UnitId recipient, Province focus) { }


}