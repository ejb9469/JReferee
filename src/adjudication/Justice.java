package adjudication;

import adjudication.util.Dependencies;
import domain.OrderType;
import adjudication.util.OrderComparator;
import adjudication.util.Orders;

import java.util.*;

/**
 * `Justice` is a subclass of `Judge` which resolves both simple & complex
 * Paradoxes.<br><br>
 *
 * It does so by generating a large number of permutations, and running
 * `super::judge()` for them all to compare their results.<br><br>
 *
 * If there are multiple ( >1 ) possible resolutions (i.e. depends on
 * permutation), `Justice` will apply certain meta-resolution rules to
 * determine the correct resolution.
 *
 * @author Evan B
 */
public class Justice extends Judge {


    // Constants \\

    /*
     * 3,000 shuffled trials are required for deterministic candidate coverage in
     * the DATC sixth-order paradox and butterfly-effect cases (6.F.28.P/F.29).
     */
    public static final int NUM_TRIALS_DEFAULT = 3_000;  // up from 300  [ @ 10-08-26 ]
    // TODO: This should be programmatically determined from `orders.size()`

    /*
     * A fixed default makes a test run reproducible. Supply a different seed
     * through the three-argument constructor when investigating instability.
     */
    public static final long SHUFFLE_SEED_DEFAULT = 0xD1A10C4CL;


    // Config and state \\

    private final int numTrials;
    private final long shuffleSeed;


    private final List<ParadoxCycle> aggregateParadoxCycles;

    /*
     * Key: canonical externally meaningful adjudication outcome.
     * Value: complete candidate information for that outcome.
     */
    protected final Map<String, CandidateResolution> candidateResolutions;


    // Constructors \\

    public Justice() {
        this(Collections.emptyList(), NUM_TRIALS_DEFAULT, SHUFFLE_SEED_DEFAULT);
    }

    public Justice(Collection<Order> orders) {
        this(orders, NUM_TRIALS_DEFAULT, SHUFFLE_SEED_DEFAULT);
    }

    public Justice(int numTrials) {
        this(Collections.emptyList(), numTrials, SHUFFLE_SEED_DEFAULT);
    }

    public Justice(Collection<Order> orders, int numTrials) {
        this(orders, numTrials, SHUFFLE_SEED_DEFAULT);
    }

    /**
     * Creates a Justice with a known seed, allowing reproducible trial ordering.
     *
     * @param orders orders to adjudicate
     * @param numTrials number of shuffled orderings to examine; must be at least 1
     * @param shuffleSeed seed used to generate shuffled orderings
     */
    public Justice(Collection<Order> orders, int numTrials, long shuffleSeed) {

        super(orders);

        if (numTrials < 1)
            throw new IllegalArgumentException("numTrials must be at least 1");

        this.aggregateParadoxCycles = new ArrayList<>();
        // TreeMap gives every discovered candidate a canonical iteration order.
        this.candidateResolutions = new TreeMap<>();

        this.numTrials = numTrials;
        this.shuffleSeed = shuffleSeed;
    }


    // `judge()` method \\

    /**
     * Definitively meta-resolves the Collection of Orders `orders`, and applies
     * paradox-handling rules.
     *
     * @author Evan B
     */
    @Override
    public void judge() {

        this.aggregateParadoxCycles.clear();

        List<Order> submittedOrders = Orders.deepCopy(this.orders);

        List<List<Order>> components =
                Dependencies.partition(submittedOrders);

        /*
         * Preserve the established `Justice` behavior for a position
         * whose dependency graph is connected.
         */
        if (components.size() <= 1) {
            this.judgeAmbiguousComponent(submittedOrders);
            this.refreshParadoxCycles();
            return;
        }

        this.candidateResolutions.clear();

        Map<String, CandidateResolution> allCandidates = new TreeMap<>();
        List<Order> finalOrders = new ArrayList<>();

        int componentNumber = 0;
        for (List<Order> component : components) {

            componentNumber++;

            /*
             * Judge a private component copy once before running multitudes of trials.
             * A cycle of `Judge`-applied "snapshot" Szykman-HOLD means this component
             * might be order-sensitive.
             */
            Judge baselineJudge = new Judge(
                    Orders.deepCopy(component));

            baselineJudge.judge();

            this.recordParadoxCycles(
                    baselineJudge.getParadoxCycles());

            if (!this.requiresTrials(baselineJudge)) {
                finalOrders.addAll(
                        Orders.deepCopy(baselineJudge.getOrders()));
                continue;
            }

            Collection<Order> componentResolution =
                    this.judgeAmbiguousComponent(component);

            finalOrders.addAll(
                    Orders.deepCopy(componentResolution));

            this.copyCandidates(componentNumber, allCandidates);

        }

        /*
         * Candidate keys are local to each dep. component.
         * Prefix every stored key so 2 components with identical outcomes
         * do not overwrite each other's diagnostics.
         */
        this.candidateResolutions.clear();
        this.candidateResolutions.putAll(allCandidates);

        this.orders = Orders.deepCopy(finalOrders);

        this.refreshParadoxCycles();

    }

