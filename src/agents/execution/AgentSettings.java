package agents.execution;

import agents.encoding.MovementEncoding;
import agents.evaluation.Evaluator;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.policy.MovementPolicy;
import agents.search.*;
import domain.Nation;
import game.Game;
import game.GameState;
import game.GameState_Movement;

import java.util.*;

/**
 * Configuration for one recorded-game seat.<br><br>
 *
 * Root generation and rollout continuation are independent.
 * Optional movement models supply a frozen anchor and coherent proposals.
 * Nonmovement roots retain their existing generators and priors.
 */
public record AgentSettings(
        Method method,
        int candidateLimit,
        int iterations,
        int rolloutCount,
        int rolloutLimit,
        PolicyOutput output,
        Generator rootGenerator,
        Generator rolloutGenerator,
        Optional<AgentModels> models
) {


    // Available methods and generators \\

    public enum Method {
        PRIOR,
        BEST_RESPONSE,
        DIL_PIKL
    }

    public enum Generator {

        UNIFORM,
        PLAUSIBLE,
        COHERENT_GRU_HYBRID;

        /**
         * Constructs a model-free generator.
         * Learned roots require AgentSettings.createRootGenerator().
         */
        public ActionGenerator create() {

            return switch (this) {

                case UNIFORM -> new UniformActionGenerator();
                case PLAUSIBLE -> new PlausibleActionGenerator();

                case COHERENT_GRU_HYBRID -> throw new IllegalArgumentException(
                        "Coherent hybrid requires root models; use "
                                + "AgentSettings.createRootGenerator()"
                );

            };

        }

    }


    // Constructors \\

    public AgentSettings {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rootGenerator, "rootGenerator");
        Objects.requireNonNull(rolloutGenerator, "rolloutGenerator");
        Objects.requireNonNull(models, "models");

        if (candidateLimit < 1 || iterations < 1
                || rolloutCount < 1 || rolloutLimit < 1)
            throw new IllegalArgumentException(
                    "Agent budgets must be at least 1");

        if (rolloutGenerator == Generator.COHERENT_GRU_HYBRID)
            throw new IllegalArgumentException(
                    "Learned generation is movement-root only; "
                            + "rollouts must be UNIFORM or PLAUSIBLE"
            );

        if (rootGenerator == Generator.COHERENT_GRU_HYBRID) {

            if (models.isEmpty() || models.orElseThrow().proposal().isEmpty())
                throw new IllegalArgumentException(
                        "Coherent hybrid requires a frozen anchor "
                                + "and a proposal model"
                );

            if (candidateLimit > PolicyActionGenerator.MAX_CANDIDATES)
                throw new IllegalArgumentException(
                        "Coherent hybrid supports at most "
                                + PolicyActionGenerator.MAX_CANDIDATES
                                + " candidates"
                );

        } else if (models.isPresent()
                && models.orElseThrow().proposal().isPresent()) {

            throw new IllegalArgumentException(
                    "A proposal model requires "
                            + "COHERENT_GRU_HYBRID root generation"
            );

        }

    }

    /**
     * Preserves the existing model-free constructor.
     */
    public AgentSettings(
            Method method,
            int candidateLimit,
            int iterations,
            int rolloutCount,
            int rolloutLimit,
            PolicyOutput output,
            Generator rootGenerator,
            Generator rolloutGenerator
    ) {
        this(
                method, candidateLimit, iterations,
                rolloutCount, rolloutLimit, output,
                rootGenerator, rolloutGenerator, Optional.empty()
        );
    }

    /**
     * Preserves uniform root and continuation generation for existing callers.
     */
    public AgentSettings(
            Method method,
            int candidateLimit,
            int iterations,
            int rolloutCount,
            int rolloutLimit,
            PolicyOutput output
    ) {
        this(
                method, candidateLimit, iterations,
                rolloutCount, rolloutLimit, output,
                Generator.UNIFORM, Generator.UNIFORM
        );
    }

    public static AgentSettings paper(int candidateLimit, int iterations) {
        return new AgentSettings(
                Method.DIL_PIKL, candidateLimit, iterations,
                1, candidateLimit, PolicyOutput.AVERAGE
        );
    }


    // Root generation \\

    /**
     * Creates the deciding seat's own and modeled-opponent generator.
     * Movement policies are never prepared for retreats or adjustments.
     */
    public ActionGenerator createRootGenerator() {

        if (this.rootGenerator == Generator.COHERENT_GRU_HYBRID) {

            AgentModels loaded = this.models.orElseThrow();

            return new HybridActionGenerator(
                    new CoherentPolicyActionGenerator(
                            loaded.proposal().orElseThrow(),
                            loaded.anchor()
                    ),
                    new PlausibleActionGenerator(),
                    loaded.anchor(),
                    new UniformActionGenerator()
            );

        }

        ActionGenerator generator = this.rootGenerator.create();

        if (this.models.isEmpty())
            return generator;

        MovementPolicy anchor = this.models.orElseThrow().anchor();

        return (request, catalog) -> {

            List<ActionCandidate> candidates = generator.generate(
                    request, catalog);

            if (!(request.state() instanceof GameState_Movement movement))
                return candidates;

            MovementEncoding encoding = new MovementEncoding(
                    movement, request.power());

            MovementPolicy.Prepared prepared = Objects.requireNonNull(
                    anchor.prepare(encoding.input()), "prepared anchor");

            List<ActionCandidate> anchored = new ArrayList<>();

            for (ActionCandidate candidate : candidates) {

                double logPrior = prepared.logProbability(
                        encoding.encode(candidate.orders()));

                anchored.add(new ActionCandidate(
                        candidate.power(),
                        candidate.orders(),
                        logPrior,
                        candidate.source()
                ));

            }

            return List.copyOf(anchored);

        };

    }


    // Decision execution \\

    AgentDecision decide(
            Game game,
            Nation power,
            GameRunner.Options options,
            long searchSeed,
            long rolloutSeed,
            long selectionSeed
    ) {

        long started = System.nanoTime();
        GameState root = game.snapshot();

        ActionGenerator generator = this.createRootGenerator();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);

        ActionGenerator recording = (request, catalog) -> {

            List<ActionCandidate> candidates = List.copyOf(
                    generator.generate(request, catalog));

            if (pools.putIfAbsent(request.power(), candidates) != null)
                throw new IllegalStateException(
                        "Root pool generated twice for " + request.power());

            return candidates;

        };

        ActionDistribution distribution;
        Optional<SearchResult> searchResult;

        if (this.method == Method.PRIOR) {

            LegalOrderCatalog catalog = new LegalOrderGenerator().generate(
                    root, power);

            List<ActionCandidate> candidates = recording.generate(
                    new ActionGenerationRequest(
                            root, power, this.candidateLimit, searchSeed
                    ),
                    catalog
            );

            distribution = ActionDistribution.fromPriors(candidates);
            searchResult = Optional.empty();

        } else {

            Evaluator evaluator = new RolloutEvaluator(
                    new ScoringEvaluator(options.scoring()),
                    this.rolloutGenerator.create(),
                    options::createGame,
                    this.rolloutCount,
                    this.rolloutLimit,
                    rolloutSeed
            );

            PolicySearch search = switch (this.method) {

                case BEST_RESPONSE -> new BestResponseSearch(
                        evaluator, recording,
                        this.candidateLimit, this.iterations, searchSeed
                );

                case DIL_PIKL -> PaperSearchProfile.forState(root).solver(
                        evaluator, recording,
                        this.candidateLimit, this.iterations,
                        searchSeed, this.output
                );

                case PRIOR -> throw new IllegalStateException(
                        "Prior sampling does not run a policy search"
                );

            };

            SearchResult result = search.search(game, power);

            distribution = result.distribution();
            searchResult = Optional.of(result);

        }

        ActionCandidate selected = distribution.sample(
                new Random(selectionSeed));

        return new AgentDecision(
                root,
                this,
                searchSeed,
                rolloutSeed,
                selectionSeed,
                System.nanoTime() - started,
                pools,
                distribution,
                searchResult,
                distribution.candidates().indexOf(selected)
        );

    }


    // Configuration identity \\

    @Override
    public String toString() {

        // Preserve existing configuration-derived game IDs without models.
        return "AgentSettings[method=" + this.method
                + ", candidateLimit=" + this.candidateLimit
                + ", iterations=" + this.iterations
                + ", rolloutCount=" + this.rolloutCount
                + ", rolloutLimit=" + this.rolloutLimit
                + ", output=" + this.output
                + ", rootGenerator=" + this.rootGenerator
                + ", rolloutGenerator=" + this.rolloutGenerator
                + this.models.map(value -> ", models=" + value).orElse("")
                + "]";

    }

}