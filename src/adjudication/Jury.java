package adjudication;

import adjudication.util.Dependencies;
import domain.Nation;
import domain.Order;
import domain.OrderType;
import domain.Province;
import adjudication.util.Orders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs conservative ordinary adjudication WITHOUT speculative recursion
 * (and WITHOUT applying the Szykman rule.)
 *
 * <p>`Jury` repeatedly commits only SUCCESS or FAILURE outcomes that can
 * be established from already-known outcomes. A dependency whose result is
 * unknown keeps its consumer UNKNOWN; it is never guessed optimistically or
 * pessimistically (like in `Judge`).</p>
 */
public class Jury implements Resolver {


    private final List<Order> orders;
    private final Map<String, ResolutionState> states;


    public Jury(Collection<Order> submittedOrders) {
        this.orders = new ArrayList<>(submittedOrders);
        this.states = new LinkedHashMap<>();
        for (Order order : this.orders)
            this.states.put(
                    Orders.keyOf(order),
                    ResolutionState.UNKNOWN);
    }


    @Override
    public OrdinaryResolution inquire() {

        boolean changed;

        do {
            changed = false;

            for (Order order : this.orders) {

                if (stateOf(order).isKnown())
                    continue;

                ResolutionState state = evaluate(order);

                if (!state.isKnown())
                    continue;

                this.states.put(Orders.keyOf(order), state);
                changed = true;

            }
        } while (changed);

        List<List<Order>> unresolvedComponents =
                this.findUnresolvedComponents();

        return new OrdinaryResolution(
                this.states,
                unresolvedComponents);

    }


    /**
     * Evaluates one order from currently known facts only.
     */
    protected ResolutionState evaluate(Order order) {

        if (!Orders.orderIsValid(order))
            return ResolutionState.FAILURE;

        return switch (order.orderType) {
            case HOLD -> evaluateHold(order);
            case SUPPORT -> evaluateSupport(order);
            case CONVOY -> evaluateConvoy(order);
            case MOVE -> evaluateMove(order);
            default -> ResolutionState.UNKNOWN;
        };

    }


    /**
     * A HOLD fails only if a known-successful enemy move dislodges it.
     *
     * <p>If an assailant remains unknown, the hold remains unknown rather than
     * being guessed successful.</p>
     */
    protected ResolutionState evaluateHold(Order hold) {

        boolean hasUnknownAssailant = false;

        for (Order assailant : Orders.locateUnitsMovingToPosition(
                                hold.pos0, this.orders)
        ) {

            if (assailant == hold)
                continue;

            ResolutionState assailantState = stateOf(assailant);

            if (assailantState == ResolutionState.SUCCESS)
                return ResolutionState.FAILURE;

            if (assailantState == ResolutionState.UNKNOWN)
                hasUnknownAssailant = true;

        }

        return hasUnknownAssailant
                ? ResolutionState.UNKNOWN
                : ResolutionState.SUCCESS;

    }


    /**
     * A convoy succeeds if every possible assailant is known not to dislodge
     * its fleet. It fails if a known-successful assailant dislodges it.
     *
     * <p>This conservative version intentionally leaves a convoy UNKNOWN when
     * an assailant is UNKNOWN. Later iterations can settle it after tactical
     * moves have been resolved.</p>
     */
    protected ResolutionState evaluateConvoy(Order convoy) {

        if (Orders.locateCorresponding(convoy, this.orders) == null)
            return ResolutionState.FAILURE;

        boolean hasUnknownAssailant = false;

        for (Order assailant :
                Orders.locateUnitsMovingToPosition(
                        convoy.pos0,
                        this.orders
                )) {

            if (assailant == convoy)
                continue;

            ResolutionState assailantState = stateOf(assailant);

            if (assailantState == ResolutionState.SUCCESS)
                return ResolutionState.FAILURE;

            if (assailantState == ResolutionState.UNKNOWN)
                hasUnknownAssailant = true;
        }

        return hasUnknownAssailant
                ? ResolutionState.UNKNOWN
                : ResolutionState.SUCCESS;

    }