    /**
     * Performs the final selection process for 1 dependency component.<br><br>
     *
     * `SzykmanJustice::selectFinalResolution()` intentionally executes while
     * `this.orders` and `candidateResolutions` describe *only* this component.
     */
    private Collection<Order> judgeAmbiguousComponent(Collection<Order> componentOrders) {

        this.candidateResolutions.clear();

        /*
         * Begin from a stable order before shuffling.
         * This ensures the same input order set + the same seed ==> same trials seq.
         */
        // new ArrayList<>(Orders.deepCopy()) is REDUNDANT!!
        List<Order> originalOrders = Orders.deepCopy(componentOrders);

        originalOrders.sort(new OrderComparator());

        Random random = new Random(this.shuffleSeed);

        for (int trial = 1; trial <= this.numTrials; trial++) {

            List<Order> ordersClone = Orders.deepCopy(originalOrders);

            Collections.shuffle(ordersClone, random);

            /*
             * Preserve the order before Judge mutates resolution metadata or
             * applies a Szykman convoy replacement.
             */
            List<Order> trialInputOrder = Orders.deepCopy(ordersClone);

            this.orders = ordersClone;
            super.judge();

            Set<Order> outcome = new LinkedHashSet<>(
                    Orders.deepCopy(this.orders));

            String outcomeKey = resolutionKey(outcome);

            CandidateResolution candidateResolution =
                    this.candidateResolutions.computeIfAbsent(
                            outcomeKey,
                            ignored -> new CandidateResolution(
                                    outcome,
                                    trialInputOrder,
                                    super.getParadoxCycles()
                            )
                    );

            candidateResolution.recordTrial(trial);
            candidateResolution.recordDetectedCycles(
                    super.getParadoxCycles());
            candidateResolution.recordResolutionStates(outcome);

        }

        /*
         * Restore the original submitted state before selecting a final
         * meta-resolution.
         */
        this.orders = Orders.deepCopy(originalOrders);

        Collection<Order> finalResolution = this.selectFinalResolution();

        /*
         * A null result means no raw candidate was collected. Preserve the
         * restored original order set, matching the previous implementation.
         */
        if (finalResolution != null) {
            this.orders = Orders.deepCopy(finalResolution);
        }

        return Orders.deepCopy(this.orders);

    }


    // Component handling \\

    /**
     * Returns whether a component needs shuffled Justice trials
     * after 1 `Judge` pass.<br><br>
     *
     * A recursive cycle is evidence of branch-sentitive resolution.
     * A snapshot-backed HOLD is also evidence, because `Judge` only applies
     * that transformation when handling a cycle containing a convoy(s).
     */
    private boolean requiresTrials(Judge baselineJudge) {

        if (!baselineJudge.getParadoxCycles().isEmpty())
            return true;

        for (Order order : baselineJudge.getOrders())
            if (order.getSnapshot() != null)
                return true;

        return false;

    }

    /**
     * Copies candidates from the most recently-processed component
     * by `judgeAmbiguousComponent(...)`.
     */
    private void copyCandidates(
            int componentNumber,
            Map<String, CandidateResolution> allCandidates
    ) {

        String componentPrefix = "component="
                + componentNumber
                + "\u001F";

        for (Map.Entry<String, CandidateResolution> entry :
                this.candidateResolutions.entrySet()) {
            allCandidates.put(
                    componentPrefix + entry.getKey(),
                    new CandidateResolution(entry.getValue()));
        }

    }

