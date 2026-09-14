package adjudication;

import adjudication.util.Dependencies;
import domain.*;
import adjudication.util.Convoys;
import adjudication.util.Orders;

import java.util.*;

/**
 * The `Judge` class holds a Collection of Orders, and contains the Adjudication & Resolution logic required to process them all in 1 sequence:
 *      see `Judge.judge(...)`<br><br>
 *
 * Utilizes a duplex recursive algorithm, where `resolve(...)` handles dependency logic i.e. <i>"resolution via deduction"</i>,
 *      and `adjudicate(...)` handles board logic i.e. <i>"resolution via force"</i>
 *
 * @author Evan B
 */
public class Judge implements Adjudicator, ParadoxTransparent {


    // Constants \\

    //public static final boolean DEBUG_PRINT = true;


    // The adjudication program needs to handle the following situations:
        // a. An order that is not indirectly dependent on itself
        // b. An order that is indirectly dependent on itself, but there is still exactly 1 resolution
        // c. An order that is indirectly dependent on itself, but there are 0 or 2 possible resolutions


    // Core state \\

    protected Collection<Order> orders;


    // Resolver state and cycle diagnostics \\

    /*
     * Global vars for the `resolve()` func:</u><br>
     *      ~ <i>(List of Orders)</i> `<i><b>cycle</b></i>` contains the contents of a recursion cycle, if it exists (empty otherwise)<br>
     *      ~ <i>int</i> `<i><b>recursionHits</b></i>` represents the cyclic dependency depth<br>
     *      ~ <i>bool</i> `<i><b>uncertain</b></i>` is the "guessing variable" --
     *          when true, indicates resolve() returns a result based on uncertain information
     *          ... (i.e. is guessing)
     */
    private List<Order> cycle = new ArrayList<>();
    private int recursionHits = 0;
    private boolean uncertain = false;


    // Clears at the beginning of each run of `judge()`
    private final List<ParadoxCycle> detectedParadoxCycles = new ArrayList<>();

    /*
     * The actual active resolveResult(...) call chain.
     *
     * This is separate from `cycle`, whose contents are part of the 'legacy'
     * Kruijswijk resolution-control algorithm. This stack exists only to capture
     * the exact active dependency slice when recursion revisits an Order.
     */
    private final List<Order> resolutionStack = new ArrayList<>();

    /*
     * Root context for the current `Judge.judge()` invocation.
     *
     * At this stage it is always empty. It is threaded through recursive calls
     * so a later change can safely create branch-local contexts.
     */
    private ResolutionContext rootContext = ResolutionContext.empty();
    
    
    // Constructors \\

    public Judge() {
        this.orders = new ArrayList<>();
    }

    public Judge(Collection<Order> orders) {
        this.orders = orders;
    }


    // Public accessors \\

    @Override
    public Collection<Order> getOrders() {
        return orders;
    }

    /**
     * Returns recursive dependency cycles detected during the most recent
     * {@link #judge()} invocation.
     */
    //public Collection<ParadoxCycle> getParadoxCycles() {
    @Override
    public List<ParadoxCycle> getParadoxCycles() {  // changed to List to preserve ordering
        return Collections.unmodifiableList(
                new ArrayList<>(this.detectedParadoxCycles));
    }


    // `judge()` method \\

    /**
     * Definitively resolves the Collection of Orders `orders`.<br><br>
     *
     * Will handle some paradoxes, but not the more complex ones.<br>
     * For more sophisticated paradox handling, using `Justice.java`<br><br>
     *
     * Acquires Orders' resolution 'verdicts' by calling top-level `resolve(...)` 3x per Order:<br>
     *      ~ 1st Mass-Resolve: sets each `order.verdict` to the output of the call `resolve(order, optimistic=true)`<br>
     *      ~ 2nd (& 3rd) Mass-Resolve: does not directly set `order.verdict`, but still calls `resolve(order, optimistic=true)` for each order
     *
     * @postcondition Every order in `orders` is definitively resolved and has a verdict<br>
     *                (Note: This should be enough information to infer dislodgement status)
     *
     * @author Evan B
     */
    @Override
    public void judge() {

        if (this.judgeComponents())
            return;

        // reset meta resolve values
        this.cycle = new ArrayList<>();
        this.recursionHits = 0;
        this.uncertain = false;

        this.detectedParadoxCycles.clear();
        this.resolutionStack.clear();

        this.rootContext = ResolutionContext.empty();

        //      DEFAULT IMPLEMENTATION:     \\
        // [1 Hard Resolve + 2 Soft Resolve]

        Collection<Order> ordersCopy = new ArrayList<>(Orders.deepCopy(this.orders));

        // 1st run :: HARD RESOLVE
        for (Order order : orders)
            order.verdict = resolve(order, true, this.rootContext);

        // 2nd run :: SOFT RESOLVE
        for (Order order : orders)
            resolve(order, true, this.rootContext);

        // Detect Szykman rule overriding an Order(s) with HOLDs,
        // ... in this case, run through the judging process again
        if (!Orders.uniq(List.of(ordersCopy, this.orders)).isEmpty()) {

            for (Order order : orders)
                order.wipeMetaInf();

            // 1st run :: HARD RESOLVE
            for (Order order : orders)
                order.verdict = resolve(order, true, this.rootContext);

            // 2nd run :: SOFT RESOLVE
            for (Order order : orders)
                resolve(order, true, this.rootContext);
        }

        // 3rd run :: SOFT RESOLVE
        for (Order order : orders)
            resolve(order, true, this.rootContext);

    }


