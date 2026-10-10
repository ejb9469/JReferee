package analysis.tactics.detective;

import analysis.tactics.Detective;
import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;

import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes support-to-move relationships.<br><br>
 *
 * A complete match requires a support order referring to another active
 * unit whose known MOVE targets the same canonical destination.
 *
 * <p>If the recipient exists but its order is unknown, the relationship
 * is retained as an incomplete match. A known contradictory order is
 * not a match.</p>
 */
public final class SupportToMoveDetective extends Detective {


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORT_TO_MOVE;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order support = evidence.order();

            if (support.orderType() != OrderType.SUPPORT
                    || support.auxiliaryTarget() == null)
                continue;

            Optional<UnitId> recipient = context.unitAt(support.target());

            if (recipient.isEmpty())
                continue;

            UnitId supportedUnit = recipient.get();

            // A unit cannot form a support relationship with itself.
            if (supportedUnit.equals(support.unit()))
                continue;

            Province destination = Province.canonical(
                    support.auxiliaryTarget());

            Optional<TacticalContext.KnownOrder> recipientEvidence =
                    context.orderOf(supportedUnit);

            Set<UnitId> missingOrders;

            if (recipientEvidence.isEmpty()) {

                // Unknown is not a fabricated MOVE or an implicit HOLD.
                missingOrders = Set.of(supportedUnit);

            } else {

                Order supportedOrder = recipientEvidence.get().order();

                if (supportedOrder.orderType() != OrderType.MOVE)
                    continue;

                if (Province.canonical(supportedOrder.target()) != destination)
                    continue;

                missingOrders = Set.of();

            }

            List<TacticMatch.Participant> participants = List.of(
                    new TacticMatch.Participant(
                            TacticMatch.Role.SUPPORTER,
                            support.unit()),
                    new TacticMatch.Participant(
                            TacticMatch.Role.SUPPORTED_UNIT,
                            supportedUnit));

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    destination,
                    participants,
                    missingOrders));

        }

        return findings;

    }


}