    /**
     * Returns distinct recursive dependency cycles found during the most recent
     * top-level `Justice` run.<br><br>
     *
     * A `Judge` returns cycles from its most recent single adjudication.
     * `Justice` aggregates baseline and shuffled-trial cycles from every
     * dependency component.
     */
    @Override
    public List<ParadoxCycle> getParadoxCycles() {
        return Collections.unmodifiableList(
                new ArrayList<>(this.aggregateParadoxCycles));
    }

    private void refreshParadoxCycles() {

        for (CandidateResolution candidateResolution :
                this.candidateResolutions.values()) {
            this.recordParadoxCycles(
                    candidateResolution.getParadoxCycles());
        }

    }

    private void recordParadoxCycles(Collection<ParadoxCycle> cycles) {

        for (ParadoxCycle cycle : cycles) {

            boolean alreadyKnown = false;

            for (ParadoxCycle existingCycle :
                    this.aggregateParadoxCycles) {
                if (existingCycle.key().equals(cycle.key())) {
                    alreadyKnown = true;
                    break;
                }
            }

            if (!alreadyKnown)
                this.aggregateParadoxCycles.add(cycle);

        }

    }


    // Final meta-resolution selection \\

    /**
     * Selects the final resolution after {@link #judge()} has collected all raw
     * candidate outcomes.
     *
     * <p>The default implementation preserves Justice's established
     * compatibility-oriented meta-resolution policy. Subclasses may override
     * this hook to apply another policy after candidate collection—for example,
     * a strict DATC/Szykman convoy-paradox interpretation.</p>
     *
     * <p>This method does not modify {@code this.orders} except temporarily in
     * the existing tie-handling branch, where it performs a fresh Judge pass.
     * The caller, {@link #judge()}, deep-copies the returned selection into the
     * final {@code this.orders} collection.</p>
     *
     * @return selected final order collection, or {@code null} when no raw
     *         candidate resolution was collected
     */
    protected Collection<Order> selectFinalResolution() {

        Collection<Set<Order>> resolutions =
                this.representativeCandidateResolutions();

        if (resolutions.size() == 1) {

            return resolutions.iterator().next();

        } else if (resolutions.size() > 1) {

            Set<Order> szykmanHolds = new HashSet<>();
            Set<Order> firstSzykmanSet = null;

            for (Set<Order> resolution : resolutions) {
                for (Order order : resolution) {
                    if (order.getSnapshot() != null) {
                        szykmanHolds.add(order);

                        if (firstSzykmanSet == null)
                            firstSzykmanSet = resolution;
                    }
                }
            }

            Collection<Order> heuristicOrders = new HashSet<>(szykmanHolds);

            /*
             * S = total number of Szykman replacement holds over all
             * discovered outcomes.
             */
            int S = szykmanHolds.size();

            if (S == 0) {

                Set<Order> mostResolvedPerm = new HashSet<>();
                List<Set<Order>> otherMostResolvedPerms = new ArrayList<>();
                int mostNumResolved = -1;
                boolean tie = false;

                for (Set<Order> resolution : resolutions) {

                    int numResolved = 0;

                    for (Order order : resolution)
                        numResolved += order.resolved ? 1 : 0;

                    if (mostResolvedPerm.isEmpty()
                            || numResolved > mostNumResolved) {

                        mostResolvedPerm = resolution;
                        mostNumResolved = numResolved;
                        tie = false;
                        otherMostResolvedPerms.clear();

                    } else if (numResolved == mostNumResolved) {

                        tie = true;
                        otherMostResolvedPerms.add(resolution);

                    }

                }

                if (tie) {

                    otherMostResolvedPerms.add(mostResolvedPerm);

                    Map<String, Order> tiedConflictingConvoys =
                            this.findConflictingConvoys(otherMostResolvedPerms);

                    /*
                     * E11 reaches a tie in the number of resolved orders, but it
                     * has no conflicting convoy. Re-adjudicating an arbitrarily
                     * ordered Set makes the outcome depend on collection
                     * iteration order.
                     *
                     * In a non-convoy tie, prefer the candidate with the most
                     * successful orders and do not manufacture a Szykman
                     * scenario.
                     */
                    if (tiedConflictingConvoys.isEmpty()) {
                        mostResolvedPerm = this.selectMostSuccessfulCandidate(
                                otherMostResolvedPerms
                        );

                    } else {
                        /*
                         * A genuine convoy contradiction exists among the tied
                         * candidates. Preserve the legacy meta-Szykman approach
                         * for those cases.
                         */
                        Collection<Order> szykmanOrders =
                                this.szykmanRule(otherMostResolvedPerms);

                        this.orders = Orders.deepCopy(szykmanOrders);

                        for (Order order : this.orders)
                            order.wipeMetaInf();

                        super.judge();

                        mostResolvedPerm = new LinkedHashSet<>(
                                Orders.deepCopy(this.orders));

                    }

                }

                heuristicOrders = mostResolvedPerm;

            } else if (S == 1) {

                heuristicOrders = firstSzykmanSet;

            } else {

                /*
                 * Retain the common non-Szykman orders from the first
                 * resolution, while preserving every discovered Szykman
                 * replacement hold.
                 */
                for (Order order : firstSzykmanSet) {

                    boolean foundSzykmanHoldAtPosition = false;

                    for (Order holdOrder : szykmanHolds) {
                        if (holdOrder.pos0 == order.pos0) {
                            foundSzykmanHoldAtPosition = true;
                            break;
                        }
                    }

                    if (!foundSzykmanHoldAtPosition)
                        heuristicOrders.add(order);

                }

            }

            return heuristicOrders;
        }

        return null;

    }


