package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.ActionCandidate;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import game.Game;
import game.GameState;
import performance.PerformanceStats;

import java.util.*;

/**
 * Evaluates complete joint submissions at one fixed simulation root.
 *
 * <p>The game and evaluator are bound for the lifetime of this object.
 * A deterministic cache is an explicit caller promise: processors, evaluation,
 * and continuation sampling must return the same payoffs on every call.
 * Stateful or freshly sampled evaluations must use uncached().</p>
 *
 * <p>Cache keys ignore candidate labels, priors, and order-list order.
 * Repeated orders retain their multiplicity. Only successful, finite, complete
 * payoff maps are cached. Hits do not invoke processors or the evaluator.</p>
 *
 * <p>Single-threaded. Custom processor state and external records are outside
 * Game's rollback and must not affect a cached evaluation.</p>
 */
public final class JointActionEvaluator {

    private final Game game;
    private final GameState root;
    private final Evaluator evaluator;
    private final PerformanceStats timings;
    private final int capacity;
    private final Simulator simulator = new Simulator();
    private final Map<Map<Nation, Map<OrderForm, Integer>>, Map<Nation, Double>> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long requests;
    private long hits;
    private long simulations;
    private long evaluations;
    private long failures;
    private long evictions;

    private JointActionEvaluator(Game game, Evaluator evaluator, int capacity, PerformanceStats timings) {
        this.game = Objects.requireNonNull(game, "game");
        this.root = SearchSupport.root(game);
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.capacity = capacity;
    }

    public static JointActionEvaluator uncached(Game game, Evaluator evaluator) {
        return uncached(game, evaluator, new PerformanceStats(false));
    }

    public static JointActionEvaluator uncached(Game game, Evaluator evaluator, PerformanceStats timings) {
        return new JointActionEvaluator(game, evaluator, 0, timings);
    }

    /**
     * Enables least-recently-used eviction under a positive entry limit.
     * No determinism is inferred from an evaluator's class or seed.
     */
    public static JointActionEvaluator deterministic(
            Game game, Evaluator evaluator, int capacity, PerformanceStats timings) {
        if (capacity < 1)
            throw new IllegalArgumentException("Cache capacity must be positive");
        return new JointActionEvaluator(game, evaluator, capacity, timings);
    }

    /**
     * Resolves one phase, restores the root, then evaluates its successor.
     * Every living nation must be present exactly once.
     */
    public Map<Nation, Double> evaluate(Map<Nation, ActionCandidate> actions) {
        requests++;
        boolean completed = false;
        try (var timing = measure("joint evaluation")) {
            requireRoot();
            Map<Nation, ActionCandidate> submitted = Map.copyOf(actions);
            if (!submitted.keySet().equals(root.aliveNations()))
                throw new IllegalArgumentException("Joint action must cover exactly the living nations");
            for (var entry : submitted.entrySet())
                if (entry.getValue().power() != entry.getKey())
                    throw new IllegalArgumentException("Candidate power differs from its map key");

            Map<Nation, Map<OrderForm, Integer>> key = new EnumMap<>(Nation.class);
            if (capacity > 0) {
                submitted.forEach((nation, candidate) -> key.put(nation, SearchSupport.key(candidate)));
                Map<Nation, Double> found = cache.get(key);
                if (found != null) {
                    hits++;
                    completed = true;
                    return found;
                }
            }

            GameState successor;
            try (var simulation = measure("root simulation")) {
                simulations++;
                successor = simulator.peek(game, submitted);
            }
            Map<Nation, Double> values;
            try (var evaluation = measure("continuation evaluation")) {
                evaluations++;
                values = SearchSupport.values(evaluator, successor);
            }
            requireRoot();
            if (capacity > 0) {
                if (cache.size() == capacity) {
                    cache.remove(cache.keySet().iterator().next());
                    evictions++;
                }
                cache.put(Map.copyOf(key), values);
            }
            completed = true;
            return values;
        } finally {
            if (!completed)
                failures++;
        }
    }

    void requireContext(Game game, Evaluator evaluator) {
        if (this.game != game || this.evaluator != evaluator)
            throw new IllegalArgumentException("Joint evaluator belongs to another game or evaluator");
        requireRoot();
    }

    private void requireRoot() {
        if (game.getSnapshot() != null || !game.snapshot().equals(root))
            throw new IllegalStateException("Joint evaluation requires its unchanged root without a checkpoint");
    }

    public PerformanceStats.Scope measure(String name) {
        return timings.measure(name);
    }

    public List<PerformanceStats.Measurement> timings() {
        return timings.snapshot();
    }

    public Counts counts() {
        return new Counts(requests, hits, simulations, evaluations, failures, evictions, cache.size());
    }

    public record Counts(long requests, long hits, long simulations, long evaluations,
                         long failures, long evictions, int entries) { }

}