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
 * Recognizes contradicted references in statically acceptable support.
 *
 * <p>Support rejected by BogusMovesDetective's shared static check is
 * excluded before reference matching. Such a submission belongs to
 * BOGUS_MOVES, even if its recipient is absent or contradictory.</p>
 *
 * <p>An existing recipient with an unknown order is not a contradiction.
 * This detective does not infer intent or adjudicate support effectiveness.</p>
 */
public final class SupportMismatchDetective extends Detective {


    // Constants \\

    /*
     * v1 examined support references without static-validity filtering.
     * v2 excludes support submissions classified as bogus.
     */
    private static final String VERSION = "support-reference-v2";


    // Construction \\

    public SupportMismatchDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORT_ORDER_MISMATCH;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order support = evidence.order();

            if (support.orderType() != OrderType.SUPPORT)
                continue;

            // Impossible support is a bogus submission, not a reference mismatch.
            if (BogusMovesDetective.isBogus(context, support))
                continue;

            Optional<UnitId> referenced = context.unitAt(support.target());

            if (referenced.isEmpty()) {

                findings.add(finding(context, support, Optional.empty()));
                continue;

            }

            UnitId recipient = referenced.get();

            if (recipient.equals(support.unit())) {

                findings.add(finding(context, support, referenced));
                continue;

            }

            Optional<TacticalContext.KnownOrder> recipientEvidence =
                    context.orderOf(recipient);

            if (recipientEvidence.isEmpty())
                continue;

            if (compatible(support, recipientEvidence.get().order()))
                continue;

            findings.add(finding(context, support, referenced));

        }

        return findings;

    }


    // Reference compatibility \\

    private static boolean compatible(Order support, Order recipient) {

        if (support.auxiliaryTarget() == null) {

            return switch (recipient.orderType()) {
                case HOLD, SUPPORT, CONVOY -> true;
                default -> false;
            };

        }

        return recipient.orderType() == OrderType.MOVE
                && Province.canonical(recipient.target())
                == Province.canonical(support.auxiliaryTarget());

    }


    // Finding construction \\

    private TacticMatch finding(
            TacticalContext context,
            Order support,
            Optional<UnitId> referenced
    ) {

        List<TacticMatch.Participant> participants = new ArrayList<>();

        participants.add(new TacticMatch.Participant(
                TacticMatch.Role.SUPPORTER,
                support.unit()));

        if (referenced.isPresent())
            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.REFERENCED_UNIT,
                    referenced.get()));

        Province focus = support.auxiliaryTarget() == null
                ? support.target()
                : support.auxiliaryTarget();

        return new TacticMatch(
                kind(),
                version(),
                context,
                focus,
                participants,
                Set.of());

    }


}