    // Component handling \\

    /**
     * Resolves independent dependency components (based on `ParadoxCycle`s)
     * through separate `Judge` instances.
     * Each component preserves the legacy shared recursive state internally,
     * while unrelated paradoxes cannot corrupt one another's bookkeeping `cycle`.<br><br>
     *
     * The component lists contain the original Order instances,
     * so each child `Judge` mutates the same orders retained by this `Judge`.
     *
     * @return True when this `Judge` delegated to multiple component `Judge`s
     */
    private boolean judgeComponents() {

        List<List<Order>> components =
                Dependencies.partition(this.orders);

        // Single Component
        if (components.size() <= 1)
            return false;

        this.detectedParadoxCycles.clear();
        this.resolutionStack.clear();

        // Multiple components
        for (List<Order> component : components) {

            Judge componentJudge = new Judge(component);
            componentJudge.judge();

            for (ParadoxCycle detectedCycle :
                    componentJudge.getParadoxCycles()) {

                boolean alreadyKnown = false;
                for (ParadoxCycle existingCycle :
                        this.detectedParadoxCycles) {
                    if (existingCycle.key().equals(detectedCycle.key())) {
                        alreadyKnown = true;
                        break;
                    }
                }
                if (!alreadyKnown)
                    this.detectedParadoxCycles.add(detectedCycle);

            }

        }

        return true;

    }


    // `adjudicate(...)` method(s) \\

    /**
     * Compatibility wrapper.<br><br>
     *
     * This overload exists for `ResolutionContext`
     */
    protected boolean adjudicate(Order order, boolean optimistic) {
        return adjudicate(order, optimistic, this.rootContext);
    }