    /**
     * Resolves a support when every potential support-cutting move is known.
     *
     * <p>A friendly move cannot cut support. A move directed at the province
     * that the support itself attacks also cannot cut that support.</p>
     */
    protected ResolutionState evaluateSupport(Order support) {

        if (Orders.locateCorresponding(support, this.orders) == null)
            return ResolutionState.FAILURE;

        boolean hasUnknownCutter = false;

        for (Order move : this.orders) {

            if (move.orderType != OrderType.MOVE)
                continue;

            if (!Province.equalsIgnoreCoast(move.pos1, support.pos0))
                continue;

            if (move.owner == support.owner)
                continue;

            if (support.pos2 != null
                    && Province.equalsIgnoreCoast(
                    support.pos2,
                    move.pos0
            )) { continue; }

            /*
             * A move cuts support when it can attack the supporter's province.
             * It does not need to succeed in entering that province.
             *
             * Example: in F29, En A Naf - Cly fails because Cly remains occupied,
             * but its successful MAO convoy route still cuts Ru F Cly S Nwy - NWG.
             */
            if (isOrdinaryPathKnown(move))
                return ResolutionState.FAILURE;

            /*
             * An unresolved move whose path is not yet known may still become a
             * valid support-cutting attack. Do not prematurely validate the support.
             *
             * A known FAILURE is not automatically a possible cutter: it may simply
             * be an invalid move or have no viable route.
             */
            if (stateOf(move) == ResolutionState.UNKNOWN)
                hasUnknownCutter = true;
        }

        return hasUnknownCutter
                ? ResolutionState.UNKNOWN
                : ResolutionState.SUCCESS;

    }


    /**
     * Conservative non-head-to-head move evaluation.
     *
     * <p>The immediate goal is to resolve a move that is decisively blocked by
     * known attack and hold strengths—for example F29's Spa/nc -> MAO attack.
     * Complex head-to-head, convoy-swap, and unresolved-path situations remain
     * UNKNOWN for now.</p>
     */
    protected ResolutionState evaluateMove(Order move) {

        if (!isOrdinaryPathKnown(move))
            return ResolutionState.UNKNOWN;

        Order headToHead = Orders.locateHeadToHead(move, this.orders);

        /*
         * Head-to-head and convoy-swap logic require their own interval-aware
         * implementation. Keep them conservative for this first pass.
         */
        if (headToHead != null)
            return ResolutionState.UNKNOWN;

        StrengthRange attack = attackStrengthRange(move);

        if (attack == null)
            return ResolutionState.UNKNOWN;

        StrengthRange defense = holdStrengthRange(move.pos1);

        if (defense == null)
            return ResolutionState.UNKNOWN;

        /*
         * F29's decisive deduction:
         *
         * Spa -> MAO has at most 2 strength, while MAO has at least 2 defense
         * because Por S MAO H is already known successful. A move must be
         * strictly stronger to succeed, so this failure is non-circular.
         */
        if (attack.cannotBeat(defense))
            return ResolutionState.FAILURE;

        boolean defeatsEveryCompetitor = true;

        for (Order competitor : Orders.locateUnitsMovingToPosition(
                                    move.pos1, this.orders)
        ) {

            if (competitor == move
                    || competitor.orderType != OrderType.MOVE) {
                continue;
            }

            StrengthRange competitorPrevent =
                    preventStrengthRange(competitor);

            if (competitorPrevent == null)
                return ResolutionState.UNKNOWN;

            /*
             * A competitor can prevent this move unless this move's minimum
             * attack is greater than that competitor's maximum prevent
             * strength.
             */
            if (attack.minimum() <= competitorPrevent.maximum()) {
                defeatsEveryCompetitor = false;
                break;
            }

        }

        if (attack.alwaysBeats(defense) && defeatsEveryCompetitor)
            return ResolutionState.SUCCESS;

        return ResolutionState.UNKNOWN;

    }


    /**
     * Returns false when a move's path depends on an unresolved convoy route.
     *
     * <p>Adjacent moves are ordinary and known. A non-adjacent army move needs
     * all potentially relevant convoy orders to be known successful before the
     * probe will call its path known.</p>
     */
    private boolean isOrdinaryPathKnown(Order move) {

        if (!Orders.orderIsValid(move))
            return false;

        if (move.pos0.isAdjacentTo(move.pos1))
            return true;

        if (move.orderType != OrderType.MOVE)
            return false;

        boolean hasMatchingConvoy = false;

        for (Order convoy : this.orders) {

            if (convoy.orderType != OrderType.CONVOY)
                continue;

            if (!sameConvoySpecification(convoy, move))
                continue;

            hasMatchingConvoy = true;

            if (stateOf(convoy) != ResolutionState.SUCCESS)
                return false;
        }

        return hasMatchingConvoy;

    }


