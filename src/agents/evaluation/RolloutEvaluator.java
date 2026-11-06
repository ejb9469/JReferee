package agents.evaluation;

import agents.generation.*;
import agents.simulation.Simulator;
import domain.Nation;
import game.Game;
import game.GameState;
import game.GameState_Adjustments;

import java.util.*;
import java.util.function.Function;

/**
 * Averages sampled continuations through the supplied position's Fall turn.
 *
 * <p>Each trajectory ends at the first winter/adjustment position, after Fall
 * ownership updates and before builds or disbands. A winter input is evaluated
 * immediately. Compared candidates from one root therefore share a year-end
 * boundary, even when some expose retreats and others skip them.</p>
 *
 * <p>Actions are sampled independently for each nation from freshly generated
 * candidate pools, weighted by exp(logPrior). These are anchor probabilities
 * conditioned on the retained candidates. The default PlausibleActionGenerator
 * coordinates orders within each movement action, while keeping a uniform
 * anchor. Selection is uniform within its returned pool. Construction scores
 * affect which candidates are offered, but are not sampling weights.
 * Candidate priors are never modified.</p>
 *
 * <p>Default movement proposals exclude convoys. Retreats and adjustments
 * retain the uniform baseline. Each phase receives new candidate pools for
 * its current board and living nations; root search pools are not reused.</p>
 *
 * <p>The leaf evaluator defines the payoff scale. Averaging preserves that
 * scale, including variable-total scoring systems. Random or weak continuation
 * policies can give poor forecasts; this is not a learned value model
 * or an equilibrium search.</p>
 *
 * <p>The seed restarts for each evaluation. Reproducibility also requires
 * deterministic generators, processors, and leaf evaluation. A game factory
 * must supply a separate, correctly configured game for each call. Mutable
 * custom processor state is outside Game's rollback. Parallel evaluations
 * require thread-safe collaborators and independent games/processors.</p>
 */
public class RolloutEvaluator implements Evaluator {

    private final Evaluator leafEvaluator;
    private final ActionGenerator generator;
    private final Function<GameState, Game> gameFactory;
    private final int rolloutCount;
    private final int candidateLimit;
    private final long seed;

    /**
     * Uses PlausibleActionGenerator and Game.fromState's standard processors.
     *
     * <p>Movement pools contain coordinated, convoy-free candidates. The
     * generator caps them at 256 and may return fewer under its construction
     * budget. A limit of 1 produces all-hold in movement.</p>
     *
     * <p>Root search chooses its generator separately. Supply the explicit
     * constructors when either stage needs a different generation policy.
     * Custom root processors also require a matching continuation game factory.</p>
     *
     * @param leafEvaluator finite year-end payoffs for every nation
     * @param rolloutCount sampled continuations per evaluation; at least 1
     * @param candidateLimit maximum candidates per living nation and phase; at least 1
     * @param seed random seed restarted by each evaluation
     * @throws NullPointerException if leafEvaluator is null
     * @throws IllegalArgumentException if either limit is below 1
     */
    public RolloutEvaluator(Evaluator leafEvaluator, int rolloutCount, int candidateLimit, long seed) {
        this(leafEvaluator, new PlausibleActionGenerator(), Game::fromState,
                rolloutCount, candidateLimit, seed);
    }

    /**
     * Supplies the continuation generator and simulation configuration explicitly.
     * Each generated pool must contain at least one action with positive prior.
     */
    public RolloutEvaluator(
            Evaluator leafEvaluator,
            ActionGenerator generator,
            Function<GameState, Game> gameFactory,
            int rolloutCount,
            int candidateLimit,
            long seed
    ) {
        this.leafEvaluator = Objects.requireNonNull(leafEvaluator, "leafEvaluator");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.gameFactory = Objects.requireNonNull(gameFactory, "gameFactory");
        if (rolloutCount < 1 || candidateLimit < 1)
            throw new IllegalArgumentException("rolloutCount and candidateLimit must be at least 1");
        this.rolloutCount = rolloutCount;
        this.candidateLimit = candidateLimit;
        this.seed = seed;
    }

    /**
     * Returns immutable mean values for all nations at the current year's winter.
     *
     * <p>One game is reused across trajectories. Each has its own checkpoint,
     * restored in finally even if generation, resolution, or evaluation fails.
     * No winter orders are submitted and the year never advances.</p>
     */
    @Override
    public Map<Nation, Double> evaluate(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state instanceof GameState_Adjustments)
            return leafValues(state);

        Game game = Objects.requireNonNull(gameFactory.apply(state), "gameFactory result");
        if (game.getSnapshot() != null)
            throw new IllegalStateException("Rollout game already has a checkpoint");
        if (!game.snapshot().equals(state))
            throw new IllegalStateException("Rollout game does not match the supplied position");

        Random random = new Random(seed);
        Simulator simulator = new Simulator();
        Map<Nation, Double> means = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            means.put(nation, 0.0);

        for (int rollout = 0; rollout < rolloutCount; rollout++) {
            game.takeSnapshot();
            try {
                GameState current = state;
                while (!(current instanceof GameState_Adjustments)) {
                    GameState next = simulator.advance(game, sampleActions(current, random));
                    if (next.moment().year() != state.moment().year()
                            || next.moment().compareTo(current.moment()) <= 0)
                        throw new IllegalStateException("Rollout must advance within the current year");
                    current = next;
                }
                Map<Nation, Double> values = leafValues(current);
                for (Nation nation : Nation.values()) {
                    double mean = means.get(nation) + values.get(nation) / rolloutCount;
                    if (!Double.isFinite(mean))
                        throw new IllegalStateException("Non-finite rollout mean for " + nation);
                    means.put(nation, mean);
                }
            } finally {
                game.restoreFromSnapshot();
            }
        }
        return Collections.unmodifiableMap(means);
    }

    private Map<Nation, ActionCandidate> sampleActions(GameState state, Random random) {
        Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
        LegalOrderGenerator legalOrders = new LegalOrderGenerator();
        for (Nation nation : state.aliveNations()) {
            LegalOrderCatalog catalog = legalOrders.generate(state, nation);
            List<ActionCandidate> candidates = List.copyOf(generator.generate(
                    new ActionGenerationRequest(state, nation, candidateLimit, random.nextLong()), catalog));
            if (candidates.isEmpty() || candidates.size() > candidateLimit)
                throw new IllegalStateException("Invalid candidate count for " + nation + ": " + candidates.size());
            for (ActionCandidate candidate : candidates)
                if (candidate.power() != nation)
                    throw new IllegalStateException("Generator returned an action for another nation");
            actions.put(nation, ActionDistribution.fromPriors(candidates).sample(random));
        }
        return actions;
    }

    private Map<Nation, Double> leafValues(GameState state) {
        Map<Nation, Double> values = Map.copyOf(
                Objects.requireNonNull(leafEvaluator.evaluate(state), "leaf evaluator result"));
        if (!values.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalStateException("Leaf evaluator must score every nation");
        for (Nation nation : Nation.values())
            if (!Double.isFinite(values.get(nation)))
                throw new IllegalStateException("Non-finite leaf score for " + nation);
        return Collections.unmodifiableMap(new EnumMap<>(values));
    }

}