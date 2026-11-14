package agents.execution;

import agents.encoding.MovementEncoding;
import agents.encoding.MovementInput;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.policy.*;
import agents.search.*;
import domain.Nation;
import game.*;
import misc.scoring.ScoringSystem;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Generates complete movement actions and retains their scores for inspection.
 * Rankings apply to the retained pool. No global top-action guarantee is made.
 */
public final class ProposalInspector {

    public enum Method {
        UNIFORM, PLAUSIBLE, FULL_UNIFORM, LEARNED, HYBRID, GRU, GRU_HYBRID,
        COHERENT_GRU, COHERENT_GRU_HYBRID
    }
    public enum Ranking { LINEAR_POLICY, GRU_POLICY, ANCHOR, SEARCH }

    public record Options(int candidates, int iterations, int rollouts, int rolloutCandidates,
                          long poolSeed, long searchSeed, long rolloutSeed, boolean search) {
        public Options {
            if (candidates < 1 || candidates > 64 || iterations < 1 || iterations > 256
                    || rollouts < 1 || rollouts > 8 || rolloutCandidates < 1 || rolloutCandidates > 64)
                throw new IllegalArgumentException("Budgets: candidates 1..64, iterations 1..256, rollouts 1..8, rollout candidates 1..64");
        }
        public static Options defaults() { return new Options(8, 16, 1, 8, 7, 7, 7, false); }
    }

    public record SearchScore(double probability, double meanValue, int samples) {
        public SearchScore {
            if (!Double.isFinite(probability) || probability < 0 || probability > 1
                    || !Double.isFinite(meanValue) || samples < 1)
                throw new IllegalArgumentException("Invalid search score");
        }
    }

    public record Proposal(int index, ActionCandidate action, List<String> orders,
                           double logLinear, double logGru, double anchorPoolProbability,
                           Optional<SearchScore> search, MovementCoordination.Counts coordination,
                           List<MovementFeasibility.Finding> findings) {
        public Proposal {
            Objects.requireNonNull(action, "action");
            orders = List.copyOf(orders);
            Objects.requireNonNull(search, "search");
            Objects.requireNonNull(coordination, "coordination");
            findings = List.copyOf(findings);
            if (index < 0 || orders.size() != action.orders().size()
                    || Double.isNaN(logLinear) || logLinear > 0 || Double.isNaN(logGru) || logGru > 0
                    || !Double.isFinite(anchorPoolProbability) || anchorPoolProbability < 0 || anchorPoolProbability > 1)
                throw new IllegalArgumentException("Invalid proposal scores");
        }
        public double score(Ranking ranking) {
            return switch (Objects.requireNonNull(ranking, "ranking")) {
                case LINEAR_POLICY -> logLinear;
                case GRU_POLICY -> logGru;
                case ANCHOR -> action.logPrior();
                case SEARCH -> search.orElseThrow(() -> new IllegalStateException("Search was not requested")).probability();
            };
        }
    }

    public record Generation(CoherentPolicyActionGenerator.Result learned,
                             Optional<HybridActionGenerator.Result> hybrid) {
        public Generation {
            Objects.requireNonNull(learned);
            Objects.requireNonNull(hybrid);
        }
    }

    public static final class CoherentExhaustion extends IllegalStateException {
        private final Nation power;
        private final CoherentPolicyActionGenerator.Result generation;

        private CoherentExhaustion(Nation power, CoherentPolicyActionGenerator.Result generation) {
            super("Coherent construction exhausted its budget without a candidate; use COHERENT_GRU_HYBRID for heuristic coverage");
            this.power = power;
            this.generation = generation;
        }
        public Nation power() { return power; }
        public CoherentPolicyActionGenerator.Result generation() { return generation; }
    }

