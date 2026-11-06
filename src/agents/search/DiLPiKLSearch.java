package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.simulation.Simulator;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Distributional-lambda piKL over fixed root candidate pools.
 *
 * <p>Uses Algorithm 1 and Equation 5 of arXiv:2210.05492 with kappa = 0,
 * the simpler option described in Section 2.2. For positive lambda, a policy
 * is proportional to exp(logPrior + Q / lambda). The experimental adaptive
 * temperature schedule is not included.</p>
 *
 * <p>Every iteration samples an independent population lambda and action for
 * each living nation, using values from the preceding iteration. All candidate
 * deviations then face that fixed joint sample. Each nation keeps one running
 * payoff vector, shared across its lambda types. Payoffs contain no KL penalty;
 * regularization enters only when forming a policy.</p>
 *
 * <p>The population distribution is common to all nations, including the
 * requested nation during search. After the final value update, agentLambda
 * determines its returned policy, without averaging earlier policies or lambda
 * types into the result. agentLambda need not occur in the population.</p>
 *
 * <p>Lambda must match the evaluator's payoff scale. Zero-prior candidates
 * retain zero policy mass; finite log priors are kept in log space. No prior
 * smoothing or exploration outside the generated pools is performed.</p>
 *
 * <p>All root trials use `Simulator.peek()`. A `RolloutEvaluator` may continue
 * successors on its own game using `advance()`. The seed restarts each search;
 * reproducibility also requires deterministic collaborators. Use separate
 * games and processors for parallel searches. A uniform anchor and short
 * rollouts remain baselines for the paper's learned policies and values;
 * convergence in seven-player Diplomacy is not guaranteed. The caller decides
 * whether the game has ended before requesting a search.</p>
 */
public final class DiLPiKLSearch {

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int candidateLimit;
    private final int iterations;
    private final LambdaDistribution population;
    private final double agentLambda;
    private final long seed;

    public DiLPiKLSearch(Evaluator evaluator, int candidateLimit, int iterations,
                         LambdaDistribution population, double agentLambda, long seed) {
        this(evaluator, new UniformActionGenerator(), candidateLimit, iterations,
                population, agentLambda, seed);
    }

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

    /**
     * Updates all living nations and returns the requested nation's policy.
     *
     * <p>The supplied game must be isolated and have no saved checkpoint.
     * Each candidate receives one payoff observation per iteration. Result
     * means are raw evaluator payoffs against the sampled opponent history.
     * The returned candidate order and original anchor priors are retained.</p>
     */
    public SearchResult search(Game game, Nation power) {
        GameState root = SearchSupport.root(game, power);
        Random random = new Random(seed);
        Map<Nation, List<ActionCandidate>> pools =
                SearchSupport.generatePools(root, generator, candidateLimit, random);
        Map<Nation, double[]> means = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            List<ActionCandidate> candidates = pools.get(nation);
            if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
                throw new IllegalStateException("No candidate has positive prior for " + nation);
            means.put(nation, new double[candidates.size()]);
        }

        Simulator simulator = new Simulator();
        for (int iteration = 0; iteration < iterations; iteration++) {
            Map<Nation, ActionCandidate> played = new EnumMap<>(Nation.class);
            for (Nation nation : pools.keySet()) {
                double lambda = population.sample(random);
                played.put(nation, policy(pools.get(nation), means.get(nation), lambda).sample(random));
            }

            Map<Nation, Double> observed = SearchSupport.values(evaluator, simulator.peek(game, played));
            for (Nation nation : pools.keySet()) {
                List<ActionCandidate> candidates = pools.get(nation);
                Map<Nation, ActionCandidate> deviation = new EnumMap<>(played);
                double[] values = means.get(nation);
                for (int index = 0; index < candidates.size(); index++) {
                    ActionCandidate candidate = candidates.get(index);
                    double payoff;
                    if (candidate == played.get(nation)) {
                        payoff = observed.get(nation);
                    } else {
                        deviation.put(nation, candidate);
                        payoff = SearchSupport.values(evaluator, simulator.peek(game, deviation)).get(nation);
                    }
                    double count = iteration + 1.0;
                    values[index] = values[index] * (iteration / count) + payoff / count;
                    if (!Double.isFinite(values[index]))
                        throw new IllegalStateException("Non-finite action value for " + nation);
                }
            }
        }

        double[] values = means.get(power);
        return new SearchResult(policy(pools.get(power), values, agentLambda),
                Arrays.stream(values).boxed().toList(), iterations);
    }

    private static ActionDistribution policy(List<ActionCandidate> candidates, double[] means, double lambda) {
        double best = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < candidates.size(); index++)
            if (Double.isFinite(candidates.get(index).logPrior()))
                best = Math.max(best, means[index]);

        double[] logits = new double[candidates.size()];
        double maximum = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < logits.length; index++) {
            double prior = candidates.get(index).logPrior();
            double difference = means[index] - best;
            double advantage = Double.isFinite(difference)
                    ? difference / lambda : means[index] / lambda - best / lambda;
            logits[index] = prior == Double.NEGATIVE_INFINITY
                    ? Double.NEGATIVE_INFINITY : advantage + prior;
            maximum = Math.max(maximum, logits[index]);
        }

        double total = 0.0;
        List<Double> probabilities = new ArrayList<>(logits.length);
        for (double logit : logits) {
            double weight = StrictMath.exp(logit - maximum);
            probabilities.add(weight);
            total += weight;
        }
        for (int index = 0; index < probabilities.size(); index++)
            probabilities.set(index, probabilities.get(index) / total);
        return new ActionDistribution(candidates, probabilities);
    }

}