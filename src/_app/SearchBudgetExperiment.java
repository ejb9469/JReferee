package _app;

import agents.encoding.MovementEncoding;
import agents.evaluation.*;
import agents.execution.ProposalInspector;
import agents.execution.ProposalInspector.*;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/**
 * Search checkpoints over one set of generated pools.
 * Independent anchor opponents supply one fresh payoff table per pool.
 */
public final class SearchBudgetExperiment {

    public record Options(int candidates, int rollouts, int rolloutCandidates, int opponentSamples,
                          List<Integer> checkpoints, List<Method> methods) {
        public Options {
            checkpoints = List.copyOf(checkpoints);
            methods = List.copyOf(methods);
            new ProposalInspector.Options(candidates, 1, rollouts, rolloutCandidates, 0, 0, 0, false);
            if (opponentSamples < 2 || checkpoints.size() < 2 || methods.isEmpty()
                    || new HashSet<>(methods).size() != methods.size())
                throw new IllegalArgumentException("Supply at least two opponent samples/checkpoints and distinct methods");
            int previous = 0;
            for (int checkpoint : checkpoints) {
                if (checkpoint <= previous) throw new IllegalArgumentException("Checkpoints must increase strictly from a positive value");
                previous = checkpoint;
            }
        }
        public static Options defaults() {
            return new Options(8, 1, 8, 16, List.of(16, 64, 256),
                    List.of(Method.PLAUSIBLE, Method.COHERENT_GRU, Method.COHERENT_GRU_HYBRID));
        }
    }

    public record Pool(Inspection inspection, String sha256) {
        public List<ActionCandidate> candidates() { return inspection.pools().get(inspection.power()); }
    }
    public record Work(double generationSeconds, double searchSeconds, double freshSeconds,
                       long requestsPerIteration, JointActionEvaluator.Counts search, JointActionEvaluator.Counts fresh) { }
    public record MethodRun(Method method, Map<Nation, Pool> pools, List<JointSearchResult> history,
                            Map<Nation, List<List<Double>>> freshValues, Work work) {
        public MethodRun {
            pools = Collections.unmodifiableMap(new EnumMap<>(pools));
            history = List.copyOf(history);
            Map<Nation, List<List<Double>>> copy = new EnumMap<>(Nation.class);
            freshValues.forEach((power, rows) -> copy.put(power, rows.stream().map(List::copyOf).toList()));
            freshValues = Collections.unmodifiableMap(copy);
        }
    }
    public record Result(GameState_Movement root, Options options, PolicyProposalBenchmarkApp.Seeds seeds,
                         List<Map<Nation, ActionCandidate>> opponents, List<MethodRun> methods) {
        public Result {
            opponents = opponents.stream().map(joint -> Collections.unmodifiableMap(new EnumMap<>(joint))).toList();
            methods = List.copyOf(methods);
        }
    }

    private final ProposalInspector inspector;
    private final MovementPolicy anchor;

    public SearchBudgetExperiment(MovementPolicy proposal, MovementPolicy anchor, MovementPolicy gru) {
        this(new ProposalInspector(proposal, anchor, gru), anchor);
    }

    private SearchBudgetExperiment(ProposalInspector inspector, MovementPolicy anchor) {
        this.inspector = Objects.requireNonNull(inspector);
        this.anchor = Objects.requireNonNull(anchor);
    }

