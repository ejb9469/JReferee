package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Distributional-lambda piKL search over fixed candidate pools.
 *
 * <p>Each candidate is one nation's complete set of orders for the current
 * phase. All living nations choose simultaneously. Search compares each
 * candidate against sampled orders from the other nations.</p>
 *
 * <p>Uses Algorithm 1 and Equation 5 of arXiv:2210.05492 with kappa = 0,
 * the simpler option described in Section 2.2. For each positive lambda:</p>
 * <pre>
 * policy(a) = prior(a) * exp(Q(a) / lambda) / normalizing sum
 * </pre>
 * <p>Q is the mean evaluator payoff for an action. The prior is its fixed
 * anchor probability. Larger lambda gives the anchor more influence;
 * smaller lambda gives payoff differences more influence. The experimental
 * adaptive temperature schedule is not included.</p>
 *
 * <p>Each iteration samples a fresh population lambda and action independently
 * for every living nation. Sampling finishes before any values are updated.
 * Each nation then tries every candidate against the same sampled opponents.
 * One running payoff vector is shared across that nation's lambda types.
 * Payoffs contain no KL penalty; regularization enters when forming a policy.</p>
 *
 * <p>The population distribution applies to every nation, including the
 * requested nation. `agentLambda` is used only for its returned policy,
 * after the final value update. That policy is neither a time average nor
 * a mixture of population types. `agentLambda` may be absent from the population.</p>
 *
 * <p>All lambdas must be finite, positive, and appropriate for the evaluator's
 * payoff scale. Multiplying payoffs by a positive factor requires scaling
 * both population lambdas and `agentLambda` by that factor to preserve policies.
 * Negative-infinity log priors keep zero policy mass, regardless of payoff.</p>
 *
 * <p>Pools and anchor priors remain fixed throughout a search. Missing actions
 * cannot be discovered by increasing the iteration count. The generator supplies
 * coordinated order sets; its construction scores do not enter the payoff update
 * or the KL calculation. No prior smoothing or response-driven pool expansion occurs here.
 * A uniform anchor and short rollouts remain baselines for the paper's learned
 * policies and values. Convergence in seven-player Diplomacy is not guaranteed.</p>
 *
 * <p>Each root trial uses `Simulator.peek()` and restores the supplied game.
 * Search iterations repeat the current phase; they do not increase search depth.
 * A `RolloutEvaluator` can extend each successor on a separate game using
 * `advance()`. Future-phase equilibrium searches would require further work.</p>
 *
 * <p>The seed restarts each search. Reproducibility also requires deterministic
 * generators, evaluators, and processors. Parallel searches require independent
 * games and processors, with thread-safe collaborators. Custom processor state
 * and external records are outside Game's rollback. The caller decides whether
 * the game has ended before requesting a search.</p>
 *
 * @author Evan B.
 */
public class DiLPiKLSearch {

    // Search configuration \\

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int candidateLimit;
    private final int iterations;
    private final LambdaDistribution population;
    private final double agentLambda;
    private final long seed;

    // Constructors \\

    /**
     * Uses PlausibleActionGenerator for every living nation's root pool.
     * Movement candidates coordinate friendly orders and exclude convoys.
     * Retreats and adjustments retain the uniform baseline.
     *
     * <p>The generator caps movement pools at 256 candidates and may return
     * fewer under its construction budget. A limit of 1 produces all-hold
     * in movement. The anchor remains factorized and uniform.</p>
     *
     * <p>A rollout evaluator configures its continuation generator separately.
     * Its convenience constructor also uses PlausibleActionGenerator.
     * Use the explicit constructors to choose another generator for either stage.</p>
     *
     * @param evaluator successor evaluation, including any continuation search
     * @param candidateLimit maximum candidates per living nation; at least 1
     * @param iterations payoff observations per candidate; at least 1
     * @param population shared distribution of positive lambda types
     * @param agentLambda finite, positive lambda for the returned policy
     * @param seed random seed restarted by each search
     * @throws NullPointerException if evaluator or population is null
     * @throws IllegalArgumentException if a limit or agentLambda is invalid
     */
    public DiLPiKLSearch(Evaluator evaluator, int candidateLimit, int iterations,
                         LambdaDistribution population, double agentLambda, long seed) {
        this(evaluator, new PlausibleActionGenerator(), candidateLimit, iterations,
                population, agentLambda, seed);
    }

