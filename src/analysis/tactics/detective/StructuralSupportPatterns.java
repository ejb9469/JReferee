package analysis.tactics.detective;

import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * Complete compatible support edges, without adjudicating their effectiveness.
 */
final class StructuralSupportPatterns {


    // Construction \\

    private StructuralSupportPatterns() {  }


    // Complete relationships \\

    static List<TacticMatch> complete(TacticalContext context, boolean moving) {

        Detective source = moving
                ? new SupportToMoveDetective()
                : new SupportToHoldDetective();

        return source.investigate(context).stream()
                .filter(TacticMatch::completePattern).toList();

    }

    static List<TacticMatch> complete(TacticalContext context) {

        List<TacticMatch> edges = new ArrayList<>(complete(context, false));
        edges.addAll(complete(context, true));
        return List.copyOf(edges);

    }

    static UnitId supporter(TacticMatch edge) {
        return edge.units(TacticMatch.Role.SUPPORTER).getFirst();
    }

    static UnitId recipient(TacticMatch edge) {
        return edge.units(TacticMatch.Role.SUPPORTED_UNIT).getFirst();
    }

    static Map<UnitId, List<UnitId>> movingGroups(TacticalContext context) {

        Map<UnitId, List<UnitId>> groups = new LinkedHashMap<>();

        for (TacticMatch edge : complete(context, true))
            groups.computeIfAbsent(recipient(edge), ignored -> new ArrayList<>())
                    .add(supporter(edge));

        return groups;

    }

    static Province firstLocation(TacticalContext context, Collection<UnitId> units) {

        return units.stream()
                .map(unit -> Province.canonical(context.board().locationOf(unit)))
                .min(Comparator.comparing(Enum::name)).orElseThrow();

    }


}