    /**
     * Performs the necessary adjudication equations to resolve an Order.<br><br>
     *
     * Does not know anything about Order states;<br>
     * Instead, calls `resolve(order2, ...)` to determine whether an Order succeeds or fails.<br>
     * These calls may return guess-based results, but this is of no concern to the `adjudicate` function.
     *
     * @param order Order to adjudicate
     * @param optimistic Caller's `optimistic` bool -- which `adjudicate` may sometimes flip (for "opponents") in subsequent calls
     * @return True if `order` is logistically successful, potentially based on `resolve(...)` guesswork -- false otherwise
     *
     * @author Evan B
     */
    protected boolean adjudicate(
            Order order,
            boolean optimistic,
            ResolutionContext context
    ) {

        // Handle MOVE orders
        if (order.orderType == OrderType.MOVE) {

            int attackStrength;

            Order headToHead = Orders.locateHeadToHead(order, this.orders);

            // HEAD-TO-HEAD Battle
            if (headToHead != null
                    && !isHeadToHeadSuppressed(order, context)) {

                // Calculate Move order's ATTACK STRENGTH
                // [Must be greater than... a. the Defend Strength of the opposing mover, and
                //                          b. the Prevent Strength of all movers competing for the same area]
                attackStrength = calculateAttackStrength(
                        order,
                        optimistic,
                        true,
                        orders,
                        context
                );

                // Calculate opponent's DEFEND STRENGTH
                int opponentDefendStrength = calculateDefendStrength(
                        headToHead,
                        optimistic,
                        orders,
                        context
                );

                Collection<Order> otherOpponents =
                        Orders.locateUnitsMovingToPosition(order.pos1, orders);

                if (attackStrength > opponentDefendStrength) {

                    // Move is completely unopposed
                    if (otherOpponents.size() <= 1)
                        return true;

                    // Calculate PREVENT STRENGTH of all 'opponents' (other movers going to the same destination)
                    // returns true if our Move order is the greatest (with no ties)
                    return champion(
                            order,
                            attackStrength,
                            optimistic,
                            otherOpponents,
                            context
                    );

                } else if (Orders.adjacentMatchingConvoyFleetExists(order, orders)
                        || Orders.adjacentMatchingConvoyFleetExists(headToHead, orders)) {

                    // Lost to opponent mover, the move will fail unless there are Convoy-Swap hijinx

                    // Test for Convoy-Swaps (very specific edge case)
                    // Convoy-Swaps will succeed if-and-only-if all below conditions are met:
                    //      1) The other move EITHER succeeds on its own merits, OR we deduce the H2H Battle restrictions could be sabotaging the other move's success verdict
                    //      2) Our principal Order "is champion"; beats out all other attacks to its destination
                    //      3) The other move is ALSO champion of its destination
                    //      4) The Convoy Path is successful, or another one can be found

                    Collection<Order> convoyOrders1 = Orders.pruneForOrderType(
                            OrderType.CONVOY,
                            Orders.locateCorresponding(order, true, orders)
                    );

                    Collection<Order> convoyOrders2 = Orders.pruneForOrderType(
                            OrderType.CONVOY,
                            Orders.locateCorresponding(headToHead, true, orders)
                    );

                    boolean convoyPath1Successful = convoyPathSuccessful(
                            order,
                            optimistic,
                            convoyOrders1,
                            context
                    );

                    boolean convoyPath2Successful = convoyPathSuccessful(
                            headToHead,
                            optimistic,
                            convoyOrders2,
                            context
                    );

                    boolean otherMoveSuccessful = resolve(
                            headToHead,
                            optimistic,
                            context
                    );

                    //int destHoldStrength = calculateHoldStrength(order.pos1, optimistic, orders);
                    int disguisedHeadToHeadAttackStrength = calculateAttackStrength(
                            headToHead,
                            optimistic,
                            false,
                            orders,
                            context
                    );

                    int currentHeadToHeadAttackStrength = calculateAttackStrength(
                            headToHead,
                            optimistic,
                            true,
                            orders,
                            context
                    );

                    int headToHeadAttackStrengthDiscrepancy =
                            disguisedHeadToHeadAttackStrength
                                    - currentHeadToHeadAttackStrength;

                    boolean swapSuccess =
                            (otherMoveSuccessful
                                    || headToHeadAttackStrengthDiscrepancy > 0)
                                    && (convoyPath1Successful || convoyPath2Successful)
                                    && champion(
                                    order,
                                    attackStrength,
                                    optimistic,
                                    otherOpponents,
                                    context
                            )
                                    && champion(
                                    headToHead,
                                    disguisedHeadToHeadAttackStrength,
                                    optimistic,
                                    Orders.locateUnitsMovingToPosition(
                                            headToHead.pos1,
                                            orders
                                    ),
                                    context
                            );

                    // If the Convoy-Swap appears successful, force the `headToHead` Order to reevaluate...
                    // ... going down the NON-HEAD-TO-HEAD tree (via a SPECIAL FLAG `Order.suppressH2HAdjudication` (wysiwyg)) ...
                    // Why?
                    //      A) this H2H battle is no longer an obstacle
                    //      B) there can only be one H2H battle per (set of) Order(s)
                    // For good measure, also tick our principal Order's `suppressH2HAdjudication` flag
                    // Note: THIS IS A 'SHORTCUT' AND VIOLATES THE DIVISION OF RESPONSIBILITY BTWN. `ADJUDICATE()` AND `RESOLVE()`
                    if (swapSuccess) {
                        /*
                         * Treat the successful convoy swap as a branch-local interpretation.
                         * Both participating moves are no longer ordinary head-to-head opponents.
                         */
                        ResolutionContext swapContext =
                                context.withHeadToHeadSuppressed(order, headToHead);

                        /*
                         * The legacy Order flags remain temporarily. They preserve
                         * the original implementation's behavior while the context
                         * infrastructure is being introduced.
                         */
                        headToHead.resolved = false;
                        headToHead.suppressH2HAdjudication = true;
                        order.suppressH2HAdjudication = true;

                        resolve(headToHead, optimistic, swapContext);
                    }

                    return swapSuccess;

                } else {  // Cannot overwhelm nor swap with the Head-to-Head adversary
                    return false;
                }

            }

            // NON-HEAD-TO-HEAD Battle
            else {

                // Check if we have arrived here via CONVOY SWAP hijinx (rare edge case)...
                // If we have, we must re-evaluate all orders adjacent to the swap...
                // ... (i.e. what WOULD be the head-to-head order)
                if (headToHead != null) {
                    Collection<Order> otherOpponents =
                            Orders.locateUnitsMovingToPosition(
                                    headToHead.pos1,
                                    orders
                            );

                    for (Order order2 : otherOpponents)
                        order2.resolved = false;

                    for (Order order2 : otherOpponents)
                        resolve(order2, optimistic, context);
                }

                // Calculate Move order's ATTACK STRENGTH
                // [Must be greater than... a. the Hold Strength of the area, and
                //                          b. the Prevent Strength of all movers competing for the same area]
                attackStrength = calculateAttackStrength(
                        order,
                        optimistic,
                        false,
                        orders,
                        context
                );

                // Calculate destination's HOLD STRENGTH
                int destHoldStrength = calculateHoldStrength(
                        order.pos1,
                        optimistic,
                        orders,
                        context
                );

                Collection<Order> otherOpponents =
                        Orders.locateUnitsMovingToPosition(order.pos1, orders);

                if (attackStrength > destHoldStrength) {

                    // Calculate PREVENT STRENGTH of all 'opponents' (other movers going to the same destination)
                    // returns true if our Move order is the greatest (with no ties)
                    return champion(
                            order,
                            attackStrength,
                            optimistic,
                            otherOpponents,
                            context
                    ) && (
                            !Orders.adjacentMatchingConvoyFleetExists(order, orders)
                                    || pathSuccessful(
                                    order,
                                    optimistic,
                                    orders,
                                    context
                            )
                    );

                } else {  // Lost on hold strength, return false
                    return false;
                }

            }

        }

        // Handle SUPPORT orders
        else if (order.orderType == OrderType.SUPPORT) {

            // SUPPORTS WILL FAIL WITHOUT A CORRESPONDING ORDER
            if (Orders.locateCorresponding(order, orders) == null)
                return false;

            for (Order order2 : orders) {

                if (order2.equals(order) || order2.orderType != OrderType.MOVE)
                    continue;

                // .equalsIC() is used b/c supports can be cut from either coast
                if (!Province.equalsIgnoreCoast(order2.pos1, order.pos0))
                    continue;

                if (pathSuccessful(order2, optimistic, orders, context)
                        && order2.owner != order.owner
                        && order.pos2 != order2.pos0) {
                    return false;
                } else if (resolve(order2, !optimistic, context)) {
                    return false;
                }

            }

            return true;

        }

        // Handle CONVOYS
        else if (order.orderType == OrderType.CONVOY) {

            // CONVOYS WILL FAIL WITHOUT A CORRESPONDING ORDER
            if (Orders.locateCorresponding(order, orders) == null)
                return false;

            // CONVOYS WILL FAIL IF ORDER IS DEEMED INVALID
            if (!Orders.orderIsValid(order))
                return false;

            Collection<Order> assailants =
                    Orders.locateUnitsMovingToPosition(order.pos0, orders);

            for (Order assailant : assailants) {
                if (assailant.equals(order))
                    continue;

                if (resolve(assailant, !optimistic, context)) {

                    Order matchingMoveOrder =
                            Orders.locateCorresponding(order, this.orders);

                    if (matchingMoveOrder != null) {
                        if (!matchingMoveOrder.pos0.isAdjacentTo(
                                matchingMoveOrder.pos1
                        )) {
                            // There exists a Move to & from non-adjacent squares that matches this convoy's specifications,
                            // and this convoy is now dislodged...
                            // Therefore, force `matchingMoveOrder` to reevaluate!
                            // Note: THIS IS A 'SHORTCUT' AND VIOLATES THE DIVISION OF RESPONSIBILITY BTWN. `ADJUDICATE()` AND `RESOLVE()`
                            matchingMoveOrder.resolved = false;
                            resolve(
                                    matchingMoveOrder,
                                    optimistic,
                                    context
                            );
                        }
                    }

                    return false;

                }
            }

            return true;

        }

        // Handle HOLDS
        else if (order.orderType == OrderType.HOLD) {

            Collection<Order> assailants =
                    Orders.locateUnitsMovingToPosition(order.pos0, orders);

            for (Order assailant : assailants) {
                if (assailant.equals(order))
                    continue;

                if (resolve(assailant, !optimistic, context))
                    return false;
            }

            return true;

        }

        else if (order.orderType == null) {
            throw new IllegalStateException(String.format(
                    "`%s:adjudicate(...)` - `null` OrderType: only Spring & Fall Orders are directly handled by `%s`:\t(%s, %s, %s, %s)\n",
                    this.getClass().getSimpleName(),
                    this.getClass().getSimpleName(),
                    OrderType.MOVE.name(),
                    OrderType.HOLD.name(),
                    OrderType.SUPPORT.name(),
                    OrderType.CONVOY.name()
            ));
        }

        else {
            throw new IllegalStateException(String.format(
                    "`%s:adjudicate(...)` - Impossible OrderType \"%s\": only Spring & Fall Orders are directly handled by `%s`:\t(%s, %s, %s, %s)\n",
                    this.getClass().getSimpleName(),
                    order.orderType,
                    this.getClass().getSimpleName(),
                    OrderType.MOVE.name(),
                    OrderType.HOLD.name(),
                    OrderType.SUPPORT.name(),
                    OrderType.CONVOY.name()
            ));
        }

    }


