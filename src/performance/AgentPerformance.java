package performance;

import agents.execution.AgentDecision;
import agents.generation.ActionGenerator;
import agents.search.JointActionEvaluator;
import phase.movement.MovementProcessor;

import java.io.PrintStream;
import java.util.*;

/**
 * Optional, caller-owned diagnostics for sequential decisions and games.
 * Never part of agent settings, random streams or archive identities.
 * Counts include attempted work; nested elapsed timings overlap.
 */
public class AgentPerformance {


    private static final AgentPerformance DISABLED = new AgentPerformance(false);

    private final       PerformanceStats                timings;
    private final       MovementProcessor.Diagnostics   movement;
    private             long    requests;
    private             long    hits;
    private             long    simulations;
    private             long    evaluations;
    private             long    failures;
    private             long    evictions;


    public AgentPerformance(boolean enabled) {

        this.timings = new PerformanceStats(enabled);
        this.movement = enabled ? new MovementProcessor.Diagnostics(this.timings) : null;

    }

    public static AgentPerformance disabled() {
        return DISABLED;
    }

    public PerformanceStats timings() {
        return this.timings;
    }

    /** Null retains MovementProcessor's original no-diagnostics path. */
    public MovementProcessor.Diagnostics movement() {
        return this.movement;
    }

    public ActionGenerator rolloutGenerator(ActionGenerator generator) {

        Objects.requireNonNull(generator, "generator");
        if (!this.timings.isEnabled())
            return generator;
        return (request, catalog) -> {
            try (var timing = this.timings.measure("rollout generation")) {
                return generator.generate(request, catalog);
            }
        };

    }

    /** Adds one finished search's counters, including any failed attempt. */
    public void record(JointActionEvaluator.Counts counts) {

        Objects.requireNonNull(counts, "counts");
        if (!this.timings.isEnabled())
            return;
        this.requests += counts.requests();
        this.hits += counts.hits();
        this.simulations += counts.simulations();
        this.evaluations += counts.evaluations();
        this.failures += counts.failures();
        this.evictions += counts.evictions();

    }

    public Map<String, Long> counters() {

        var counts = this.movement == null
                ? new MovementProcessor.Counts(0, 0, 0, 0, 0, 0, 0)
                : this.movement.snapshot();
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("joint_requests", this.requests);
        result.put("joint_cache_hits", this.hits);
        result.put("root_simulations", this.simulations);
        result.put("continuation_evaluations", this.evaluations);
        result.put("joint_failures", this.failures);
        result.put("cache_evictions", this.evictions);
        result.put("movements", counts.movements());
        result.put("movement_failures", counts.failures());
        result.put("plain_adjudicator_calls", counts.plainCalls());
        result.put("justice_baseline_calls", counts.baselineCalls());
        result.put("justice_shuffled_trials", counts.shuffledTrials());
        result.put("justice_jury_probes", counts.juryProbes());
        result.put("justice_fallback_calls", counts.fallbackCalls());
        return Collections.unmodifiableMap(result);

    }

    public PerformanceReport.Section snapshot(String name) {
        return new PerformanceReport.Section(name, this.timings.snapshot(), counters());
    }

    public void printTo(PrintStream output) {

        Objects.requireNonNull(output, "output");
        if (!this.timings.isEnabled())
            return;
        this.timings.printTo(output);
        output.println("[perf] Work counters: " + counters());

    }

    /** Exact decision equivalence, excluding only diagnostic elapsed time. */
    public static void requireSameDecision(AgentDecision first, AgentDecision second) {

        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!first.state().equals(second.state())
                || !first.settings().equals(second.settings())
                || first.searchSeed() != second.searchSeed()
                || first.rolloutSeed() != second.rolloutSeed()
                || first.selectionSeed() != second.selectionSeed()
                || !first.pools().equals(second.pools())
                || !first.distribution().equals(second.distribution())
                || !first.searchResult().equals(second.searchResult())
                || first.selectedIndex() != second.selectedIndex())
            throw new IllegalStateException("Profiled and unprofiled decisions differ");

    }

}