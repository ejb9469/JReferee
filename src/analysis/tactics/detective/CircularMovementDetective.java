package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes closed movement cycles containing at least three units.
 *
 * <p>Each edge connects a known mover to the unit initially occupying its
 * destination. Unknown orders terminate a traversal; they are not invented.</p>
 *
 * <p>Two-unit cycles belong to HeadToHeadDetective. Feeders entering a cycle
 * are not members of that cycle. Success remains an adjudication question.</p>
 */
public final class CircularMovementDetective extends Detective {


    // Construction \\

    public CircularMovementDetective() {
        super("movement-cycle-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CIRCULAR_MOVEMENT;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Map<UnitId, UnitId> next = new LinkedHashMap<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Optional<UnitId> occupant = context.unitAt(move.target());

            if (occupant.isPresent())
                next.put(move.unit(), occupant.get());

        }

        Set<UnitId> finished = new HashSet<>();
        List<TacticMatch> findings = new ArrayList<>();

        for (UnitId start : next.keySet()) {

            if (finished.contains(start))
                continue;

            List<UnitId> path = new ArrayList<>();
            Map<UnitId, Integer> positions = new HashMap<>();
            UnitId current = start;

            while (current != null
                    && !finished.contains(current)
                    && !positions.containsKey(current)) {

                positions.put(current, path.size());
                path.add(current);
                current = next.get(current);

            }

            Integer cycleStart = current == null ? null : positions.get(current);

            if (cycleStart != null && path.size() - cycleStart >= 3) {

                List<UnitId> cycle =
                        new ArrayList<>(path.subList(cycleStart, path.size()));

                findings.add(finding(context, cycle));

            }

            finished.addAll(path);

        }

        return findings;

    }


    // Finding construction \\

    private TacticMatch finding(
            TacticalContext context,
            List<UnitId> cycle
    ) {

        List<TacticMatch.Participant> participants = new ArrayList<>();
        Province focus = null;

        for (UnitId unit : cycle) {

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.ISSUER, unit));

            Province location = Province.canonical(
                    context.board().locationOf(unit));

            if (focus == null || location.name().compareTo(focus.name()) < 0)
                focus = location;

        }

        return new TacticMatch(
                kind(),
                version(),
                context,
                Objects.requireNonNull(focus),
                participants,
                Set.of());

    }


}