    /**
     * Supplies the root generator and search settings explicitly.
     *
     * <p>The generator must return distinct, complete actions with fixed anchor
     * log priors. Each pool must contain at least one positive-prior action.
     * Generation and pool validation occur when `search()` is called.</p>
     *
     * @param evaluator finite successor payoffs for every nation, including eliminated nations
     * @param generator candidate generation for all living nations at the root
     * @param candidateLimit maximum candidates per living nation; at least 1
     * @param iterations payoff observations per candidate; at least 1
     * @param population shared distribution of positive lambda types
     * @param agentLambda finite, positive lambda for the returned policy
     * @param seed random seed for candidate generation, lambda types, and actions
     * @throws NullPointerException if evaluator, generator, or population is null
     * @throws IllegalArgumentException if a limit or agentLambda is invalid
     */
    public DiLPiKLSearch(Evaluator evaluator, ActionGenerator generator,
                         int candidateLimit, int iterations, LambdaDistribution population,
                         double agentLambda, long seed) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.population = Objects.requireNonNull(population, "population");
        if (candidateLimit < 1 || iterations < 1)
            throw new IllegalArgumentException("candidateLimit and iterations must be at least 1");
        LambdaDistribution.requirePositive(agentLambda);
        this.candidateLimit = candidateLimit;
        this.iterations = iterations;
        this.agentLambda = agentLambda;
        this.seed = seed;
    }

    // Root search \\

    /**
     * Updates all living nations and returns the requested nation's policy.
     *
     * <p>Generates one pool per living nation from the same root position.
     * The pools retain their order and original anchor priors. Nations with
     * no orders still supply an empty action when they remain alive.</p>
     *
     * <p>Each candidate receives one payoff observation per iteration,
     * including candidates with zero prior. Means describe the sampled opponent
     * history. They are not fresh estimates against the final opponent policies.
     * Extra trajectories inside an evaluator do not change the result's
     * `samplesPerCandidate`, which equals `iterations`.</p>
     *
     * <p>For pool sizes K_i, each iteration evaluates 1 + sum_i(K_i - 1) joint
     * actions. The sampled joint result is reused for its selected candidates.
     * An explicitly shared deterministic cache may reuse further results.
     * Any continuation work performed by the evaluator adds to this cost.</p>
     *
     * <p>The supplied game must be isolated and have no saved checkpoint.
     * Each peek restores its phase, board, and histories before evaluation.
     * Generation, simulation, and evaluation failures propagate to the caller.</p>
     *
     * @param game isolated simulation game at the phase to search
     * @param power living nation whose policy is requested
     * @return final agent-lambda policy and aligned mean evaluator payoffs
     * @throws NullPointerException if an argument or required collaborator result is null
     * @throws IllegalArgumentException if power is eliminated
     * @throws IllegalStateException if a checkpoint exists, a pool is invalid,
     *         evaluator payoffs are incomplete or non-finite, or simulation fails
     */
    public SearchResult search(Game game, Nation power) {
        return search(game, power, JointActionEvaluator.uncached(game, evaluator));
    }

    /**
     * Shares fixed-root evaluation and optional caching with other searches.
     * The supplied helper must be bound to this game and evaluator instance.
     */
    public SearchResult search(Game game, Nation power, JointActionEvaluator trials) {
        Objects.requireNonNull(trials, "trials").requireContext(game, evaluator);
        GameState root = SearchSupport.root(game, power);
        Random random = new Random(seed);

        // Candidate generation finishes before any trial advances the root.
        Map<Nation, List<ActionCandidate>> pools;
        try (var generation = trials.measure("candidate generation")) {
            pools = SearchSupport.generatePools(root, generator, candidateLimit, random);
        }
        return solve(SearchSupport.pools(root, pools), random, trials).results().get(power);
    }

    /**
     * Solves caller-supplied pools for all living nations.
     *
     * <p>The generation limit does not cap explicit pools. They must contain
     * distinct, valid actions and positive prior support for each living nation.
     * Input order and priors are retained. No new candidates are generated.</p>
     *
     * <p>The seed starts directly at population sampling. `search()` first uses
     * its random stream for generation seeds, so the two entry points need not
     * produce identical samples even when supplied the same pools.</p>
     */
    public JointSearchResult solve(Game game, Map<Nation, List<ActionCandidate>> pools) {
        return solve(game, pools, JointActionEvaluator.uncached(game, evaluator));
    }

    /**
     * Solves explicit pools using an existing fixed-root evaluation helper.
     * Cache use does not change the sampling sequence or Q update.
     */
    public JointSearchResult solve(Game game, Map<Nation, List<ActionCandidate>> pools,
                                   JointActionEvaluator trials) {
        Objects.requireNonNull(trials, "trials").requireContext(game, evaluator);
        return solve(SearchSupport.pools(SearchSupport.root(game), pools), new Random(seed), trials);
    }

    /**
     * Shared iteration loop. Existing search calls retain their random stream.
     */
    private JointSearchResult solve(Map<Nation, List<ActionCandidate>> pools, Random random,
                                    JointActionEvaluator trials) {
        try (var solving = trials.measure("fixed-pool solve")) {
            return iterate(pools, random, trials);
        }
    }

    private JointSearchResult iterate(Map<Nation, List<ActionCandidate>> pools, Random random,
                                      JointActionEvaluator trials) {
        Map<Nation, double[]> means = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            List<ActionCandidate> candidates = pools.get(nation);

            // A zero-prior pool cannot define a regularized policy.
            if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
                throw new IllegalStateException("No candidate has positive prior for " + nation);

            // Initial Q = 0 makes the first policy the anchor conditioned on its pool.
            means.put(nation, new double[candidates.size()]);
        }

        for (int iteration = 0; iteration < iterations; iteration++) {

            // Freeze all sampled actions before updating any nation's values.
            Map<Nation, ActionCandidate> played = new EnumMap<>(Nation.class);
            for (Nation nation : pools.keySet()) {

                // Types are redrawn independently; a nation has no permanent sampled type.
                double lambda = population.sample(random);
                played.put(nation, policy(pools.get(nation), means.get(nation), lambda).sample(random));
            }

            // One joint trial supplies the played-action payoff for every nation.
            Map<Nation, Double> observed = trials.evaluate(played);
            for (Nation nation : pools.keySet()) {
                List<ActionCandidate> candidates = pools.get(nation);

                // Only this nation's complete action changes during its deviations.
                Map<Nation, ActionCandidate> deviation = new EnumMap<>(played);
                double[] values = means.get(nation);
                for (int index = 0; index < candidates.size(); index++) {
                    ActionCandidate candidate = candidates.get(index);
                    double payoff;

                    // sample() returns a pool member, so identity identifies the reused trial.
                    if (candidate == played.get(nation)) {
                        payoff = observed.get(nation);
                    } else {
                        deviation.put(nation, candidate);

                        // Opponent orders stay fixed; a stochastic evaluator may add fresh noise.
                        payoff = trials.evaluate(deviation).get(nation);
                    }

                    // Equal-weight mean of raw payoffs across the sampled opponent history.
                    // This form avoids accumulating a potentially overflowing payoff sum.
                    double count = iteration + 1.0;
                    values[index] = values[index] * (iteration / count) + payoff / count;
                    if (!Double.isFinite(values[index]))
                        throw new IllegalStateException("Non-finite action value for " + nation);
                }
            }
        }

        // Final policies use the completed values, without drawing further samples.
        Map<Nation, SearchResult> results = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> populations = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            List<ActionCandidate> candidates = pools.get(nation);
            double[] values = means.get(nation);
            results.put(nation, new SearchResult(policy(candidates, values, agentLambda),
                    Arrays.stream(values).boxed().toList(), iterations));
            List<Double> mixture = new ArrayList<>(Collections.nCopies(candidates.size(), 0.0));
            for (int type = 0; type < population.values().size(); type++) {
                double mass = population.probabilities().get(type);
                if (mass == 0.0)
                    continue;
                List<Double> probabilities = policy(candidates, values, population.values().get(type)).probabilities();
                for (int index = 0; index < mixture.size(); index++)
                    mixture.set(index, mixture.get(index) + mass * probabilities.get(index));
            }
            double total = mixture.stream().mapToDouble(Double::doubleValue).sum();
            for (int index = 0; index < mixture.size(); index++)
                mixture.set(index, mixture.get(index) / total);
            populations.put(nation, new ActionDistribution(candidates, mixture));
        }
        return new JointSearchResult(results, populations);
    }

    // Policy calculation \\

    /**
     * Forms the same regularized policy from caller-supplied finite values.
     * Useful for comparing historical-Q policies with fresh final-profile values.
     * Candidate order and fixed anchor priors are retained.
     */
    public static ActionDistribution policy(
            List<ActionCandidate> candidates, List<Double> values, double lambda) {
        candidates = List.copyOf(candidates);
        values = List.copyOf(values);
        LambdaDistribution.requirePositive(lambda);
        if (candidates.isEmpty() || candidates.size() != values.size())
            throw new IllegalArgumentException("Candidates and values must have equal, nonzero sizes");
        if (values.stream().anyMatch(value -> !Double.isFinite(value)))
            throw new IllegalArgumentException("Policy values must be finite");
        if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
            throw new IllegalArgumentException("Policy requires positive prior support");
        return policy(candidates, values.stream().mapToDouble(Double::doubleValue).toArray(), lambda);
    }

    /**
     * Forms the anchor-weighted softmax for one nation's current payoff means.
     *
     * <p>The same calculation serves sampled population types and the final
     * agent policy. Input lists must be aligned and nonempty, means must be
     * finite, and at least one candidate must have finite log prior.
     * Lambda is already checked by the constructor or population distribution.</p>
     *
     * <p>Subtracting the best supported payoff and then the largest logit
     * preserves relative weights while limiting overflow. Priors stay in log
     * space until combined with payoffs, so a tiny finite prior can recover
     * weight when its payoff advantage is large enough.</p>
     *
     * <p>Negative-infinity log priors always receive zero mass. Very small
     * relative weights can still underflow when exponentiated. No smoothing
     * is applied, and the candidates' stored priors remain unchanged.</p>
     *
     * @param candidates fixed pool in generator order
     * @param means raw mean payoffs in the same order
     * @param lambda finite, positive regularization strength
     * @return normalized selection probabilities over the supplied pool
     */
    private static ActionDistribution policy(List<ActionCandidate> candidates, double[] means, double lambda) {

        // Unsupported actions cannot set the reference payoff.
        double best = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < candidates.size(); index++)
            if (Double.isFinite(candidates.get(index).logPrior()))
                best = Math.max(best, means[index]);

        double[] logits = new double[candidates.size()];
        double maximum = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < logits.length; index++) {
            double prior = candidates.get(index).logPrior();
            double difference = means[index] - best;

            // Divide first if subtraction overflowed for finite, opposite-sign payoffs.
            double advantage = Double.isFinite(difference)
                    ? difference / lambda : means[index] / lambda - best / lambda;

            // Zero anchor probability remains zero, even for a high-value action.
            logits[index] = prior == Double.NEGATIVE_INFINITY
                    ? Double.NEGATIVE_INFINITY : advantage + prior;
            maximum = Math.max(maximum, logits[index]);
        }

        double total = 0.0;
        List<Double> probabilities = new ArrayList<>(logits.length);
        for (double logit : logits) {

            // The largest shifted logit is zero, giving a weight of 1.
            double weight = StrictMath.exp(logit - maximum);
            probabilities.add(weight);
            total += weight;
        }

        // Normalize over this retained pool without rewriting any anchor priors.
        for (int index = 0; index < probabilities.size(); index++)
            probabilities.set(index, probabilities.get(index) / total);
        return new ActionDistribution(candidates, probabilities);
    }

}