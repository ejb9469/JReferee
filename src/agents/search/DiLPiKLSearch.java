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
 * phase. All living nations choose simultaneously. BestResponseSearch compares each
 * candidate against sampled orders from the other nations.</p>
 *
 * <p>Uses Algorithm 1 and Equation 5 of arXiv:2210.05492.
 * For each positive lambda:</p>
 * <pre>
 * policy(a) proportional to exp((Q(a) + lambda * logPrior(a)) / (kappa + lambda))
 * </pre>
 * <p>Q is the mean evaluator payoff for an action. The prior is its fixed
 * anchor probability. Larger lambda gives the anchor more influence;
 * smaller lambda gives payoff differences more influence. Constructors without
 * a temperature argument retain kappa = 0. TemperatureSchedule.ADAPTIVE enables
 * Section 2.2's schedule and a uniform first policy over supported actions.</p>
 *
 * <p>Each iteration samples a fresh population lambda and action independently
 * for every living nation. Sampling finishes before any values are updated.
 * Each nation then tries every candidate against the same sampled opponents.
 * One running payoff vector is shared across that nation's lambda types.
 * Each type tracks its own policy-averaged utility observations for temperature.
 * Payoffs contain no KL penalty; regularization enters when forming a policy.</p>
 *
 * <p>The population distribution applies to every nation, including the
 * requested nation. `agentLambda` selects its returned type and may be absent
 * from the population. Tracking that extra type does not affect sampled play.
 * FINAL returns the policy after the last value update. AVERAGE returns the
 * equal-weight average of that type's pre-update policies, including startup.
 * Output selection changes neither the sampled actions nor the Q updates.
 * Older constructors retain FINAL output.</p>
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
 * BestResponseSearch iterations repeat the current phase; they do not increase search depth.
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
public class DiLPiKLSearch implements PolicySearch {