    // Public diagnostics \\

    /**
     * Returns deep copies of every distinct raw Judge candidate discovered
     * before Justice meta-resolution selects a final result.<br><br>
     *
     * For a multi-component position, candidates describe only dependency components
     * that required shuffled `Justice` trials.
     * Components resolved by 1 baseline `Judge` pass do *not* produce `CandidateResolution` entries.
     */
    public Collection<CandidateResolution> getCandidateResolutions() {

        Collection<CandidateResolution> copies = new ArrayList<>();

        for (CandidateResolution candidateResolution :
                this.candidateResolutions.values()) {
            copies.add(new CandidateResolution(candidateResolution));
        }

        return copies;

    }


    // Final meta-resolution helpers \\

    /**
     * Returns deep copies of the representative order collection from every
     * distinct raw candidate resolution.
     */
    protected Collection<Set<Order>> representativeCandidateResolutions() {

        Collection<Set<Order>> resolutions = new ArrayList<>();

        for (CandidateResolution candidateResolution :
                this.candidateResolutions.values()) {
            resolutions.add(
                    candidateResolution.getRepresentativeResolution()
            );
        }

        return resolutions;

    }

    /**
     * Deterministically selects the most successful candidate from candidates
     * already tied on the number of resolved orders.<br><br>
     *
     * A lexicographically ordered resolution key is used only as a final stable
     * tie-breaker. This prevents HashSet iteration order from deciding a final
     * Justice outcome.
     */
    private Set<Order> selectMostSuccessfulCandidate(
            Collection<Set<Order>> candidates
    ) {

        Set<Order> selected = null;
        int highestSuccessfulCount = -1;
        String selectedKey = null;

        for (Set<Order> candidate : candidates) {

            int successfulCount = 0;

            for (Order order : candidate) {
                if (order.verdict)
                    successfulCount++;
            }

            String candidateKey = resolutionKey(candidate);

            if (selected == null
                    || successfulCount > highestSuccessfulCount
                    || (successfulCount == highestSuccessfulCount
                    && candidateKey.compareTo(selectedKey) < 0)) {

                selected = candidate;
                highestSuccessfulCount = successfulCount;
                selectedKey = candidateKey;
            }
        }

        return selected;

    }


    // Default Szykman rule \\