    public static SearchBudgetExperiment load(Path proposal, Path anchor, Path gru) throws IOException {
        var inspector = ProposalInspector.load(proposal, anchor, gru);
        var policy = LinearMovementPolicy.load(anchor);
        try {
            if (!inspector.models().get("anchor").equals(PolicyProposalBenchmarkApp.hash(anchor)))
                throw new IOException("Anchor changed while loading");
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        return new SearchBudgetExperiment(inspector, policy);
    }

    public Map<String, String> models() { return inspector.models(); }

    public Result run(GameState_Movement root, Options options, PolicyProposalBenchmarkApp.Seeds seeds) {
        Objects.requireNonNull(root); Objects.requireNonNull(options); Objects.requireNonNull(seeds);
        if (root.aliveNations().isEmpty()) throw new IllegalArgumentException("The position has no living powers");
        List<Map<Nation, ActionCandidate>> opponents = opponents(root, options.opponentSamples(), seeds.opponents());
        List<MethodRun> methods = new ArrayList<>();
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), SearchBudgetExperiment::game, options.rollouts(), options.rolloutCandidates(), seeds.rollout());
        var request = new ProposalInspector.Options(options.candidates(), 1, options.rollouts(),
                options.rolloutCandidates(), seeds.pool(), seeds.search(), seeds.rollout(), false);
        for (Method method : options.methods()) {
            long start = System.nanoTime();
            Map<Nation, Pool> pools = new EnumMap<>(Nation.class);
            Map<Nation, List<ActionCandidate>> candidates = new EnumMap<>(Nation.class);
            for (Nation power : Nation.values()) {
                if (!root.aliveNations().contains(power)) continue;
                Inspection inspection;
                try { inspection = inspector.inspect(root, power, method, request); }
                catch (ProposalInspector.CoherentExhaustion exception) {
                    throw new IllegalStateException(method + " exhausted for " + exception.power()
                            + "; construction=" + exception.generation(), exception);
                }
                var pool = new Pool(inspection, fingerprint(inspection));
                pools.put(power, pool);
                candidates.put(power, pool.candidates());
            }
            double generationSeconds = seconds(start);
            candidates = Collections.unmodifiableMap(candidates);
            long requestsPerIteration = 1 + candidates.values().stream().mapToLong(pool -> pool.size() - 1).sum();
            Game simulation = game(root);
            var searchTrials = JointActionEvaluator.uncached(simulation, evaluator);
            ActionGenerator fixed = (r, c) -> { throw new IllegalStateException("Fixed-pool search requested generation"); };
            int maximum = options.checkpoints().getLast();
            start = System.nanoTime();
            List<JointSearchResult> history = PaperSearchProfile.forState(root).solver(evaluator, fixed,
                            options.candidates(), maximum, seeds.search(), PolicyOutput.AVERAGE)
                    .trace(simulation, candidates, options.checkpoints(), searchTrials);
            double searchSeconds = seconds(start);
            requireWork(searchTrials.counts(), requestsPerIteration * maximum);
            requireRoot(simulation, root);
            for (int index = 0; index < history.size(); index++) {
                var checkpoint = history.get(index);
                if (!checkpoint.results().keySet().equals(candidates.keySet()))
                    throw new IllegalStateException("Search changed the living powers");
                for (var entry : checkpoint.results().entrySet())
                    if (!entry.getValue().distribution().candidates().equals(candidates.get(entry.getKey()))
                            || entry.getValue().samplesPerCandidate() != options.checkpoints().get(index))
                        throw new IllegalStateException("Search changed its fixed pool or iteration count");
            }
            Game freshGame = game(root);
            var freshTrials = JointActionEvaluator.uncached(freshGame, evaluator);
            Map<Nation, List<List<Double>>> values = new EnumMap<>(Nation.class);
            start = System.nanoTime();
            for (var entry : candidates.entrySet()) {
                List<List<Double>> rows = new ArrayList<>();
                for (ActionCandidate candidate : entry.getValue()) {
                    List<Double> row = new ArrayList<>();
                    for (var sample : opponents) {
                        Map<Nation, ActionCandidate> joint = new EnumMap<>(sample);
                        joint.put(entry.getKey(), candidate);
                        row.add(freshTrials.evaluate(joint).get(entry.getKey()));
                    }
                    rows.add(List.copyOf(row));
                }
                values.put(entry.getKey(), List.copyOf(rows));
            }
            double freshSeconds = seconds(start);
            requireWork(freshTrials.counts(), candidates.values().stream().mapToLong(List::size).sum() * options.opponentSamples());
            requireRoot(freshGame, root);
            methods.add(new MethodRun(method, pools, history, values,
                    new Work(generationSeconds, searchSeconds, freshSeconds, requestsPerIteration, searchTrials.counts(), freshTrials.counts())));
        }
        return new Result(root, options, seeds, opponents, methods);
    }

    public static double[] mixture(List<List<Double>> values, List<Double> probabilities) {
        if (values.isEmpty() || values.size() != probabilities.size()
                || probabilities.stream().anyMatch(p -> !Double.isFinite(p) || p < 0 || p > 1)
                || Math.abs(probabilities.stream().mapToDouble(Double::doubleValue).sum() - 1) > 1e-9)
            throw new IllegalArgumentException("Expected a complete playing distribution");
        double[] result = new double[values.getFirst().size()];
        if (result.length < 2) throw new IllegalArgumentException("At least two fresh samples are required");
        for (int action = 0; action < values.size(); action++) {
            List<Double> row = values.get(action);
            if (row.size() != result.length || row.stream().anyMatch(value -> !Double.isFinite(value)))
                throw new IllegalArgumentException("Incomplete or nonfinite payoff table");
            for (int sample = 0; sample < result.length; sample++)
                result[sample] += probabilities.get(action) * row.get(sample);
        }
        return result;
    }

    public static String fingerprint(Inspection inspection) {
        StringBuilder text = new StringBuilder();
        for (Proposal proposal : inspection.proposals())
            text.append(proposal.action().source()).append('\t').append(Double.toHexString(proposal.action().logPrior()))
                    .append('\t').append(String.join("; ", proposal.orders())).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private List<Map<Nation, ActionCandidate>> opponents(GameState_Movement root, int count, long seed) {
        Map<Nation, MovementEncoding> encodings = new EnumMap<>(Nation.class);
        Map<Nation, MovementPolicy.Prepared> policies = new EnumMap<>(Nation.class);
        for (Nation power : root.aliveNations()) {
            var encoding = new MovementEncoding(root, power);
            encodings.put(power, encoding);
            policies.put(power, anchor.prepare(encoding.input()));
        }
        List<Map<Nation, ActionCandidate>> samples = new ArrayList<>();
        Random random = new Random(seed);
        for (int sample = 0; sample < count; sample++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
            for (var entry : policies.entrySet()) {
                var action = entry.getValue().sample(random);
                joint.put(entry.getKey(), new ActionCandidate(entry.getKey(), encodings.get(entry.getKey()).decode(action),
                        entry.getValue().logProbability(action), ActionCandidate.Source.POLICY_SAMPLE));
            }
            samples.add(Collections.unmodifiableMap(joint));
        }
        return List.copyOf(samples);
    }

    private static void requireWork(JointActionEvaluator.Counts counts, long expected) {
        if (counts.requests() != expected || counts.simulations() != expected || counts.evaluations() != expected
                || counts.failures() != 0 || counts.hits() != 0)
            throw new IllegalStateException("Unexpected fixed-pool evaluation accounting: " + counts);
    }
    private static void requireRoot(Game game, GameState root) {
        if (!game.snapshot().equals(root) || game.getSnapshot() != null)
            throw new IllegalStateException("Experiment changed its root");
    }
    private static double seconds(long start) { return (System.nanoTime() - start) / 1e9; }
    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }
}