    // `resolve(...)` method(s) \\

    /**
     * Compatibility wrapper.<br><br>
     *
     * Internal Judge code should use the overload that accepts
     * ResolutionContext.
     */
    private boolean resolve(Order order, boolean optimistic) {
        return resolve(order, optimistic, this.rootContext);
    }

    /**
     * Transitional boolean API.<br><br>
     *
     * Existing adjudication logic still needs only a success/failure value. The
     * ResolutionResult overload preserves whether that value was committed or
     * merely guessed in a recursive dependency cycle.
     */
    private boolean resolve(
            Order order,
            boolean optimistic,
            ResolutionContext context
    ) {
        return resolveResult(order, optimistic, context).isSuccessful();
    }

    /**
     * Resolves an Order while preserving whether the returned value is
     * definitive or provisional.
     *
     * @param order Order to resolve
     * @param optimistic Whether to resolve (& adjudicate) for the best-case or worst-case of `order`
     * @param context Temporary, branch-local resolver context
     * @return A definitive result if persisted to the Order; otherwise a provisional recursive guess
     */
    private ResolutionResult resolveResult(
            Order order,
            boolean optimistic,
            ResolutionContext context
    ) {

        if (order.resolved) {
            // Resolution already exists and is definitive.
            return ResolutionResult.definitive(order.verdict);
        }

        if (cycle.contains(order)) {
            // We already concluded this order is in a cycle,
            // ... which we cannot yet resolve.
            uncertain = true;

            // Success if optimistic, but this is only a recursive guess.
            return ResolutionResult.provisional(optimistic);
        }

        if (order.visited) {
            /*
             * We hit an Order already active in this recursive call chain.
             * Record the active-stack slice for Justice diagnostics before the legacy
             * cycle bookkeeping changes it.
             */
            this.recordDetectedParadoxCycle(order);

            // Legacy resolution-control behavior remains unchanged.
            cycle.add(order);
            recursionHits++;
            uncertain = true;

            // Success if optimistic, but this is only a recursive guess.
            return ResolutionResult.provisional(optimistic);
        }

        order.visited = true;  // Prevent endless recursion; block from recursing to self
        this.resolutionStack.add(order);

        int cycleLen_Old = cycle.size();
        int recursionHits_Old = recursionHits;
        boolean uncertain_Old = uncertain;

        uncertain = false;

        boolean optResult = this.adjudicate(order, true, context);
        boolean pesResult;

        // Try to avoid a 2nd adjudication for performance
        if (optResult && uncertain)
            pesResult = this.adjudicate(order, false, context);
        else
            pesResult = optResult;

        //pesResult = this.adjudicate(order, false);

        int stackLastIndex = this.resolutionStack.size() - 1;

        if (stackLastIndex < 0 || this.resolutionStack.get(stackLastIndex) != order) {
            throw new IllegalStateException(
                    "Resolution stack corruption while leaving: " + order);
        }

        this.resolutionStack.remove(stackLastIndex);
        order.visited = false;  // Un-block recursion for this Order

        if (optResult == pesResult) {
            // We have a single resolution
            // Delete any cycle info that was found in recursion
            if (cycleLen_Old >= cycle.size())
                cycle.clear();
            else
                cycle.subList(0, cycleLen_Old).clear();

            recursionHits = recursionHits_Old;

            // The uncertain variable must be unaltered, because the order is now resolved
            uncertain = uncertain_Old;

            // Store the result and return it
            order.verdict = optResult;
            order.resolved = true;

            return ResolutionResult.definitive(optResult);
        }

        if (cycle.contains(order)) {
            // We returned from recursion, where this order hit the cycle,
            // ... and we didn't receive any resolution
            recursionHits--;
        }

        if (recursionHits == recursionHits_Old) {
            // We have sufficiently retreated from recursion such that ...
            // ... this order is the ancestor of the whole cycle
            // Apply the backup rule on all orders in the cycle
            this.backupRule(cycle.subList(cycleLen_Old, cycle.size()));
            cycle.subList(0, cycleLen_Old).clear();
            uncertain = uncertain_Old;

            // The backup rule may not have resolved THIS order
            return this.resolveResult(order, optimistic, context);

        } else {
            // We returned from a situation where a cycle was detected
            // However, this order is not the ancestor of the whole cycle
            // We further retreat from recursion
            if (!cycle.contains(order))
                cycle.add(order);

            return ResolutionResult.provisional(optimistic);
        }

    }

