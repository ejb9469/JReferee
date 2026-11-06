package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import game.*;

import java.util.*;

/**
 * Looks for omitted complete actions against frozen population policies.
 *
 * <p>The proposal generator supplies coordinated whole-nation replacements.
 * A larger proposal limit and fresh seeds can expose alternatives missing from
 * the initial pools. Construction is heuristic; response evaluation determines
 * which proposals are retained. No exhaustive best-response claim is made.</p>
 *
 * <p>Every proposal and incumbent faces the same opponent samples. Incumbents
 * are measured again; the preceding solver's historical Q values are not used.
 * The evaluator may add its own continuation noise.</p>
 *
 * <p>Selection uses raw payoff improvement over the best positive-prior
 * incumbent. This is a pool-expansion heuristic, not an optimality test for the
 * regularized game. Small samples can miss responses or promote noisy estimates.
 * Future work includes policy-directed bundle construction and held-out checks.</p>
 */
public class ResponseSearch {

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int proposalLimit;
    private final int opponentSamples;
    private final int additionsPerNation;
    private final double minimumGain;

    public ResponseSearch(Evaluator evaluator, ActionGenerator generator, int proposalLimit,
                          int opponentSamples, int additionsPerNation, double minimumGain) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.generator = Objects.requireNonNull(generator, "generator");
        if (proposalLimit < 1 || opponentSamples < 1 || additionsPerNation < 1
                || !Double.isFinite(minimumGain) || minimumGain < 0.0)
            throw new IllegalArgumentException("Invalid response-search budget or gain");
        this.proposalLimit = proposalLimit;
        this.opponentSamples = opponentSamples;
        this.additionsPerNation = additionsPerNation;
        this.minimumGain = minimumGain;
    }

    public record Response(ActionCandidate candidate, double meanValue, double incumbentValue) {
        public Response {
            Objects.requireNonNull(candidate, "candidate");
            if (!Double.isFinite(meanValue) || !Double.isFinite(incumbentValue))
                throw new IllegalArgumentException("Response values must be finite");
        }
    }

    /**
     * Returns bounded additions for each living nation without changing any pool.
     * The prior must be bound to this root and shared with the initial candidates.
     * Each trial restores the supplied game before invoking the evaluator.
     */
    public Map<Nation, List<Response>> discover(
            Game game, JointSearchResult solved, ActionPrior prior, long seed
    ) {
        GameState root = SearchSupport.root(game);
        if (!(root instanceof GameState_Movement))
            throw new IllegalArgumentException("Response discovery currently requires movement");
        Objects.requireNonNull(solved, "solved");
        Map<Nation, List<ActionCandidate>> supplied = new EnumMap<>(Nation.class);
        solved.results().forEach((nation, result) -> supplied.put(nation, result.distribution().candidates()));
        Map<Nation, List<ActionCandidate>> pools = SearchSupport.pools(root, supplied);
        SearchSupport.requireAnchor(pools, prior);
        Random random = new Random(seed);
        Map<Nation, List<ActionCandidate>> proposals =
                SearchSupport.generatePools(root, generator, proposalLimit, random);

        // All nations discover responses against this same frozen policy profile.
        List<Map<Nation, ActionCandidate>> samples = new ArrayList<>();
        for (int sample = 0; sample < opponentSamples; sample++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
            for (Nation nation : pools.keySet())
                joint.put(nation, solved.populationPolicies().get(nation).sample(random));
            samples.add(Collections.unmodifiableMap(joint));
        }

        Map<Nation, List<Response>> result = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            Set<Map<OrderForm, Integer>> seen = new HashSet<>();
            pools.get(nation).forEach(candidate -> seen.add(SearchSupport.key(candidate)));
            List<ActionCandidate> fresh = new ArrayList<>();
            for (ActionCandidate proposal : proposals.get(nation)) {
                ActionCandidate candidate = SearchSupport.anchored(proposal, prior);
                if (Double.isFinite(candidate.logPrior()) && seen.add(SearchSupport.key(candidate)))
                    fresh.add(candidate);
            }
            List<Response> responses = new ArrayList<>();
            if (!fresh.isEmpty()) {
                double incumbent = Double.NEGATIVE_INFINITY;
                for (ActionCandidate candidate : pools.get(nation))
                    if (Double.isFinite(candidate.logPrior()))
                        incumbent = Math.max(incumbent, value(game, candidate, samples));
                for (ActionCandidate candidate : fresh) {
                    double mean = value(game, candidate, samples);
                    if (mean > incumbent + minimumGain)
                        responses.add(new Response(candidate, mean, incumbent));
                }
                responses.sort(Comparator.comparingDouble(Response::meanValue).reversed());
            }
            result.put(nation, List.copyOf(responses.subList(0,
                    Math.min(additionsPerNation, responses.size()))));
        }
        return Collections.unmodifiableMap(result);
    }

    private double value(Game game, ActionCandidate candidate,
                         List<Map<Nation, ActionCandidate>> samples) {
        Simulator simulator = new Simulator();
        double mean = 0.0;
        for (int index = 0; index < samples.size(); index++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(samples.get(index));
            joint.put(candidate.power(), candidate);
            double payoff = SearchSupport.values(evaluator, simulator.peek(game, joint)).get(candidate.power());
            double count = index + 1.0;
            mean = mean * (index / count) + payoff / count;
            if (!Double.isFinite(mean))
                throw new IllegalStateException("Non-finite response value for " + candidate.power());
        }
        return mean;
    }
}