    public record Inspection(GameState_Movement root, Nation power, Method method, Options options,
                             Map<String, String> models, Map<Nation, List<ActionCandidate>> pools,
                             List<Proposal> proposals, Optional<JointSearchResult> search,
                             Optional<JointActionEvaluator.Counts> work, Map<Nation, Generation> generation) {
        public Inspection {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(power, "power");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(options, "options");
            models = Map.copyOf(models);
            Map<Nation, List<ActionCandidate>> copied = new EnumMap<>(Nation.class);
            pools.forEach((nation, pool) -> copied.put(nation, List.copyOf(pool)));
            pools = Collections.unmodifiableMap(copied);
            proposals = List.copyOf(proposals);
            Objects.requireNonNull(search, "search");
            Objects.requireNonNull(work, "work");
            generation = Map.copyOf(generation);
            boolean coherent = method == Method.COHERENT_GRU || method == Method.COHERENT_GRU_HYBRID;
            if (!generation.keySet().equals(coherent ? pools.keySet() : Set.of()))
                throw new IllegalArgumentException("Construction diagnostics do not match the method");
            if (!pools.keySet().equals(options.search() ? root.aliveNations() : Set.of(power))
                    || search.isPresent() != options.search() || work.isPresent() != options.search()
                    || proposals.isEmpty() || proposals.size() != pools.get(power).size())
                throw new IllegalArgumentException("Inspection does not match its request");
            for (int index = 0; index < proposals.size(); index++)
                if (proposals.get(index).index() != index || !proposals.get(index).action().equals(pools.get(power).get(index))
                        || proposals.get(index).search().isPresent() != options.search())
                    throw new IllegalArgumentException("Proposal indexing does not match its pool");
        }
        /** Ties retain generation order. Probabilities are never renormalized to the displayed subset. */
        public List<Proposal> top(int count, Ranking ranking) {
            if (count < 1 || count > options.candidates()) throw new IllegalArgumentException("Top count must be 1..candidate budget");
            if (ranking == Ranking.SEARCH && search.isEmpty()) throw new IllegalStateException("Search was not requested");
            Objects.requireNonNull(ranking, "ranking");
            return proposals.stream().sorted(Comparator.comparingDouble((Proposal p) -> p.score(ranking))
                    .reversed().thenComparingInt(Proposal::index)).limit(count).toList();
        }
    }

    private final MovementPolicy linear, anchor, gru;
    private final Map<String, String> models;

    /** Supplied policies must remain immutable throughout an inspection. */
    public ProposalInspector(MovementPolicy linear, MovementPolicy anchor, MovementPolicy gru) {
        this(linear, anchor, gru, Map.of());
    }

    private ProposalInspector(MovementPolicy linear, MovementPolicy anchor, MovementPolicy gru, Map<String, String> models) {
        this.linear = Objects.requireNonNull(linear, "linear");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.gru = Objects.requireNonNull(gru, "gru");
        this.models = Map.copyOf(models);
    }