    /**
     * Captures the active call-stack segment that starts with `revisitedOrder`.<br><br>
     *
     * Identity comparison is required: Order.equals() may compare submitted-order
     * fields and is not a safe test for locating a specific object on the active
     * recursive stack.
     */
    private void recordDetectedParadoxCycle(Order revisitedOrder) {

        int cycleStart = -1;

        for (int i = 0; i < this.resolutionStack.size(); i++) {
            if (this.resolutionStack.get(i) == revisitedOrder) {
                cycleStart = i;
                break;
            }
        }

        if (cycleStart < 0)
            return;

        ParadoxCycle detectedCycle = new ParadoxCycle(
                this.resolutionStack.subList(
                        cycleStart,
                        this.resolutionStack.size()
                )
        );

        for (ParadoxCycle existingCycle : this.detectedParadoxCycles) {
            if (existingCycle.key().equals(detectedCycle.key()))
                return;
        }

        this.detectedParadoxCycles.add(detectedCycle);
    }


    // Cycle backup and Szykman handling \\

    /**
     * Subroutine of `resolve(...)`, handles cyclical Order dependencies.<br><br>
     *
     * These dependencies may EITHER be comprised of all Move Orders, in which case, all Orders are forced through as `resolved = true` and `verdict = true`,<br>
     * ... OR there are Convoy orders present in the chain, in which case, call the Szykman Rule method / subroutine (force all paradoxical Convoys to hold).
     *
     * @param cyclicalOrders List of cyclic Order dependencies
     *
     * @author algorithm by Lucas B. Kruijswijk
     * @author implementation by Evan B
     */
    private void backupRule(List<Order> cyclicalOrders) {

        boolean areAllMovers = true;

        for (Order order : cyclicalOrders) {
            if (order.orderType != OrderType.MOVE
                    && order.orderType != OrderType.RETREAT) {
                areAllMovers = false;
                break;
            }
        }

        if (areAllMovers) {
            for (Order order : cyclicalOrders) {
                order.resolved = true;
                order.verdict = true;
            }
        } else {
            szykmanRule(cyclicalOrders);
        }

    }

