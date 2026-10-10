package analysis.tactics.detective;

import adjudication.util.Orders;
import analysis.tactics.Detective;
import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;
import domain.Province;
import phase.Order;

import java.util.*;


/**
 * Recognizes submissions rejected by JReferee's static validity checks.
 *
 * <p>The category name "bogus moves" includes statically invalid movement-
 * phase submissions such as impossible support and invalid convoy issuers.
 * It is not restricted to the MOVE order type.</p>
 *
 * <p>The existing static validator is deliberately reused. A negative
 * finding does not establish full legality: convoy availability, some route
 * constraints, support correspondence, and dynamic outcomes are separate.</p>
 */
public final class BogusMovesDetective extends Detective {


    // Constants \\

    private static final String VERSION = "static-validity-v1";


    // Construction \\

    public BogusMovesDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.BOGUS_MOVES;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order order = evidence.order();

            if (!isBogus(context, order))
                continue;

            /*
             * Focus on the submitting unit. Its attempted targets remain
             * available through the original context and territory queries.
             */
            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    context.board().locationOf(order.unit()),
                    List.of(new TacticMatch.Participant(
                            TacticMatch.Role.ISSUER,
                            order.unit())),
                    Set.of()));

        }

        return findings;

    }


    // Shared static classification \\

    /**
     * Returns whether the existing static validator rejects this submission.
     *
     * <p>Package-private so other detectives can exclude the same category
     * without maintaining a second, potentially divergent rule set.</p>
     *
     * <p>Requires an active issuer. TacticalContext already ensures that
     * submissions in its known-order map have active issuers and valid
     * field structure.</p>
     */
    static boolean isBogus(TacticalContext context, Order order) {

        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(order, "order");

        Province location = context.board().locationOf(order.unit());

        if (location == null)
            throw new IllegalArgumentException(
                    "Cannot inspect a submission from an inactive unit");

        adjudication.Order candidate =
                new adjudication.Order(order, location);

        return !Orders.orderIsValid(candidate);

    }


}