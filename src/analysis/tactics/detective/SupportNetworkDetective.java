package analysis.tactics.detective;

import analysis.tactics.*;
import phase.UnitId;

import java.util.*;


/**
 * One maximal weakly connected component of at least two complete compatible
 * support edges. Hold and move edges may mix. Roles are the union of edge
 * endpoints; a unit can be both supporter and recipient. The least canonical
 * member location is the focus. Unknown and contradictory edges are excluded.
 */
public final class SupportNetworkDetective extends Detective {


    // Construction \\

    public SupportNetworkDetective() {
        super("support-network-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORT_NETWORK;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> edges = StructuralSupportPatterns.complete(context);
        Map<UnitId, Set<UnitId>> adjacent = new LinkedHashMap<>();

        for (TacticMatch edge : edges) {
            UnitId from = StructuralSupportPatterns.supporter(edge);
            UnitId to = StructuralSupportPatterns.recipient(edge);
            adjacent.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
            adjacent.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from);
        }

        Set<UnitId> visited = new HashSet<>();
        List<TacticMatch> findings = new ArrayList<>();

        for (UnitId start : adjacent.keySet()) {

            if (visited.contains(start))
                continue;

            Set<UnitId> component = new LinkedHashSet<>();
            Deque<UnitId> pending = new ArrayDeque<>(List.of(start));

            while (!pending.isEmpty()) {
                UnitId unit = pending.removeFirst();
                if (component.add(unit))
                    pending.addAll(adjacent.get(unit));
            }

            visited.addAll(component);
            Set<TacticMatch.Participant> participants = new LinkedHashSet<>();
            int edgeCount = 0;

            for (TacticMatch edge : edges)
                if (component.contains(StructuralSupportPatterns.supporter(edge))) {
                    edgeCount++;
                    participants.addAll(edge.participants());
                }

            if (edgeCount >= 2)
                findings.add(new TacticMatch(kind(), version(), context,
                        StructuralSupportPatterns.firstLocation(context, component),
                        new ArrayList<>(participants), Set.of()));

        }

        return findings;

    }


}