    /**
     * Subroutine of `backupRule(...)`, handles paradoxical Convoy situations by applying the Szykman Rule.<br><br>
     *
     * <i><u>Mutator function!</u></i><br><br>
     *
     * Szykman Rule definition: "All Convoy orders in the paradoxical convoy situation are forced to hold"
     *
     * @param cyclicalOrders List of cyclic Order dependencies
     */
    private void szykmanRule(List<Order> cyclicalOrders) {

        for (Order order : cyclicalOrders) {

            if (order.orderType == OrderType.CONVOY) {

                // take a copy of the original, since the adjudication / resolution process changes the order
                order.takeSnapshot();

                //order.resolved = true;
                //order.verdict = false;
                order.pos1 = null;
                order.pos2 = null;
                order.orderType = OrderType.HOLD;
            }
        }
    }


    // Movement and convoy-path helpers \\

    /**
     * Compatibility overload for subclasses and callers that do not yet pass
     * a branch-local ResolutionContext.
     */
    protected boolean pathSuccessful(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> orders
    ) {
        return pathSuccessful(moveOrder, optimistic, orders, this.rootContext);
    }

    /**
     * Adjudication subroutine which returns true if a given Move Order can successfully reach its destination.
     *
     * @param moveOrder Move Order whose path to test
     * @param optimistic Whether to resolve (& adjudicate) for the best-case or worst-case of `moveOrder`
     * @param orders Collection of all Orders to test against
     * @param context Temporary, branch-local resolver context
     * @return Whether `moveOrder`'s path is successful; `moveOrder` touches its destination
     */
    protected boolean pathSuccessful(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        if (moveOrder.orderType != OrderType.MOVE) {
            throw new IllegalArgumentException(String.format(
                    "Non-Move Order supplied for `pathSuccessful(...)`: %s",
                    moveOrder
            ));
        }

        // Below will implicitly reject pos0->pos0 moves, among other invalid orders
        if (!Orders.orderIsValid(moveOrder))
            return false;

        boolean isConvoyingArmy =
                moveOrder.unitType == UnitType.ARMY
                        && Orders.adjacentMatchingConvoyFleetExists(moveOrder, orders);

        boolean isCoastCrawlingFleet =
                moveOrder.unitType == UnitType.FLEET
                        && moveOrder.pos0.geography == Geography.COASTAL
                        && moveOrder.pos1.geography == Geography.COASTAL;

        if (isConvoyingArmy) {

            Collection<Order> convoyOrders =
                    Orders.pruneForOrderType(OrderType.CONVOY, orders);

            List<Order> convoyPath =
                    Convoys.drawConvoyPath(moveOrder, convoyOrders);

            List<Order> unsuccessfulConvoys = new ArrayList<>();

            for (Order convoyOrder : convoyPath) {
                if (!resolve(convoyOrder, optimistic, context))
                    unsuccessfulConvoys.add(convoyOrder);
            }

            if (unsuccessfulConvoys.isEmpty()) {
                return true;

            } else {

                convoyOrders.removeAll(unsuccessfulConvoys);
                convoyPath = Convoys.drawConvoyPath(moveOrder, convoyOrders);

                for (;
                     !convoyPath.isEmpty()
                             && !Convoys.convoyPathIsValid(moveOrder, convoyPath);
                     convoyPath = Convoys.drawConvoyPath(moveOrder, convoyOrders)) {

                    unsuccessfulConvoys.clear();

                    for (Order convoyOrder : convoyPath) {
                        if (!resolve(convoyOrder, optimistic, context))
                            unsuccessfulConvoys.add(convoyOrder);
                    }

                    if (unsuccessfulConvoys.isEmpty())
                        return true;
                    else
                        convoyOrders.removeAll(unsuccessfulConvoys);
                }

                // No convoy path available -- only the land route
                return moveOrder.pos0.isAdjacentTo(moveOrder.pos1);
            }

        } else if (isCoastCrawlingFleet) {

            // Fleets cannot convoy, so they must be literally adjacent
            if (!moveOrder.pos0.isAdjacentTo(moveOrder.pos1))
                return false;

            return Province.adjacentBySea(moveOrder.pos0, moveOrder.pos1);

        } else {

            // Armies cannot traverse split coast Provinces
            if (moveOrder.unitType == UnitType.ARMY
                    && moveOrder.pos1.coastType == CoastType.SPLIT) {
                return false;
            }

            return moveOrder.pos0.isAdjacentTo(moveOrder.pos1);
        }
    }


    protected boolean convoyPathSuccessful(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> convoyOrders
    ) {
        return convoyPathSuccessful(
                moveOrder,
                optimistic,
                convoyOrders,
                this.rootContext
        );
    }