    private StrengthRange attackStrengthRange(Order move) {

        StrengthRange supports = moveSupportRange(
                move,
                null);

        Order destinationOccupant = Orders.locateUnitAtPosition(
                move.pos1,
                this.orders);

        StrengthRange strengthRange = new StrengthRange(
                1 + supports.minimum(),
                1 + supports.maximum());

        if (destinationOccupant == null)
            return strengthRange;

        /*
         * A moving destination occupant creates a dependency on whether the unit
         * vacates. Do not guess that fact in this first interval-based probe.
         */
        if (destinationOccupant.orderType == OrderType.MOVE) {
            ResolutionState occupantState = stateOf(destinationOccupant);
            if (occupantState == ResolutionState.UNKNOWN)
                return null;
            if (occupantState == ResolutionState.SUCCESS)
                return strengthRange;
            if (destinationOccupant.owner == move.owner)
                return new StrengthRange(0, 0);
        }

        StrengthRange foreignSupports = moveSupportRange(
                move,
                destinationOccupant.owner);

        return new StrengthRange(
                1 + foreignSupports.minimum(),
                1 + foreignSupports.maximum());

    }


    private StrengthRange preventStrengthRange(Order move) {

        if (!isOrdinaryPathKnown(move))
            return null;

        Order headToHead = Orders.locateHeadToHead(move, this.orders);

        if (headToHead != null) {

            ResolutionState headToHeadState = stateOf(headToHead);

            if (headToHeadState == ResolutionState.UNKNOWN)
                return null;

            if (headToHeadState == ResolutionState.SUCCESS)
                return new StrengthRange(0, 0);

        }

        StrengthRange supports = moveSupportRange(move, null);

        return new StrengthRange(
                1 + supports.minimum(),
                1 + supports.maximum());

    }


    private StrengthRange holdStrengthRange(Province destination) {

        Order occupant = Orders.locateUnitAtPosition(
                destination,
                this.orders);

        if (occupant == null)
            return new StrengthRange(0, 0);

        if (occupant.orderType == OrderType.MOVE) {

            ResolutionState state = stateOf(occupant);

            if (state == ResolutionState.UNKNOWN)
                return null;

            if (state == ResolutionState.SUCCESS)
                return new StrengthRange(0, 0);

        }

        StrengthRange supports = holdSupportRange(occupant);

        return new StrengthRange(
                1 + supports.minimum(),
                1 + supports.maximum());

    }


    /**
     * Counts known successful supports in the lower bound and both successful and
     * unresolved supports in the upper bound.
     *
     * @param forbiddenOwner when non-null, supports from this power are excluded;
     *                       this matches `Judge::tallySuccessfulSupportsForeign(...)`
     */
    private StrengthRange moveSupportRange(
            Order move,
            Nation forbiddenOwner
    ) {

        int minimum = 0;
        int maximum = 0;

        for (Order support : this.orders) {

            if (support.orderType != OrderType.SUPPORT)
                continue;

            if (forbiddenOwner != null
                    && support.owner == forbiddenOwner) {
                continue;
            }

            if (!Province.equalsIgnoreCoast(support.pos1, move.pos0)
                    || !Province.equalsIgnoreCoast(support.pos2, move.pos1)) {
                continue;
            }

            ResolutionState supportState = stateOf(support);

            if (supportState == ResolutionState.SUCCESS) {
                minimum++;
                maximum++;
            } else if (supportState == ResolutionState.UNKNOWN) {
                maximum++;
            }

        }

        return new StrengthRange(minimum, maximum);

    }


    private StrengthRange holdSupportRange(Order occupant) {

        int minimum = 0;
        int maximum = 0;

        for (Order support : this.orders) {

            if (support.orderType != OrderType.SUPPORT)
                continue;

            if (!Province.equalsIgnoreCoast(support.pos1, occupant.pos0)
                    || support.pos2 != null) {
                continue;
            }

            ResolutionState supportState = stateOf(support);

            if (supportState == ResolutionState.SUCCESS) {
                minimum++;
                maximum++;
            } else if (supportState == ResolutionState.UNKNOWN) {
                maximum++;
            }

        }

        return new StrengthRange(minimum, maximum);

    }


    private List<List<Order>> findUnresolvedComponents() {

        List<List<Order>> unresolvedComponents = new ArrayList<>();

        for (List<Order> component :
                Dependencies.partition(this.orders)) {

            List<Order> unresolved = new ArrayList<>();

            for (Order order : component) {
                if (stateOf(order) == ResolutionState.UNKNOWN)
                    unresolved.add(order);
            }

            if (!unresolved.isEmpty())
                unresolvedComponents.add(unresolved);

        }

        return unresolvedComponents;

    }


    private ResolutionState stateOf(Order order) {
        return this.states.getOrDefault(
                Orders.keyOf(order),
                ResolutionState.UNKNOWN);
    }

    private static boolean sameConvoySpecification(
            Order convoy,
            Order move
    ) {
        return Province.equalsIgnoreCoast(convoy.pos1, move.pos0)
                && Province.equalsIgnoreCoast(convoy.pos2, move.pos1);
    }


}