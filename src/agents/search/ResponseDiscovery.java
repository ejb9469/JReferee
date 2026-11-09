package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
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
public class ResponseDiscovery {

    private final Evaluator evaluator;
    private final ActionGenerator generator;
    private final int proposalLimit;
    private final int opponentSamples;
    private final int additionsPerNation;
    private final double minimumGain;

    public ResponseDiscovery(Evaluator evaluator, ActionGenerator generator, int proposalLimit,
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

    public enum Decision { DUPLICATE, ZERO_PRIOR, BELOW_GAIN, ADDITION_LIMIT, SELECTED }

    /**
     * One generated proposal and its disposition.
     * Duplicates and zero-prior actions are not evaluated.
     */
    public record Proposal(ActionCandidate candidate, Decision decision, Optional<Response> measured) {
        public Proposal {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(measured, "measured");
            boolean evaluated = decision != Decision.DUPLICATE && decision != Decision.ZERO_PRIOR;
            if (evaluated != measured.isPresent()
                    || measured.isPresent() && !measured.get().candidate().equals(candidate))
                throw new IllegalArgumentException("Proposal measurement does not match its disposition");
        }
    }

    /**
     * Records every supplied proposal, including unmeasured rejections.
     * Selected responses remain ranked by measured payoff.
     */
    public record Report(long seed, int proposalLimit, int opponentSamples, int additionsPerNation,
                         double minimumGain, Map<Nation, List<Proposal>> proposals) {
        public Report {
            if (proposalLimit < 1 || opponentSamples < 1 || additionsPerNation < 1
                    || !Double.isFinite(minimumGain) || minimumGain < 0)
                throw new IllegalArgumentException("Invalid response report settings");
            Map<Nation, List<Proposal>> copy = new EnumMap<>(Nation.class);
            proposals.forEach((nation, entries) -> {
                List<Proposal> list = List.copyOf(entries);
                if (list.stream().anyMatch(entry -> entry.candidate().power() != nation))
                    throw new IllegalArgumentException("Proposal report power differs from its map key");
                copy.put(nation, list);
            });
            proposals = Collections.unmodifiableMap(copy);
        }

        public Map<Nation, List<Response>> responses() {
            Map<Nation, List<Response>> result = new EnumMap<>(Nation.class);
            proposals.forEach((nation, entries) -> result.put(nation, entries.stream()
                    .filter(entry -> entry.decision() == Decision.SELECTED)
                    .map(entry -> entry.measured().orElseThrow()).toList()));
            return Collections.unmodifiableMap(result);
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
        return discover(game, solved, prior, seed, trials(game)).responses();
    }

    JointActionEvaluator trials(Game game) {
        return JointActionEvaluator.uncached(game, evaluator);
    }

    /**
     * Includes proposal diagnostics and shares the supplied root evaluator.
     * Pool-cap decisions belong to AdaptiveDiLPiKLSearch.
     */
    public Report discover(Game game, JointSearchResult solved, ActionPrior prior, long seed,
                           JointActionEvaluator trials) {
        Objects.requireNonNull(trials, "trials").requireContext(game, evaluator);
        try (var discovery = trials.measure("response discovery")) {
            return inspect(game, solved, prior, seed, trials);
        }
    }

    private Report inspect(Game game, JointSearchResult solved, ActionPrior prior, long seed,
                           JointActionEvaluator trials) {
        GameState root = SearchSupport.root(game);
        if (!(root instanceof GameState_Movement))
            throw new IllegalArgumentException("Response discovery currently requires movement");
        Objects.requireNonNull(solved, "solved");
        Map<Nation, List<ActionCandidate>> supplied = new EnumMap<>(Nation.class);
        solved.results().forEach((nation, result) -> supplied.put(nation, result.distribution().candidates()));
        Map<Nation, List<ActionCandidate>> pools = SearchSupport.pools(root, supplied);
        SearchSupport.requireAnchor(pools, prior);
        Random random = new Random(seed);
        Map<Nation, List<ActionCandidate>> proposals;
        try (var generation = trials.measure("candidate generation")) {
            proposals = SearchSupport.generatePools(root, generator, proposalLimit, random);
        }

        // All nations discover responses against this same frozen policy profile.
        List<Map<Nation, ActionCandidate>> samples = new ArrayList<>();
        for (int sample = 0; sample < opponentSamples; sample++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
            for (Nation nation : pools.keySet())
                joint.put(nation, solved.populationPolicies().get(nation).sample(random));
            samples.add(Collections.unmodifiableMap(joint));
        }

        Map<Nation, List<Proposal>> result = new EnumMap<>(Nation.class);
        for (Nation nation : pools.keySet()) {
            Set<Map<OrderForm, Integer>> seen = new HashSet<>();
            pools.get(nation).forEach(candidate -> seen.add(SearchSupport.key(candidate)));
            List<ActionCandidate> fresh = new ArrayList<>();
            List<Proposal> assessed = new ArrayList<>();
            for (ActionCandidate proposal : proposals.get(nation)) {
                ActionCandidate candidate = SearchSupport.anchored(proposal, prior);
                if (!Double.isFinite(candidate.logPrior()))
                    assessed.add(new Proposal(candidate, Decision.ZERO_PRIOR, Optional.empty()));
                else if (!seen.add(SearchSupport.key(candidate)))
                    assessed.add(new Proposal(candidate, Decision.DUPLICATE, Optional.empty()));
                else
                    fresh.add(candidate);
            }
            List<Response> responses = new ArrayList<>();
            if (!fresh.isEmpty()) {
                double incumbent = Double.NEGATIVE_INFINITY;
                for (ActionCandidate candidate : pools.get(nation))
                    if (Double.isFinite(candidate.logPrior()))
                        incumbent = Math.max(incumbent, SearchSupport.meanValue(trials, candidate, samples));
                for (ActionCandidate candidate : fresh) {
                    double mean = SearchSupport.meanValue(trials, candidate, samples);
                    responses.add(new Response(candidate, mean, incumbent));
                }
                responses.sort(Comparator.comparingDouble(Response::meanValue).reversed());
                int selected = 0;
                for (Response response : responses) {
                    Decision decision = response.meanValue() <= incumbent + minimumGain
                            ? Decision.BELOW_GAIN : selected < additionsPerNation
                            ? Decision.SELECTED : Decision.ADDITION_LIMIT;
                    if (decision == Decision.SELECTED)
                        selected++;
                    assessed.add(new Proposal(response.candidate(), decision, Optional.of(response)));
                }
            }
            result.put(nation, List.copyOf(assessed));
        }
        return new Report(seed, proposalLimit, opponentSamples, additionsPerNation, minimumGain, result);
    }

}