    // TODO: JDocs
    protected boolean convoyPathSuccessful(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> convoyOrders,
            ResolutionContext context
    ) {

        if (convoyOrders.isEmpty())
            return false;

        List<Order> convoyPath =
                Convoys.drawConvoyPath(moveOrder, convoyOrders);

        List<Order> unsuccessfulConvoys = new ArrayList<>();

        for (Order convoyOrder : convoyPath) {
            if (!resolve(convoyOrder, optimistic, context))
                unsuccessfulConvoys.add(convoyOrder);
        }

        if (unsuccessfulConvoys.isEmpty()) {
            return true;

        } else {

            convoyOrders.removeAll(unsuccessfulConvoys);
            convoyPath = Convoys.drawConvoyPath(moveOrder, convoyOrders);

            for (;
                 !convoyPath.isEmpty()
                         && !Convoys.convoyPathIsValid(moveOrder, convoyPath);
                 convoyPath = Convoys.drawConvoyPath(moveOrder, convoyOrders)) {

                unsuccessfulConvoys.clear();

                for (Order convoyOrder : convoyPath) {
                    if (!resolve(convoyOrder, optimistic, context))
                        unsuccessfulConvoys.add(convoyOrder);
                }

                if (unsuccessfulConvoys.isEmpty())
                    return true;
                else
                    convoyOrders.removeAll(unsuccessfulConvoys);
            }

            // No convoy path available
            return false;
        }
    }


    // Support and strength helpers \\

    protected int tallySuccessfulSupports(
            Order order,
            boolean optimistic,
            Collection<Order> orders
    ) {
        return tallySuccessfulSupports(
                order,
                optimistic,
                orders,
                this.rootContext
        );
    }

    /**
     * Count & return the # of successful Support Orders attributed to a given Order.
     */
    protected int tallySuccessfulSupports(
            Order order,
            boolean optimistic,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        int supports = 0;

        if (order.orderType == OrderType.MOVE) {  // SUPPORT to MOVE

            // Invalid / illegal moves cannot receive support
            if (!Orders.orderIsValid(order))
                return 0;

            for (Order order2 : orders) {

                if (order2.equals(order)
                        || order2.orderType != OrderType.SUPPORT) {
                    continue;
                }

                // Invalid / illegal supports do not count
                if (!Orders.orderIsValid(order2))
                    continue;

                if (order2.pos1 == order.pos0
                        && order2.pos2 == order.pos1) {
                    if (resolve(order2, optimistic, context))
                        supports++;
                }
            }

        } else {  // SUPPORT to HOLD

            for (Order order2 : orders) {

                if (order2.equals(order)
                        || order2.orderType != OrderType.SUPPORT) {
                    continue;
                }

                if (order2.pos1 == order.pos0
                        && order2.pos2 == null) {
                    if (resolve(order2, optimistic, context))
                        supports++;
                }
            }
        }

        return supports;
    }


    protected int tallySuccessfulSupportsForeign(
            Order order,
            boolean optimistic,
            Nation forbiddenOwner,
            Collection<Order> orders
    ) {
        return tallySuccessfulSupportsForeign(
                order,
                optimistic,
                forbiddenOwner,
                orders,
                this.rootContext
        );
    }

    /**
     * Count & return the # of successful Support Orders attributed to a given
     * Order that are NOT coming from a given Nation.
     */
    protected int tallySuccessfulSupportsForeign(
            Order order,
            boolean optimistic,
            Nation forbiddenOwner,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        int supports = 0;

        if (order.orderType == OrderType.MOVE) {  // SUPPORT to MOVE

            // Invalid / illegal moves cannot receive support
            if (!Orders.orderIsValid(order))
                return 0;

            for (Order order2 : orders) {

                if (order2.equals(order)
                        || order2.orderType != OrderType.SUPPORT
                        || order2.owner == forbiddenOwner) {
                    continue;
                }

                // Invalid / illegal supports do not count
                if (!Orders.orderIsValid(order2))
                    continue;

                if (order2.pos1 == order.pos0
                        && order2.pos2 == order.pos1) {
                    if (resolve(order2, optimistic, context))
                        supports++;
                }
            }

        } else {  // SUPPORT to HOLD

            for (Order order2 : orders) {

                if (order2.equals(order)
                        || order2.orderType != OrderType.SUPPORT
                        || order2.owner == forbiddenOwner) {
                    continue;
                }

                if (order2.pos1 == order.pos0
                        && order2.pos2 == null) {
                    if (resolve(order2, optimistic, context))
                        supports++;
                }
            }
        }

        return supports;
    }


    protected boolean champion(
            Order moveOrder,
            int attackStrength,
            boolean optimistic,
            Collection<Order> opponents
    ) {
        return champion(
                moveOrder,
                attackStrength,
                optimistic,
                opponents,
                this.rootContext
        );
    }

    /**
     * Calculate the Prevent Strength of all Movers going to the same
     * destination as a given Move Order.
     */
    protected boolean champion(
            Order moveOrder,
            int attackStrength,
            boolean optimistic,
            Collection<Order> opponents,
            ResolutionContext context
    ) {

        boolean champion = true;

        for (Order order2 : opponents) {
            if (order2.equals(moveOrder))
                continue;

            int opponentPreventStrength = calculatePreventStrength(
                    order2,
                    !optimistic,
                    orders,
                    context
            );

            if (opponentPreventStrength >= attackStrength) {
                champion = false;
                break;
            }
        }

        return champion;
    }


