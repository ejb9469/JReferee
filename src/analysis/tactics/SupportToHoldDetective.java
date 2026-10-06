package analysis.tactics;

import domain.OrderType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes support-to-hold relationships.<br><br>
 *
 * A complete structural match requires a different active recipient
 * whose known order is HOLD, SUPPORT, or CONVOY.
 *
 * <p>A known MOVE does not qualify merely because it might fail.
 * An unknown recipient order is retained as a missing prerequisite.</p>
 */
public final class SupportToHoldDetective extends Detective {


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORT_TO_HOLD;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order support = evidence.order();

            if (support.orderType() != OrderType.SUPPORT
                    || support.auxiliaryTarget() != null)
                continue;

            Optional<UnitId> recipient = context.unitAt(support.target());

            if (recipient.isEmpty())
                continue;

            UnitId supportedUnit = recipient.get();

            if (supportedUnit.equals(support.unit()))
                continue;

            Optional<TacticalContext.KnownOrder> recipientEvidence =
                    context.orderOf(supportedUnit);

            Set<UnitId> missingOrders;

            if (recipientEvidence.isEmpty()) {

                missingOrders = Set.of(supportedUnit);

            } else {

                Order supportedOrder = recipientEvidence.get().order();

                boolean nonMoving = switch (supportedOrder.orderType()) {
                    case HOLD, SUPPORT, CONVOY -> true;
                    default -> false;
                };

                if (!nonMoving)
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
                    context.board().locationOf(supportedUnit),
                    participants,
                    missingOrders));

        }

        return findings;

    }


}