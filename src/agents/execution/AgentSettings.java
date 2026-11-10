package agents.execution;

import agents.evaluation.Evaluator;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.search.*;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Baseline agent configuration for recorded games.
 *
 * <p>Root proposals and rollout continuations have separate generators.
 * Both choices use the convoy-free uniform anchor. PRIOR samples that anchor
 * within its retained pool and performs no payoff evaluation. The other
 * methods use year-end rollouts. Search and rollout budgets are separate.</p>
 */
public record AgentSettings(Method method, int candidateLimit, int iterations,
                            int rolloutCount, int rolloutLimit, PolicyOutput output,
                            Generator rootGenerator, Generator rolloutGenerator) {

    public enum Method { PRIOR, BEST_RESPONSE, DIL_PIKL }

    public enum Generator {
        UNIFORM, PLAUSIBLE;

        public ActionGenerator create() {
            return switch (this) {
                case UNIFORM -> new UniformActionGenerator();
                case PLAUSIBLE -> new PlausibleActionGenerator();
            };
        }
    }

    public AgentSettings {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rootGenerator, "rootGenerator");
        Objects.requireNonNull(rolloutGenerator, "rolloutGenerator");
        if (candidateLimit < 1 || iterations < 1 || rolloutCount < 1 || rolloutLimit < 1)
            throw new IllegalArgumentException("Agent budgets must be at least 1");
    }

    /**
     * Retains uniform root and rollout generation for existing callers.
     */
    public AgentSettings(Method method, int candidateLimit, int iterations,
                         int rolloutCount, int rolloutLimit, PolicyOutput output) {
        this(method, candidateLimit, iterations, rolloutCount, rolloutLimit, output,
                Generator.UNIFORM, Generator.UNIFORM);
    }

    /**
     * Small fixed-pool baseline with averaged playing policies.
     * The opening or standard paper profile is selected at every decision.
     */
    public static AgentSettings paper(int candidateLimit, int iterations) {
        return new AgentSettings(Method.DIL_PIKL, candidateLimit, iterations,
                1, candidateLimit, PolicyOutput.AVERAGE);
    }

    AgentDecision decide(Game game, Nation power, GameRunner.Options options,
                         long searchSeed, long rolloutSeed, long selectionSeed) {
        long started = System.nanoTime();
        GameState root = game.snapshot();
        ActionGenerator generator = rootGenerator.create();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        ActionGenerator recording = (request, catalog) -> {
            List<ActionCandidate> candidates = List.copyOf(generator.generate(request, catalog));
            if (pools.putIfAbsent(request.power(), candidates) != null)
                throw new IllegalStateException("Root pool generated twice for " + request.power());
            return candidates;
        };
        ActionDistribution distribution;
        Optional<SearchResult> searchResult;

        if (method == Method.PRIOR) {
            LegalOrderCatalog catalog = new LegalOrderGenerator().generate(root, power);
            distribution = ActionDistribution.fromPriors(recording.generate(
                    new ActionGenerationRequest(root, power, candidateLimit, searchSeed), catalog));
            searchResult = Optional.empty();
        } else {
            Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(options.scoring()),
                    rolloutGenerator.create(), options::createGame, rolloutCount, rolloutLimit, rolloutSeed);
            PolicySearch search = switch (method) {
                case BEST_RESPONSE -> new BestResponseSearch(
                        evaluator, recording, candidateLimit, iterations, searchSeed);
                case DIL_PIKL -> PaperSearchProfile.forState(root).solver(
                        evaluator, recording, candidateLimit, iterations, searchSeed, output);
                case PRIOR -> throw new IllegalStateException("Prior sampling does not run a policy search");
            };
            SearchResult result = search.search(game, power);
            distribution = result.distribution();
            searchResult = Optional.of(result);
        }

        ActionCandidate selected = distribution.sample(new Random(selectionSeed));
        return new AgentDecision(root, this, searchSeed, rolloutSeed, selectionSeed,
                System.nanoTime() - started, pools, distribution, searchResult,
                distribution.candidates().indexOf(selected));
    }

}