package analysis.tactics.detective;

import analysis.tactics.*;
import domain.OrderType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes moves into a friendly unit with a known non-moving order.
 *
 * <p>A friendly occupant whose order is unknown or MOVE does not establish
 * this pattern. A failed departure is an assessment concern.</p>
 */
public final class FriendlyOccupantCollisionDetective extends Detective {


    // Construction \\

    public FriendlyOccupantCollisionDetective() {
        super("friendly-occupant-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.FRIENDLY_OCCUPANT_COLLISION;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Optional<UnitId> occupant = context.unitAt(move.target());

            if (occupant.isEmpty())
                continue;

            UnitId resident = occupant.get();

            if (resident.equals(move.unit())
                    || resident.owner() != move.owner())
                continue;

            TacticalContext.KnownOrder residentEvidence =
                    context.orders().get(resident);

            if (residentEvidence == null)
                continue;

            boolean stationary = switch (residentEvidence.order().orderType()) {
                case HOLD, SUPPORT, CONVOY -> true;
                default -> false;
            };

            if (!stationary)
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
                                    TacticMatch.Role.REFERENCED_UNIT, resident)),
                    Set.of()));

        }

        return findings;

    }


}