    protected int calculateAttackStrength(
            Order moveOrder,
            boolean optimistic,
            boolean headToHead,
            Collection<Order> orders
    ) {
        return calculateAttackStrength(
                moveOrder,
                optimistic,
                headToHead,
                orders,
                this.rootContext
        );
    }

    /**
     * Calculate a Move Order's Attack Strength.
     */
    protected int calculateAttackStrength(
            Order moveOrder,
            boolean optimistic,
            boolean headToHead,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        if (moveOrder.orderType != OrderType.MOVE) {
            throw new IllegalArgumentException(String.format(
                    "Non-Move Order supplied for `calculateAttackStrength(...)`: %s",
                    moveOrder
            ));
        }

        if (!pathSuccessful(moveOrder, optimistic, orders, context))
            return 0;

        Order destOrder = Orders.locateUnitAtPosition(moveOrder.pos1, orders);

        if (destOrder == null) {
            return 1 + tallySuccessfulSupports(
                    moveOrder,
                    optimistic,
                    orders,
                    context
            );
        }

        if (!headToHead && destOrder.orderType == OrderType.MOVE) {
            if (resolve(destOrder, optimistic, context)) {
                return 1 + tallySuccessfulSupports(
                        moveOrder,
                        optimistic,
                        orders,
                        context
                );

            } else if (destOrder.owner == moveOrder.owner) {
                return 0;
            }
        }

        return 1 + tallySuccessfulSupportsForeign(
                moveOrder,
                optimistic,
                destOrder.owner,
                orders,
                context
        );
    }


    protected int calculateDefendStrength(
            Order headToHeadMoveOrder,
            boolean optimistic,
            Collection<Order> orders
    ) {
        return calculateDefendStrength(
                headToHeadMoveOrder,
                optimistic,
                orders,
                this.rootContext
        );
    }

    /**
     * Calculate a Head-to-Head Move Order's Defend Strength.
     */
    protected int calculateDefendStrength(
            Order headToHeadMoveOrder,
            boolean optimistic,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        if (headToHeadMoveOrder.orderType != OrderType.MOVE) {
            throw new IllegalArgumentException(String.format(
                    "Non-Move Order supplied for `calculateDefendStrength(...)`: %s",
                    headToHeadMoveOrder
            ));
        }

        return 1 + tallySuccessfulSupports(
                headToHeadMoveOrder,
                optimistic,
                orders,
                context
        );
    }


    protected int calculatePreventStrength(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> orders
    ) {
        return calculatePreventStrength(
                moveOrder,
                optimistic,
                orders,
                this.rootContext
        );
    }

    /**
     * Calculate a Move Order's Prevent Strength.
     */
    protected int calculatePreventStrength(
            Order moveOrder,
            boolean optimistic,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        if (moveOrder.orderType != OrderType.MOVE) {
            throw new IllegalArgumentException(String.format(
                    "Non-Move Order supplied for `calculatePreventStrength(...)`: %s",
                    moveOrder
            ));
        }

        if (!pathSuccessful(moveOrder, optimistic, orders, context)
                && !isHeadToHeadSuppressed(moveOrder, context)) {
            return 0;
        }

        // Checking the `sH2HAdj` flags is a solution to the "2-units-in-1-area bug", re: convoy swaps & incorrect Prevent Str. calculation
        // For more information, see the Test Case: ["6.G.16. THE TWO UNIT IN ONE AREA BUG, MOVING BY CONVOY"
        Order headToHead = Orders.locateHeadToHead(moveOrder, orders);

        if (headToHead != null) {
            if (resolve(headToHead, optimistic, context))
                return 0;
        }

        return 1 + tallySuccessfulSupports(
                moveOrder,
                optimistic,
                orders,
                context
        );
    }


    protected int calculateHoldStrength(
            Province pos,
            boolean optimistic,
            Collection<Order> orders
    ) {
        return calculateHoldStrength(
                pos,
                optimistic,
                orders,
                this.rootContext
        );
    }

    /**
     * Calculate a Province's Hold Strength.
     */
    protected int calculateHoldStrength(
            Province pos,
            boolean optimistic,
            Collection<Order> orders,
            ResolutionContext context
    ) {

        Order occupant = Orders.locateUnitAtPosition(pos, orders);

        if (occupant == null)
            return 0;

        if (occupant.orderType == OrderType.MOVE) {
            if (resolve(occupant, optimistic, context))
                return 0;

            return 1;
        }

        return 1 + tallySuccessfulSupports(
                occupant,
                optimistic,
                orders,
                context
        );
    }


    // ResolutionContext helper \\

    /**
     * Transitional suppression lookup.<br><br>
     *
     * The legacy field is deliberately retained during the context migration,
     * so this plumbing change preserves existing adjudication behavior.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isHeadToHeadSuppressed(
            Order order,
            ResolutionContext context
    ) {
        return order.suppressH2HAdjudication
                || context.suppressesHeadToHead(order);
    }


}