    /**
     * Handles paradoxical situations involving conflicting convoy outcomes by
     * replacing each conflicting convoy with a snapshot-backed HOLD.
     * (This is a 'programmatic hold')<br><br>
     *
     * Returns a new collection and does not mutate the candidate resolutions.<br><br>
     *
     * This existing helper is used only in the historical tie-handling branch.
     */
    private Collection<Order> szykmanRule(
            Collection<Set<Order>> resolutions
    ) {

        if (resolutions.isEmpty())
            return Collections.emptyList();

        Map<String, Order> conflictingConvoys =
                this.findConflictingConvoys(resolutions);

        /*
         * Begin with a representative resolution. The replacements below
         * overwrite only orders identified as conflicting convoys.
         */
        Set<Order> representativeResolution =
                resolutions.iterator().next();

        Collection<Order> verdict = new LinkedHashSet<>();

        for (Order order : representativeResolution) {

            Order originalOrder = Orders.originalOf(order);
            String originalKey = Orders.keyOf(originalOrder);

            if (conflictingConvoys.containsKey(originalKey))
                continue;

            verdict.add(new Order(order));
        }

        /*
         * Replace each genuinely conflicting convoy by a HOLD while preserving
         * the original convoy in the replacement order's snapshot.
         */
        for (Order originalConvoy : conflictingConvoys.values()) {

            Order szykmanHold = new Order(originalConvoy);

            szykmanHold.takeSnapshot();
            szykmanHold.orderType = OrderType.HOLD;
            szykmanHold.pos1 = null;
            szykmanHold.pos2 = null;

            verdict.add(szykmanHold);
        }

        return verdict;

    }

    /**
     * Finds submitted convoy orders whose adjudication differs across raw
     * candidate resolutions.<br><br>
     *
     * A transformed Szykman HOLD is treated as its original convoy by using
     * its retained snapshot.
     */
    protected Map<String, Order> findConflictingConvoys(
            Collection<Set<Order>> resolutions
    ) {

        Map<String, Order> conflictingConvoys = new LinkedHashMap<>();

        for (Set<Order> resolution : resolutions) {
            for (Order convoyOrder :
                    convoyOrdersIncludingSzykmanHolds(resolution)) {

                boolean differsAcrossResolutions = false;

                for (Set<Order> otherResolution : resolutions) {

                    Order matchingOrder = findMatchingOriginalOrder(
                            convoyOrder,
                            otherResolution
                    );

                    if (matchingOrder == null
                            || !sameAdjudicationOutcome(
                            convoyOrder,
                            matchingOrder
                    )) {
                        differsAcrossResolutions = true;
                        break;
                    }
                }

                if (differsAcrossResolutions) {
                    Order originalConvoy = new Order(
                            Orders.originalOf(convoyOrder));
                    conflictingConvoys.putIfAbsent(
                            Orders.keyOf(originalConvoy),
                            originalConvoy
                    );
                }

            }
        }

        return conflictingConvoys;

    }


    // Candidate identity helpers \\

    private static String resolutionKey(Collection<Order> resolution) {

        List<String> entries = new ArrayList<>();

        for (Order order : resolution) {
            entries.add(
                    Orders.keyOf(order)
                            + "\u001Fverdict=" + order.verdict
                            + "\u001Fsnapshot=" + snapshotIdentityKey(order)
            );
        }

        Collections.sort(entries);
        return String.join("\n", entries);

    }

    private static String snapshotIdentityKey(Order order) {

        Order snapshot = order.getSnapshot();

        if (snapshot == null)
            return "<none>";

        return Orders.keyOf(snapshot);

    }

    private static boolean sameAdjudicationOutcome(Order first, Order second) {

        return first.resolved == second.resolved
                && first.verdict == second.verdict
                && (first.getSnapshot() != null)
                == (second.getSnapshot() != null);

    }

    protected static Order findMatchingOriginalOrder(
            Order candidate,
            Collection<Order> resolution
    ) {

        for (Order order : resolution) {
            if (Orders.sameKey(candidate, order))
                return order;
        }

        return null;

    }

    private static Collection<Order> convoyOrdersIncludingSzykmanHolds(
            Collection<Order> resolution
    ) {

        Collection<Order> convoyOrders = new ArrayList<>();

        for (Order order : resolution) {
            if (Orders.originalOf(order).orderType == OrderType.CONVOY)
                convoyOrders.add(order);
        }

        return convoyOrders;

    }


}