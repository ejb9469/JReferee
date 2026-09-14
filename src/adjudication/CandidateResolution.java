package adjudication;

import adjudication.util.Orders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1 distinct raw `Judge` outcome observed by `Justice`.<br><br>
 *
 * A "candidate resolution" is grouped by its externally-meaningful adjudication
 * outcome. It retains representative orders plus the shuffled-trial details,
 * dependency cycles, and resolver bookkeeping observed for that outcome.
 */
public final class CandidateResolution
        implements Resolution, ParadoxTransparent {


    // Core state \\

    private final Set<Order> representativeResolution;
    private final Map<String, ResolutionState> verdictStates;
    private final List<Order> exampleInputOrder;
    private final List<Integer> trialNumbers;
    private final List<ParadoxCycle> detectedCycles;

    /*
     * `Justice.resolutionKey(...)` deliberately excludes `Order.resolved`, so
     * trials with the same external verdicts can have distinct recursive
     * bookkeeping. Retain all observed resolved states rather than making the
     * first representative outcome decide later meta-resolution.
     */
    private final Map<String, ResolutionStateRange> resolutionStates;

    private int occurrences;


    // Constructors \\

    public CandidateResolution(
            Collection<Order> representativeResolution,
            Collection<Order> exampleInputOrder,
            Collection<ParadoxCycle> detectedCycles
    ) {

        this.representativeResolution = new LinkedHashSet<>(
                Orders.deepCopy(representativeResolution));

        this.verdictStates = verdictStatesOf(
                this.representativeResolution);

        this.exampleInputOrder = new ArrayList<>(
                Orders.deepCopy(exampleInputOrder));

        this.trialNumbers = new ArrayList<>();
        this.detectedCycles = new ArrayList<>(detectedCycles);

        this.resolutionStates = new LinkedHashMap<>();
        this.recordResolutionStates(representativeResolution);

        this.occurrences = 0;

    }

    public CandidateResolution(CandidateResolution other) {

        this.representativeResolution = new LinkedHashSet<>(
                Orders.deepCopy(other.representativeResolution));

        this.verdictStates = Collections.unmodifiableMap(
                new LinkedHashMap<>(other.verdictStates));

        this.exampleInputOrder = new ArrayList<>(
                Orders.deepCopy(other.exampleInputOrder));

        this.trialNumbers = new ArrayList<>(other.trialNumbers);
        this.detectedCycles = new ArrayList<>(other.detectedCycles);

        this.resolutionStates = new LinkedHashMap<>();

        for (Map.Entry<String, ResolutionStateRange> entry :
                other.resolutionStates.entrySet()) {
            this.resolutionStates.put(
                    entry.getKey(),
                    new ResolutionStateRange(entry.getValue()));
        }

        this.occurrences = other.occurrences;

    }


    // Trial recording \\

    public void recordTrial(int trial) {
        this.occurrences++;
        this.trialNumbers.add(trial);
    }

    public void recordDetectedCycles(Collection<ParadoxCycle> cycles) {

        for (ParadoxCycle cycle : cycles) {

            boolean alreadyKnown = false;

            for (ParadoxCycle existingCycle : this.detectedCycles) {
                if (existingCycle.key().equals(cycle.key())) {
                    alreadyKnown = true;
                    break;
                }
            }

            if (!alreadyKnown)
                this.detectedCycles.add(cycle);

        }

    }

    public void recordResolutionStates(Collection<Order> outcome) {

        for (Order order : outcome) {

            String key = Orders.keyOf(
                    Orders.originalOf(order));

            this.resolutionStates.computeIfAbsent(
                    key,
                    ignored -> new ResolutionStateRange()
            ).observe(order.resolved);

        }

    }


    // State accessors \\

    /**
     * Returns whether this candidate was observed with the supplied resolver
     * bookkeeping state for the submitted order.
     */
    public boolean hasObservedResolvedState(
            Order order,
            boolean resolved
    ) {

        ResolutionStateRange states = this.resolutionStates.get(
                Orders.keyOf(
                        Orders.originalOf(order)));

        return states != null && states.hasObserved(resolved);

    }

    public int getOccurrences() {
        return this.occurrences;
    }

    public Set<Order> getRepresentativeResolution() {
        return new LinkedHashSet<>(
                Orders.deepCopy(this.representativeResolution));
    }

    public List<Order> getExampleInputOrder() {
        return new ArrayList<>(
                Orders.deepCopy(this.exampleInputOrder));
    }

    public List<Integer> getTrialNumbers() {
        return Collections.unmodifiableList(
                new ArrayList<>(this.trialNumbers));
    }

    @Override
    public List<ParadoxCycle> getParadoxCycles() {
        return Collections.unmodifiableList(
                new ArrayList<>(this.detectedCycles));
    }


    /**
     * Returns this candidate's `verdict` for a given Order.<br><br>
     *
     * A Szykman replacement HOLD is matched through its
     * snapshot/original convoy identity.
     */
    @Override
    public ResolutionState stateOf(Order order) {
        return this.verdictStates.getOrDefault(
                Orders.keyOf(order),
                ResolutionState.UNKNOWN
        );
    }

    /**
     * A `CandidateResolution` is collected from a completed `Judge` run, so every
     * Order in its dependency component has a `verdict`.
     */
    @Override
    public boolean isComplete() {
        return true;
    }


    // Verdict-state indexing \\

    private static Map<String, ResolutionState> verdictStatesOf(
            Collection<Order> resolution
    ) {

        Map<String, ResolutionState> states = new LinkedHashMap<>();

        for (Order order : resolution) {
            states.put(
                    Orders.keyOf(order),
                    ResolutionState.fromBoolean(order.verdict)
            );
        }

        return Collections.unmodifiableMap(states);

    }


    // Nested bookkeeping type \\

    /**
     * Range of recursive bookkeeping states observed for one submitted order
     * under this candidate.
     */
    private static final class ResolutionStateRange {

        private boolean observedResolved;
        private boolean observedUnresolved;

        private ResolutionStateRange() {
            this.observedResolved = false;
            this.observedUnresolved = false;
        }

        private ResolutionStateRange(ResolutionStateRange other) {
            this.observedResolved = other.observedResolved;
            this.observedUnresolved = other.observedUnresolved;
        }

        private void observe(boolean resolved) {
            if (resolved)
                this.observedResolved = true;
            else
                this.observedUnresolved = true;
        }

        private boolean hasObserved(boolean resolved) {
            return resolved
                    ? this.observedResolved
                    : this.observedUnresolved;
        }

    }


}