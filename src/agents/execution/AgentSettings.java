package agents.execution;

import agents.evaluation.*;
import agents.encoding.MovementEncoding;
import agents.encoding.NeuralSearchType;
import agents.generation.*;
import agents.policy.GruMovementPolicy;
import agents.policy.MovementPolicy;
import agents.search.*;
import domain.Nation;
import game.Game;
import game.GameState;
import game.GameState_Movement;
import misc.scoring.ScoringSystem;

import java.util.*;

/**
 * Root proposals, continuation policy and optional neural winter values.
 * Existing constructors retain the original stop-now scoring leaf.
 */
public record AgentSettings(
        Method method, int candidateLimit, int iterations,
        int rolloutCount, int rolloutLimit, PolicyOutput output,
        Generator rootGenerator, Generator rolloutGenerator,
        Optional<AgentModels> models, Optional<NeuralValueModel> valueModel
) {

    public enum Method { PRIOR, BEST_RESPONSE, DIL_PIKL }

    public enum Generator {
        UNIFORM, PLAUSIBLE, COHERENT_GRU_HYBRID;

        public ActionGenerator create() {
            return switch (this) {
                case UNIFORM -> new UniformActionGenerator();
                case PLAUSIBLE -> new PlausibleActionGenerator();
                case COHERENT_GRU_HYBRID -> throw new IllegalArgumentException(
                        "Coherent hybrid requires root models; use AgentSettings.createRootGenerator()");
            };
        }
    }

    public AgentSettings {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rootGenerator, "rootGenerator");
        Objects.requireNonNull(rolloutGenerator, "rolloutGenerator");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(valueModel, "valueModel");
        if (candidateLimit < 1 || iterations < 1 || rolloutCount < 1 || rolloutLimit < 1)
            throw new IllegalArgumentException("Agent budgets must be at least 1");
        if (valueModel.isPresent() && method == Method.PRIOR)
            throw new IllegalArgumentException("PRIOR does not use a value evaluator");
        if (rolloutGenerator == Generator.COHERENT_GRU_HYBRID)
            throw new IllegalArgumentException(
                    "Learned generation is movement-root only; rollouts must be UNIFORM or PLAUSIBLE");
        if (rootGenerator == Generator.COHERENT_GRU_HYBRID) {
            if (models.isEmpty() || models.orElseThrow().proposal().isEmpty())
                throw new IllegalArgumentException(
                        "Coherent hybrid requires both a frozen anchor and a proposal model");
            if (candidateLimit > PolicyActionGenerator.MAX_CANDIDATES)
                throw new IllegalArgumentException("Coherent hybrid supports at most "
                        + PolicyActionGenerator.MAX_CANDIDATES + " candidates");
            if (models.orElseThrow().proposal().orElseThrow() instanceof GruMovementPolicy gru
                    && gru.typeConditioned() && method != Method.DIL_PIKL)
                throw new IllegalArgumentException("Typed proposals require an explicit DiL search profile");
        } else if (models.isPresent() && models.orElseThrow().proposal().isPresent()) {
            throw new IllegalArgumentException("A proposal model requires COHERENT_GRU_HYBRID root generation");
        }

    }

    public AgentSettings(
            Method method, int candidateLimit, int iterations,
            int rolloutCount, int rolloutLimit, PolicyOutput output,
            Generator rootGenerator, Generator rolloutGenerator, Optional<AgentModels> models
    ) {
        this(method, candidateLimit, iterations, rolloutCount, rolloutLimit, output,
                rootGenerator, rolloutGenerator, models, Optional.empty());
    }

    public AgentSettings(
            Method method, int candidateLimit, int iterations,
            int rolloutCount, int rolloutLimit, PolicyOutput output,
            Generator rootGenerator, Generator rolloutGenerator
    ) {
        this(method, candidateLimit, iterations, rolloutCount, rolloutLimit, output,
                rootGenerator, rolloutGenerator, Optional.empty());
    }

    public AgentSettings(
            Method method, int candidateLimit, int iterations,
            int rolloutCount, int rolloutLimit, PolicyOutput output
    ) {
        this(method, candidateLimit, iterations, rolloutCount, rolloutLimit, output,
                Generator.UNIFORM, Generator.UNIFORM);
    }

    public static AgentSettings paper(int candidateLimit, int iterations) {
        return new AgentSettings(Method.DIL_PIKL, candidateLimit, iterations,
                1, candidateLimit, PolicyOutput.AVERAGE);
    }

    public AgentSettings withValueModel(NeuralValueModel model) {
        return new AgentSettings(this.method, this.candidateLimit, this.iterations,
                this.rolloutCount, this.rolloutLimit, this.output,
                this.rootGenerator, this.rolloutGenerator, this.models,
                Optional.of(Objects.requireNonNull(model, "model")));
    }

    public Evaluator createEvaluator(GameRunner.Options options, long seed) {

        Objects.requireNonNull(options, "options");
        if (this.method == Method.PRIOR)
            throw new IllegalStateException("PRIOR does not use a value evaluator");
        if (this.valueModel.isPresent()) {
            if (options.scoring() != ScoringSystem.SUM_OF_SQUARES)
                throw new IllegalArgumentException("The neural value model requires SUM_OF_SQUARES");
            return new NeuralRolloutEvaluator(this.valueModel.orElseThrow(), options.lastYear(),
                    this.rolloutGenerator.create(), options::createGame,
                    this.rolloutCount, this.rolloutLimit, seed);
        }
        return new RolloutEvaluator(new ScoringEvaluator(options.scoring()),
                this.rolloutGenerator.create(), options::createGame,
                this.rolloutCount, this.rolloutLimit, seed);

    }

    public ActionGenerator createRootGenerator() {

        if (this.rootGenerator == Generator.COHERENT_GRU_HYBRID) {
            AgentModels loaded = this.models.orElseThrow();
            MovementPolicy proposal = loaded.proposal().orElseThrow();
            if (proposal instanceof GruMovementPolicy gru && gru.typeConditioned())
                return (request, catalog) -> {
                    PaperSearchProfile profile = PaperSearchProfile.forState(request.state());
                    NeuralSearchType type = new NeuralSearchType(
                            switch (profile) { case OPENING -> 1; case STANDARD -> 2; },
                            switch (this.output) { case FINAL -> 1; case AVERAGE -> 2; },
                            profile.agentLambda(), profile.population().values(), profile.population().probabilities());
                    return hybrid(loaded, gru.conditioned(type)).generate(request, catalog);
                };
            return hybrid(loaded, proposal);
        }
        ActionGenerator generator = this.rootGenerator.create();
        if (this.models.isEmpty())
            return generator;
        var anchor = this.models.orElseThrow().anchor();
        return (request, catalog) -> {
            var candidates = generator.generate(request, catalog);
            if (!(request.state() instanceof GameState_Movement movement))
                return candidates;
            var encoding = new MovementEncoding(movement, request.power());
            var prepared = Objects.requireNonNull(anchor.prepare(encoding.input()), "prepared anchor");
            return candidates.stream().map(candidate -> new ActionCandidate(
                    candidate.power(), candidate.orders(),
                    prepared.logProbability(encoding.encode(candidate.orders())), candidate.source())).toList();
        };

    }

    private static ActionGenerator hybrid(AgentModels models, MovementPolicy proposal) {
        return new HybridActionGenerator(
                new CoherentPolicyActionGenerator(proposal, models.anchor()),
                new PlausibleActionGenerator(), models.anchor(), new UniformActionGenerator());
    }

    @Override
    public String toString() {

        return "AgentSettings[method=" + this.method + ", candidateLimit=" + this.candidateLimit
                + ", iterations=" + this.iterations + ", rolloutCount=" + this.rolloutCount
                + ", rolloutLimit=" + this.rolloutLimit + ", output=" + this.output
                + ", rootGenerator=" + this.rootGenerator + ", rolloutGenerator=" + this.rolloutGenerator
                + this.models.map(value -> ", models=" + value).orElse("")
                + this.valueModel.map(value -> ", valueModel=" + value.sha256()).orElse("") + "]";

    }

    AgentDecision decide(
            Game game, Nation power, GameRunner.Options options,
            long searchSeed, long rolloutSeed, long selectionSeed
    ) {

        long started = System.nanoTime();
        GameState root = game.snapshot();
        ActionGenerator generator = createRootGenerator();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        ActionGenerator recording = (request, catalog) -> {
            List<ActionCandidate> candidates = List.copyOf(generator.generate(request, catalog));
            if (pools.putIfAbsent(request.power(), candidates) != null)
                throw new IllegalStateException("Root pool generated twice for " + request.power());
            return candidates;
        };
        ActionDistribution distribution;
        Optional<SearchResult> searchResult;

        if (this.method == Method.PRIOR) {
            LegalOrderCatalog catalog = new LegalOrderGenerator().generate(root, power);
            distribution = ActionDistribution.fromPriors(recording.generate(
                    new ActionGenerationRequest(root, power, this.candidateLimit, searchSeed), catalog));
            searchResult = Optional.empty();
        } else {
            Evaluator evaluator = createEvaluator(options, rolloutSeed);
            PolicySearch search = switch (this.method) {
                case BEST_RESPONSE -> new BestResponseSearch(
                        evaluator, recording, this.candidateLimit, this.iterations, searchSeed);
                case DIL_PIKL -> PaperSearchProfile.forState(root).solver(
                        evaluator, recording, this.candidateLimit, this.iterations, searchSeed, this.output);
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