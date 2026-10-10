package analysis.tactics.detective;

import analysis.tactics.Detective;
import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes convoy orders without a matching known army move.<br><br>
 *
 * A complete diagnostic is produced when the referenced army is absent,
 * the referenced unit is not an army, or its known order is not the
 * requested move.
 *
 * <p>An existing army whose order is unknown produces an incomplete
 * diagnostic. It must not be presented as a confirmed mismatch.</p>
 *
 * <p>This detective examines order correspondence only. It does not
 * establish whether the convoy issuer is legal, whether a route exists,
 * or whether any fleet survives adjudication.</p>
 */
public final class UnmatchedConvoyDetective extends Detective {


    // Constants \\

    private static final String VERSION = "convoy-reference-v1";


    // Construction \\

    public UnmatchedConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.UNMATCHED_CONVOY_ORDER;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order convoy = evidence.order();

            if (convoy.orderType() != OrderType.CONVOY)
                continue;

            Optional<UnitId> referenced = context.unitAt(convoy.target());

            List<TacticMatch.Participant> participants = new ArrayList<>();

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.CONVOYING_UNIT,
                    convoy.unit()));

            Set<UnitId> missingOrders = Set.of();

            if (referenced.isPresent()) {

                UnitId recipient = referenced.get();

                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.REFERENCED_UNIT,
                        recipient));

                if (recipient.unitType() == UnitType.ARMY) {

                    Optional<TacticalContext.KnownOrder> recipientEvidence =
                            context.orderOf(recipient);

                    if (recipientEvidence.isEmpty()) {

                        // The army may still submit the matching move.
                        missingOrders = Set.of(recipient);

                    } else {

                        Order armyOrder = recipientEvidence.get().order();

                        if (matches(convoy, armyOrder))
                            continue;

                    }

                }

            }

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    convoy.auxiliaryTarget(),
                    participants,
                    missingOrders));

        }

        return findings;

    }


    // Army correspondence \\

    private static boolean matches(Order convoy, Order armyOrder) {

        return armyOrder.orderType() == OrderType.MOVE
                && Province.canonical(armyOrder.target())
                == Province.canonical(convoy.auxiliaryTarget());

    }


}