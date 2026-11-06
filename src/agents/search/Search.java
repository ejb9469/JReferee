package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.simulation.Simulator;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Estimates a "best" response to fixed, independently sampled opponent policies.
 *
 * <p>Candidate pools are generated once at the root. Opponent policies use
 * their anchor priors conditioned on those pools. Every searching candidate
 * faces the same sampled opponent joint actions. Its value is the searching
 * nation's mean payoff.</p>
 *
 * <p>Exact ties in the sampled means split selection probability evenly.
 * The searching nation's priors are retained but impose no penalty here;
 * even a zero-prior root candidate may be selected. DiLPiKLSearch supplies
 * the iterative, regularized alternative.</p>
 *
 * <p>Root alternatives use Simulator.peek(). A RolloutEvaluator can then use
 * a separate game to continue each successor through Fall. Budgets multiply:
 * root candidates times opponent samples times the evaluator's own work.
 * The seed restarts on each search. Reproducibility also depends on the
 * supplied generator, evaluator, and game processors.</p>
 */
public class Search {

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int candidateLimit;
    private final int opponentSamples;
    private final long seed;

    public Search(Evaluator evaluator, int candidateLimit, int opponentSamples, long seed) {
        this(evaluator, new UniformActionGenerator(), candidateLimit, opponentSamples, seed);
    }

    public Search(Evaluator evaluator, ActionGenerator generator,
                  int candidateLimit, int opponentSamples, long seed) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.generator = Objects.requireNonNull(generator, "generator");
        if (candidateLimit < 1 || opponentSamples < 1)
            throw new IllegalArgumentException("candidateLimit and opponentSamples must be at least 1");
        this.candidateLimit = candidateLimit;
        this.opponentSamples = opponentSamples;
        this.seed = seed;
    }

    /**
     * Searches for one living nation using an isolated simulation game.
     *
     * <p>The game must have no saved checkpoint. Every root trial is restored
     * before its successor is evaluated, including when evaluation later fails.
     * Parallel searches must use separate games and thread-safe collaborators.</p>
     *
     * @param   game root simulation game with the desired processor settings
     * @param   power living nation whose action is being selected
     * @return  immutable candidates, mean payoffs, and selection probabilities
     */
    public SearchResult search(Game game, Nation power) {
        GameState root = SearchSupport.root(game, power);
        Random random = new Random(seed);
        Map<Nation, List<ActionCandidate>> pools =
                SearchSupport.generatePools(root, generator, candidateLimit, random);
        Map<Nation, ActionDistribution> opponents = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet())
            if (nation != power)
                opponents.put(nation, ActionDistribution.fromPriors(pools.get(nation)));

        List<Map<Nation, ActionCandidate>> samples = new ArrayList<>();
        for (int sample = 0; sample < opponentSamples; sample++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
            for (Nation nation : opponents.keySet())
                joint.put(nation, opponents.get(nation).sample(random));
            samples.add(Map.copyOf(joint));
        }

        Simulator simulator = new Simulator();
        List<ActionCandidate> candidates = pools.get(power);
        List<Double> means = new ArrayList<>(candidates.size());
        for (ActionCandidate candidate : candidates) {
            double mean = 0.0;
            for (Map<Nation, ActionCandidate> opponentsJoint : samples) {
                Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
                joint.putAll(opponentsJoint);
                joint.put(power, candidate);
                GameState successor = simulator.peek(game, joint);
                mean += SearchSupport.values(evaluator, successor).get(power) / opponentSamples;
                if (!Double.isFinite(mean))
                    throw new IllegalStateException("Non-finite search mean for " + power);
            }
            means.add(mean);
        }

        double best = Collections.max(means);
        int ties = 0;
        for (double mean : means)
            if (mean == best)
                ties++;
        List<Double> probabilities = new ArrayList<>(means.size());
        for (double mean : means)
            probabilities.add(mean == best ? 1.0 / ties : 0.0);
        return new SearchResult(new ActionDistribution(candidates, probabilities), means, opponentSamples);
    }

}