    // BestResponseSearch configuration \\

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int candidateLimit;
    private final int iterations;
    private final LambdaDistribution population;
    private final double agentLambda;
    private final long seed;
    private final TemperatureSchedule temperature;
    private final PolicyOutput output;

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
        this(evaluator, candidateLimit, iterations, population, agentLambda, seed, TemperatureSchedule.ZERO);
    }

    /**
     * Uses plausible root generation and an explicit temperature schedule.
     */
    public DiLPiKLSearch(Evaluator evaluator, int candidateLimit, int iterations,
                         LambdaDistribution population, double agentLambda, long seed,
                         TemperatureSchedule temperature) {
        this(evaluator, new PlausibleActionGenerator(), candidateLimit, iterations,
                population, agentLambda, seed, temperature);
    }

    /**
     * Uses plausible root generation and an explicit output choice.
     */
    public DiLPiKLSearch(Evaluator evaluator, int candidateLimit, int iterations,
                         LambdaDistribution population, double agentLambda, long seed,
                         TemperatureSchedule temperature, PolicyOutput output) {
        this(evaluator, new PlausibleActionGenerator(), candidateLimit, iterations,
                population, agentLambda, seed, temperature, output);
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
        this(evaluator, generator, candidateLimit, iterations, population, agentLambda, seed, TemperatureSchedule.ZERO);
    }

    /**
     * Supplies generation, lambda types, and temperature explicitly.
     * Adaptive statistics restart with Q at the beginning of each solve.
     */
    public DiLPiKLSearch(Evaluator evaluator, ActionGenerator generator,
                         int candidateLimit, int iterations, LambdaDistribution population,
                         double agentLambda, long seed, TemperatureSchedule temperature) {
        this(evaluator, generator, candidateLimit, iterations, population, agentLambda,
                seed, temperature, PolicyOutput.FINAL);
    }

    /**
     * Supplies all settings. Both policy outputs are retained at each checkpoint.
     */
    public DiLPiKLSearch(Evaluator evaluator, ActionGenerator generator,
                         int candidateLimit, int iterations, LambdaDistribution population,
                         double agentLambda, long seed, TemperatureSchedule temperature, PolicyOutput output) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.population = Objects.requireNonNull(population, "population");
        this.temperature = Objects.requireNonNull(temperature, "temperature");
        this.output = Objects.requireNonNull(output, "output");
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
     * @return selected agent-lambda policy and aligned mean evaluator payoffs
     * @throws NullPointerException if an argument or required collaborator result is null
     * @throws IllegalArgumentException if power is eliminated
     * @throws IllegalStateException if a checkpoint exists, a pool is invalid,
     *         evaluator payoffs are incomplete or non-finite, or simulation fails
     */
    @Override
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
     * Cache use does not change sampling, Q updates, or temperature observations.
     */
    public JointSearchResult solve(Game game, Map<Nation, List<ActionCandidate>> pools,
                                   JointActionEvaluator trials) {
        Objects.requireNonNull(trials, "trials").requireContext(game, evaluator);
        return solve(SearchSupport.pools(SearchSupport.root(game), pools), new Random(seed), trials);
    }

    /**
     * Records completed iterations during one fixed-pool solve.
     * Checkpoints must increase strictly and end at the configured budget.
     * Snapshots are immutable and do not draw samples or evaluate extra actions.
     */
    public List<JointSearchResult> trace(Game game, Map<Nation, List<ActionCandidate>> pools,
                                         List<Integer> checkpoints, JointActionEvaluator trials) {
        checkpoints = List.copyOf(checkpoints);
        int previous = 0;
        for (int checkpoint : checkpoints) {
            if (checkpoint <= previous || checkpoint > iterations)
                throw new IllegalArgumentException("Checkpoints must increase within the iteration budget");
            previous = checkpoint;
        }
        if (previous != iterations)
            throw new IllegalArgumentException("The last checkpoint must equal the iteration budget");
        Objects.requireNonNull(trials, "trials").requireContext(game, evaluator);
        Map<Nation, List<ActionCandidate>> checked = SearchSupport.pools(SearchSupport.root(game), pools);
        try (var solving = trials.measure("fixed-pool solve")) {
            return iterate(checked, new Random(seed), trials, checkpoints);
        }
    }

    /**
     * Shared iteration loop. Existing search calls retain their random stream.
     */
    private JointSearchResult solve(Map<Nation, List<ActionCandidate>> pools, Random random,
                                    JointActionEvaluator trials) {
        try (var solving = trials.measure("fixed-pool solve")) {
            return iterate(pools, random, trials, List.of(iterations)).getLast();
        }
    }

    private List<JointSearchResult> iterate(Map<Nation, List<ActionCandidate>> pools, Random random,
                                            JointActionEvaluator trials, List<Integer> checkpoints) {
        List<JointSearchResult> history = new ArrayList<>();
        Map<Nation, double[]> means = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, UtilityMoments>> moments = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, double[]>> policySums = new EnumMap<>(Nation.class);
        Set<Double> lambdas = new LinkedHashSet<>(population.values());
        lambdas.add(agentLambda);
        for (Nation nation : pools.keySet()) {
            List<ActionCandidate> candidates = pools.get(nation);

            // A zero-prior pool cannot define a regularized policy.
            if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
                throw new IllegalStateException("No candidate has positive prior for " + nation);

            means.put(nation, new double[candidates.size()]);
            Map<Double, UtilityMoments> types = new LinkedHashMap<>();
            Map<Double, double[]> sums = new LinkedHashMap<>();
            for (double lambda : lambdas) {
                types.put(lambda, new UtilityMoments());
                sums.put(lambda, new double[candidates.size()]);
            }
            moments.put(nation, types);
            policySums.put(nation, sums);
        }

        for (int iteration = 0; iteration < iterations; iteration++) {

            // Freeze all sampled actions before updating any nation's values.
            Map<Nation, ActionCandidate> played = new EnumMap<>(Nation.class);
            Map<Nation, Map<Double, ActionDistribution>> policies = new EnumMap<>(Nation.class);
            for (Nation nation : pools.keySet()) {

                // Types are redrawn independently; a nation has no permanent sampled type.
                double lambda = population.sample(random);
                Map<Double, ActionDistribution> current = new LinkedHashMap<>();
                for (var type : moments.get(nation).entrySet()) {
                    ActionDistribution policy = policy(pools.get(nation), means.get(nation),
                            type.getKey(), kappa(type.getValue()));
                    current.put(type.getKey(), policy);
                    double[] sums = policySums.get(nation).get(type.getKey());
                    for (int action = 0; action < sums.length; action++)
                        sums[action] += policy.probabilities().get(action);
                }
                policies.put(nation, current);
                played.put(nation, current.get(lambda).sample(random));
            }

            // One joint trial supplies the played-action payoff for every nation.
            Map<Nation, Double> observed = trials.evaluate(played);
            for (Nation nation : pools.keySet()) {
                List<ActionCandidate> candidates = pools.get(nation);

                // Only this nation's complete action changes during its deviations.
                Map<Nation, ActionCandidate> deviation = new EnumMap<>(played);
                double[] values = means.get(nation);
                double[] payoffs = new double[candidates.size()];
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
                    payoffs[index] = payoff;

                    // Equal-weight mean of raw payoffs across the sampled opponent history.
                    // This form avoids accumulating a potentially overflowing payoff sum.
                    double count = iteration + 1.0;
                    values[index] = values[index] * (iteration / count) + payoff / count;
                    if (!Double.isFinite(values[index]))
                        throw new IllegalStateException("Non-finite action value for " + nation);
                }
                if (temperature != TemperatureSchedule.ZERO)
                    for (var type : moments.get(nation).entrySet())
                        type.getValue().add(expectedUtility(policies.get(nation).get(type.getKey()), payoffs));
            }
            if (iteration + 1 == checkpoints.get(history.size()))
                history.add(result(pools, means, moments, policySums, iteration + 1));
        }
        return List.copyOf(history);
    }

    private JointSearchResult result(Map<Nation, List<ActionCandidate>> pools,
                                     Map<Nation, double[]> means,
                                     Map<Nation, Map<Double, UtilityMoments>> moments,
                                     Map<Nation, Map<Double, double[]>> policySums, int completed) {
        // Checkpoint and final policies use completed values without drawing samples.
        Map<Nation, SearchResult> results = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> populations = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, Double>> temperatures = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, ActionDistribution>> averages = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            List<ActionCandidate> candidates = pools.get(nation);
            double[] values = means.get(nation);
            Map<Double, Double> finalTemperatures = new LinkedHashMap<>();
            Map<Double, ActionDistribution> finalPolicies = new LinkedHashMap<>();
            Map<Double, ActionDistribution> averagePolicies = new LinkedHashMap<>();
            for (var type : moments.get(nation).entrySet()) {
                double kappa = kappa(type.getValue());
                finalTemperatures.put(type.getKey(), kappa);
                finalPolicies.put(type.getKey(), policy(candidates, values, type.getKey(), kappa));
                double[] sums = policySums.get(nation).get(type.getKey());
                // Total mass equals the iteration count, apart from rounding.
                double total = Arrays.stream(sums).sum();
                averagePolicies.put(type.getKey(), new ActionDistribution(candidates,
                        Arrays.stream(sums).map(sum -> sum / total).boxed().toList()));
            }
            temperatures.put(nation, finalTemperatures);
            averages.put(nation, averagePolicies);
            results.put(nation, new SearchResult(finalPolicies.get(agentLambda),
                    Arrays.stream(values).boxed().toList(), completed));
            populations.put(nation, JointSearchResult.mixture(finalPolicies, population));
        }
        return new JointSearchResult(results, populations, temperatures, averages, PolicyOutput.FINAL)
                .withOutput(output, population, agentLambda);
    }

    // Utility observations \\

    private double kappa(UtilityMoments moments) {
        return temperature.kappa(moments.count, moments.standardDeviation());
    }

    private static double expectedUtility(ActionDistribution policy, double[] payoffs) {
        double scale = 0.0;
        for (int index = 0; index < payoffs.length; index++)
            if (policy.probabilities().get(index) > 0)
                scale = Math.max(scale, Math.abs(payoffs[index]));
        if (scale == 0.0)
            return 0.0;

        // Scale before summing so finite extreme payoffs remain bounded.
        double mean = 0.0;
        double mass = 0.0;
        for (int index = 0; index < payoffs.length; index++) {
            double probability = policy.probabilities().get(index);
            if (probability > 0)
                mean += probability * (payoffs[index] / scale);
            mass += probability;
        }
        return Math.clamp(mean / mass, -1.0, 1.0) * scale;
    }

    private static final class UtilityMoments {

        private int count;
        private double scale;
        private double mean;
        private double variance;

        private void add(double utility) {
            if (!Double.isFinite(utility))
                throw new IllegalStateException("Non-finite utility observation");

            // Moments use units of the largest absolute observation.
            if (Math.abs(utility) > scale) {
                double ratio = scale / Math.abs(utility);
                mean *= ratio;
                variance *= ratio * ratio;
                scale = Math.abs(utility);
            }
            double value = scale == 0 ? 0 : utility / scale;
            double weight = 1.0 / ++count;
            double difference = value - mean;
            mean += weight * difference;
            variance = (1 - weight) * variance + weight * difference * (value - mean);
        }

        private double standardDeviation() {
            return scale * StrictMath.sqrt(Math.clamp(variance, 0.0, 1.0));
        }
    }

    // Policy calculation \\

    /**
     * Forms the kappa=0 regularized response from caller-supplied finite values.
     * This remains the Equation 4 equilibrium-response diagnostic, even when
     * a finite-iteration solver uses adaptive temperature.
     */
    public static ActionDistribution policy(
            List<ActionCandidate> candidates, List<Double> values, double lambda) {
        return policy(candidates, values, lambda, 0.0);
    }

    /**
     * Forms the Equation 5 policy with an explicit entropy temperature.
     * Positive infinity selects the uniform startup limit over supported actions.
     * Candidate order and fixed anchor priors are retained.
     */
    public static ActionDistribution policy(
            List<ActionCandidate> candidates, List<Double> values, double lambda, double kappa) {
        candidates = List.copyOf(candidates);
        values = List.copyOf(values);
        LambdaDistribution.requirePositive(lambda);
        if (Double.isNaN(kappa) || kappa < 0)
            throw new IllegalArgumentException("Kappa must be nonnegative");
        if (candidates.isEmpty() || candidates.size() != values.size())
            throw new IllegalArgumentException("Candidates and values must have equal, nonzero sizes");
        if (values.stream().anyMatch(value -> !Double.isFinite(value)))
            throw new IllegalArgumentException("Policy values must be finite");
        if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
            throw new IllegalArgumentException("Policy requires positive prior support");
        return policy(candidates, values.stream().mapToDouble(Double::doubleValue).toArray(), lambda, kappa);
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
     * @param kappa nonnegative temperature, or positive infinity at startup
     * @return normalized selection probabilities over the supplied pool
     */
    private static ActionDistribution policy(List<ActionCandidate> candidates, double[] means,
                                             double lambda, double kappa) {
        if (kappa == Double.POSITIVE_INFINITY) {
            long supported = candidates.stream().filter(candidate -> Double.isFinite(candidate.logPrior())).count();
            return new ActionDistribution(candidates, candidates.stream()
                    .map(candidate -> Double.isFinite(candidate.logPrior()) ? 1.0 / supported : 0.0).toList());
        }

        double denominator = lambda + kappa;
        double scale = Math.max(lambda, kappa);
        double scaledDenominator = lambda / scale + kappa / scale;
        double priorWeight = Double.isFinite(denominator)
                ? lambda / denominator : (lambda / scale) / scaledDenominator;

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

            double advantage;
            if (Double.isFinite(denominator)) {
                // Divide first if subtraction overflowed for finite, opposite-sign payoffs.
                advantage = Double.isFinite(difference)
                        ? difference / denominator : means[index] / denominator - best / denominator;
            } else {
                // Lambda + kappa can overflow even though both terms are finite.
                advantage = (means[index] / scale - best / scale) / scaledDenominator;
            }

            // Zero anchor probability remains zero, even for a high-value action.
            logits[index] = prior == Double.NEGATIVE_INFINITY
                    ? Double.NEGATIVE_INFINITY : advantage + priorWeight * prior;
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