    public static ProposalInspector load(Path linearPath, Path anchorPath, Path gruPath) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("linear", hash(linearPath)); hashes.put("anchor", hash(anchorPath)); hashes.put("gru", hash(gruPath));
        var linear = LinearMovementPolicy.load(linearPath);
        var anchor = linearPath.toAbsolutePath().normalize().equals(anchorPath.toAbsolutePath().normalize())
                ? linear : LinearMovementPolicy.load(anchorPath);
        var gru = GruMovementPolicy.load(gruPath);
        if (!hashes.get("linear").equals(hash(linearPath)) || !hashes.get("anchor").equals(hash(anchorPath))
                || !hashes.get("gru").equals(gru.sha256()))
            throw new IOException("Model changed while loading");
        hashes.put("checkpoint", gru.checkpointSha256());
        hashes.put("dataset", gru.datasetIndexSha256());
        return new ProposalInspector(linear, anchor, gru, hashes);
    }

    public Map<String, String> models() { return models; }

    public Inspection inspect(GameState_Movement root, Nation power, Method method, Options options) {
        Objects.requireNonNull(root, "root"); Objects.requireNonNull(method, "method"); Objects.requireNonNull(options, "options");
        if (!root.aliveNations().contains(Objects.requireNonNull(power, "power")))
            throw new IllegalArgumentException("The requested power is eliminated");
        MovementPolicy linear = cached(this.linear);
        MovementPolicy anchor = this.anchor == this.linear ? linear : cached(this.anchor);
        MovementPolicy gru = this.gru == this.linear ? linear : this.gru == this.anchor ? anchor : cached(this.gru);
        ActionGenerator generator = switch (method) {
            case UNIFORM -> new UniformActionGenerator();
            case PLAUSIBLE -> new PlausibleActionGenerator();
            case FULL_UNIFORM -> new PolicyActionGenerator(new LinearMovementPolicy(1024), anchor);
            case LEARNED, GRU -> new PolicyActionGenerator(method == Method.GRU ? gru : linear, anchor);
            case HYBRID, GRU_HYBRID -> new HybridActionGenerator(method == Method.GRU_HYBRID ? gru : linear, anchor);
            case COHERENT_GRU, COHERENT_GRU_HYBRID -> new CoherentPolicyActionGenerator(gru, anchor);
        };
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        Map<Nation, Generation> generation = new EnumMap<>(Nation.class);
        MovementEncoding focus = null;
        Random random = new Random(options.poolSeed());
        for (Nation nation : Nation.values()) {
            if (!root.aliveNations().contains(nation)) continue;
            long seed = random.nextLong();
            if (!options.search() && nation != power) continue;
            var encoding = new MovementEncoding(root, nation);
            if (nation == power) focus = encoding;
            var request = new ActionGenerationRequest(root, nation, options.candidates(), seed);
            List<ActionCandidate> raw;
            if (generator instanceof CoherentPolicyActionGenerator coherent) {
                var learned = coherent.propose(request, encoding);
                Optional<HybridActionGenerator.Result> hybrid = Optional.empty();
                if (method == Method.COHERENT_GRU_HYBRID) {
                    var combined = new HybridActionGenerator((r, c) -> learned.candidates(),
                            new PlausibleActionGenerator(), anchor, new UniformActionGenerator());
                    hybrid = Optional.of(combined.propose(request, encoding));
                }
                raw = hybrid.isPresent() ? hybrid.orElseThrow().candidates() : learned.candidates();
                generation.put(nation, new Generation(learned, hybrid));
                if (raw.isEmpty())
                    throw new CoherentExhaustion(nation, learned);
            } else raw = generator instanceof PolicyActionGenerator policy ? policy.propose(request, encoding).candidates()
                    : generator instanceof HybridActionGenerator hybrid ? hybrid.propose(request, encoding).candidates()
                    : generator.generate(request, encoding.catalog());
            var prepared = anchor.prepare(encoding.input());
            var validator = new CatalogActionValidator();
            Set<List<Integer>> seen = new HashSet<>();
            List<ActionCandidate> pool = new ArrayList<>();
            for (ActionCandidate candidate : raw) {
                var errors = validator.validate(root, candidate, encoding.catalog());
                if (!errors.isEmpty()) throw new IllegalStateException("Invalid generated action: " + errors);
                var action = encoding.encode(candidate.orders());
                if (!seen.add(action)) throw new IllegalStateException("Repeated generated action");
                pool.add(new ActionCandidate(nation, candidate.orders(), prepared.logProbability(action), candidate.source()));
            }
            if (pool.isEmpty() || pool.size() > options.candidates()) throw new IllegalStateException("Invalid pool size");
            pools.put(nation, List.copyOf(pool));
        }
        Optional<JointSearchResult> search = Optional.empty();
        Optional<JointActionEvaluator.Counts> work = Optional.empty();
        if (options.search()) {
            var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                    new UniformActionGenerator(), ProposalInspector::game, options.rollouts(), options.rolloutCandidates(), options.rolloutSeed());
            Game game = game(root);
            var trials = JointActionEvaluator.uncached(game, evaluator);
            search = Optional.of(PaperSearchProfile.forState(root).solver(evaluator, generator, options.candidates(),
                    options.iterations(), options.searchSeed(), PolicyOutput.AVERAGE).solve(game, pools, trials));
            work = Optional.of(trials.counts());
            if (!game.snapshot().equals(root) || game.getSnapshot() != null || trials.counts().failures() != 0)
                throw new IllegalStateException("Search failed or changed its root");
        }
        MovementEncoding encoding = Objects.requireNonNull(focus);
        var input = encoding.input();
        var linearPolicy = linear.prepare(input);
        var gruPolicy = gru.prepare(input);
        var pool = pools.get(power);
        var anchored = ActionDistribution.fromPriors(pool);
        var feasibility = new MovementFeasibility(root, encoding.catalog());
        List<Proposal> proposals = new ArrayList<>();
        for (int index = 0; index < pool.size(); index++) {
            ActionCandidate candidate = pool.get(index);
            var action = encoding.encode(candidate.orders());
            Optional<SearchScore> score = Optional.empty();
            if (search.isPresent()) {
                SearchResult result = search.orElseThrow().results().get(power);
                if (!result.distribution().candidates().equals(pool)) throw new IllegalStateException("Search changed candidate indexing");
                score = Optional.of(new SearchScore(result.distribution().probabilities().get(index),
                        result.meanValues().get(index), result.samplesPerCandidate()));
            }
            proposals.add(new Proposal(index, candidate, candidate.orders().stream().map(encoding::token).toList(),
                    linearPolicy.logProbability(action), gruPolicy.logProbability(action), anchored.probabilities().get(index),
                    score, MovementCoordination.inspect(root, candidate), feasibility.inspect(candidate)));
        }
        return new Inspection(root, power, method, options, models, pools, proposals, search, work, generation);
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static MovementPolicy cached(MovementPolicy policy) {
        record Key(GameMoment moment, Nation power, List<MovementInput.Unit> units,
                   Map<domain.Province, Nation> centers, List<List<String>> masks) { }
        Map<Key, MovementPolicy.Prepared> prepared = new HashMap<>();
        return input -> prepared.computeIfAbsent(new Key(input.moment(), input.power(), input.units(), input.centers(),
                        input.slots().stream().map(slot -> slot.options().stream().map(MovementInput.Option::label).toList()).toList()),
                key -> Objects.requireNonNull(policy.prepare(input), "prepared policy"));
    }

    private static String hash(Path path) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}