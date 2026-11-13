package _app;

import agents.encoding.MovementEncoding;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.policy.LinearMovementPolicy;
import agents.policy.GruMovementPolicy;
import agents.policy.MovementPolicy;
import agents.search.*;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Compares proposal methods with a shared anchor and fixed search budgets.
 * Fresh checks use the same full-policy opponent draws for every method.
 * Opening and contact boards are fixtures; no dataset split is opened.
 */
public final class PolicyProposalExperimentApp {

    public enum Method { UNIFORM, PLAUSIBLE, FULL_UNIFORM, LEARNED, HYBRID, GRU, GRU_HYBRID }
    public enum Position { OPENING, CONTACT }
    private static final String USAGE = "Usage: PolicyProposalExperimentApp "
            + "[--proposal-model <policy.bin>] [--anchor-model <policy.bin>] "
            + "[--gru-model <model.bin>] "
            + "[--position <opening|contact>] [--power <all|nation>] "
            + "[--candidates <1..256>] [--iterations <positive-int>] "
            + "[--rollouts <positive-int>] [--rollout-candidates <positive-int>] "
            + "[--opponent-samples <at-least-two>] [--pool-seed <long>] "
            + "[--search-seed <long>] [--rollout-seed <long>] [--opponent-seed <long>]";

    private record Bound(MovementEncoding encoding, MovementPolicy.Prepared anchor,
                         MovementPolicy.Prepared proposal, MovementPolicy.Prepared gru) { }
    private record Estimate(double mean, double standardError) { }
    private record Generation(Long samples, Long duplicates, double seconds) { }

    public record Observation(Method method, Nation power, int candidates, Long policySamples, Long policyDuplicates,
                              int ownSupports, int badOwnSupports, int ownConvoys, int badOwnConvoys,
                              int mismatchedActions, double mismatchMass, double logAnchorMass,
                              double freshAnchor, double freshSearch, double freshSearchError, double freshBest,
                              double generationSeconds) { }
    public record Work(Method method, double generationSeconds, double searchSeconds, double freshSeconds,
                       JointActionEvaluator.Counts search, JointActionEvaluator.Counts fresh) { }
    public record Result(GameState_Movement root, Map<String, String> hashes, List<Observation> observations, List<Work> work) {
        public Result {
            hashes = Map.copyOf(hashes);
            observations = List.copyOf(observations);
            work = List.copyOf(work);
        }
    }

    private PolicyProposalExperimentApp() { }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(USAGE);
            System.out.println("Defaults: opening, all powers, 8 candidates, 16 iterations, "
                    + "1 rollout, 8 rollout candidates, 16 opponent samples; seeds 7/7/7/17.");
            System.out.println("Anchor defaults to the proposal model. Rules: JUDGE; scoring: SUM_OF_SQUARES.");
            return;
        }
        run(args, System.out);
    }

    public static Result run(String[] args, PrintStream out) throws Exception {
        return run(args, out, null);
    }

    public static Result runPosition(GameState_Movement root, String[] args, PrintStream out) throws Exception {
        return run(args, out, Objects.requireNonNull(root, "root"));
    }

    private static Result run(String[] args, PrintStream out, GameState_Movement supplied) throws Exception {
        Objects.requireNonNull(out, "out");
        if (supplied != null && Arrays.asList(args).contains("--position"))
            throw new IllegalArgumentException("--position cannot override a supplied state");
        Path proposalPath = Path.of("data", "movement-linear-v1", "policy.bin"), anchorPath = null;
        Path gruPath = null;
        Position position = Position.OPENING;
        Set<Nation> reported = EnumSet.allOf(Nation.class);
        int limit = 8, iterations = 16, rollouts = 1, rolloutCandidates = 8, samples = 16;
        long poolSeed = 7, searchSeed = 7, rolloutSeed = 7, opponentSeed = 17;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (!seen.add(args[index]) || index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);
            switch (args[index]) {
                case "--proposal-model" -> proposalPath = Path.of(args[++index]);
                case "--anchor-model" -> anchorPath = Path.of(args[++index]);
                case "--gru-model" -> gruPath = Path.of(args[++index]);
                case "--position" -> position = Position.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--power" -> {
                    String value = args[++index];
                    reported = value.equalsIgnoreCase("all") ? EnumSet.allOf(Nation.class)
                            : EnumSet.of(Nation.valueOf(value.toUpperCase(Locale.ROOT)));
                }
                case "--candidates" -> limit = Integer.parseInt(args[++index]);
                case "--iterations" -> iterations = Integer.parseInt(args[++index]);
                case "--rollouts" -> rollouts = Integer.parseInt(args[++index]);
                case "--rollout-candidates" -> rolloutCandidates = Integer.parseInt(args[++index]);
                case "--opponent-samples" -> samples = Integer.parseInt(args[++index]);
                case "--pool-seed" -> poolSeed = Long.parseLong(args[++index]);
                case "--search-seed" -> searchSeed = Long.parseLong(args[++index]);
                case "--rollout-seed" -> rolloutSeed = Long.parseLong(args[++index]);
                case "--opponent-seed" -> opponentSeed = Long.parseLong(args[++index]);
                default -> throw new IllegalArgumentException(USAGE);
            }
        }
        if (limit < 1 || limit > PolicyActionGenerator.MAX_CANDIDATES || iterations < 1
                || rollouts < 1 || rolloutCandidates < 1 || samples < 2)
            throw new IllegalArgumentException(USAGE);
        proposalPath = proposalPath.toAbsolutePath().normalize();
        anchorPath = anchorPath == null ? proposalPath : anchorPath.toAbsolutePath().normalize();
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("proposal_sha256", hash(proposalPath));
        hashes.put("anchor_sha256", hash(anchorPath));
        if (gruPath != null) hashes.put("gru_sha256", hash(gruPath));
        var proposal = LinearMovementPolicy.load(proposalPath);
        var anchor = anchorPath.equals(proposalPath) ? proposal : LinearMovementPolicy.load(anchorPath);
        var gru = gruPath == null ? null : GruMovementPolicy.load(gruPath);
        if (!hashes.get("proposal_sha256").equals(hash(proposalPath))
                || !hashes.get("anchor_sha256").equals(hash(anchorPath))
                || gru != null && !hashes.get("gru_sha256").equals(gru.sha256()))
            throw new IOException("Model changed while loading");
        if (gru != null) {
            hashes.put("checkpoint_sha256", gru.checkpointSha256());
            hashes.put("dataset_index_sha256", gru.datasetIndexSha256());
        }
        List<Observation> observations = new ArrayList<>();
        List<Work> work = new ArrayList<>();
        GameState_Movement root = supplied == null ? position(position) : supplied;
        if (root.aliveNations().isEmpty()) throw new IllegalArgumentException("The position has no living powers");
        Map<Nation, Bound> bound = new EnumMap<>(Nation.class);
        for (Nation power : root.aliveNations()) {
            MovementEncoding encoding = new MovementEncoding(root, power);
            var input = encoding.input();
            var prepared = anchor.prepare(input);
            bound.put(power, new Bound(encoding, prepared, proposal == anchor ? prepared : proposal.prepare(input),
                    gru == null ? null : gru.prepare(input)));
        }
        List<Map<Nation, ActionCandidate>> opponents = opponents(bound, samples, opponentSeed);
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), PolicyProposalExperimentApp::game, rollouts, rolloutCandidates, rolloutSeed);
        PaperSearchProfile profile = PaperSearchProfile.forState(root);

        out.println("BEGIN POLICY PROPOSAL EXPERIMENT " + (gru == null ? "v2" : "v3"));
        out.println("Position=" + (supplied == null ? position : "SUPPLIED") + "; moment=" + root.moment());
        if (supplied == null) out.println("CONTACT is a constructed Fall 1902 board, not a replayed source position.");
        out.println("Proposal SHA-256=" + hashes.get("proposal_sha256"));
        out.println("Anchor SHA-256=" + hashes.get("anchor_sha256"));
        if (gru != null) {
            out.println("GRU SHA-256=" + gru.sha256() + "; checkpoint SHA-256=" + gru.checkpointSha256());
            out.println("GRU/GRU_HYBRID use neural proposals with the same frozen linear anchor and opponent draws.");
            out.println("Existing log_learned_proposal columns still use the linear proposal model.");
        }
        out.printf(Locale.ROOT, "Budgets: candidates=%d iterations=%d rollouts=%d rollout_candidates=%d opponent_samples=%d%n",
                limit, iterations, rollouts, rolloutCandidates, samples);
        out.printf(Locale.ROOT, "Seeds: pool=%d search=%d rollout=%d opponents=%d%n",
                poolSeed, searchSeed, rolloutSeed, opponentSeed);
        out.println("Rules=JUDGE:1:0; scoring=SUM_OF_SQUARES; profile=" + profile + "; output=AVERAGE");
        out.println("All methods use the same full-catalog anchor; this isolates proposal changes.");
        out.println("UNIFORM/PLAUSIBLE retain convoy-free construction and their forced all-hold candidate.");
        out.println("FULL_UNIFORM/LEARNED sample full catalogs without inserting all-hold or greedy actions.");
        out.println("HYBRID constructs up to two full pools, reserves half for each, then fills unused places.");
        out.println("An odd extra place goes to learned proposals. Duplicates keep the first retained source.");
        out.println("Coordination counts own support/convoy mismatches only; no orders are repaired or filtered.");
        out.println("Continuation policy is unchanged convoy-free uniform, ending at this year's winter.");
        out.println("Q uses each solve's own opponents. Fresh values use common independent anchor draws.");
        out.println("Anchor pool mass measures coverage, not playing strength. No model is fitted here.");
        out.println("log_learned_proposal scores every method under the loaded model, not its construction process.");
        out.println("Budgets match by candidates/iterations/samples, not wall time; actual work is reported.");
        out.println("Generation timing excludes model loading and shared reference-policy preparation.");
        out.println("--power filters tables only. " + (supplied == null ? "All seven powers" : "All living powers")
                + " are searched and evaluated.");
        out.println("Board=" + bound.values().iterator().next().encoding().input().units());
        out.println("Centers=" + root.board().owners());

        Map<Nation, double[]> reference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> heuristicReference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> learnedReference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> hybridReference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> gruReference = new EnumMap<>(Nation.class);
        for (Method method : Method.values()) {
            if (gru == null && (method == Method.GRU || method == Method.GRU_HYBRID)) continue;
            ActionGenerator generator = switch (method) {
                case UNIFORM -> new UniformActionGenerator();
                case PLAUSIBLE -> new PlausibleActionGenerator();
                case FULL_UNIFORM -> new PolicyActionGenerator(new LinearMovementPolicy(1024), anchor);
                case LEARNED -> new PolicyActionGenerator(proposal, anchor);
                case HYBRID -> new HybridActionGenerator(proposal, anchor);
                case GRU -> new PolicyActionGenerator(gru, anchor);
                case GRU_HYBRID -> new HybridActionGenerator(gru, anchor);
            };
            Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
            Map<Nation, HybridActionGenerator.Result> hybrid = new EnumMap<>(Nation.class);
            Map<Nation, Generation> generation = new EnumMap<>(Nation.class);
            Random random = new Random(poolSeed);
            long start = System.nanoTime(), draws = 0, duplicates = 0;
            for (var entry : bound.entrySet()) {
                Nation power = entry.getKey();
                Bound policy = entry.getValue();
                var request = new ActionGenerationRequest(root, power, limit, random.nextLong());
                long generationStart = System.nanoTime();
                Long policySamples = null, policyDuplicates = null;
                List<ActionCandidate> raw;
                if (generator instanceof PolicyActionGenerator learned) {
                    var generated = learned.propose(request, policy.encoding());
                    raw = generated.candidates();
                    draws += generated.samples();
                    duplicates += generated.duplicates();
                    policySamples = generated.samples();
                    policyDuplicates = generated.duplicates();
                } else if (generator instanceof HybridActionGenerator combined) {
                    var generated = combined.propose(request, policy.encoding());
                    hybrid.put(power, generated);
                    raw = generated.candidates();
                    policySamples = generated.learnedSamples();
                    policyDuplicates = generated.learnedDuplicates();
                } else raw = generator.generate(request, policy.encoding().catalog());
                List<ActionCandidate> anchored = new ArrayList<>();
                for (ActionCandidate candidate : raw) {
                    double logPrior = policy.anchor().logProbability(policy.encoding().encode(candidate.orders()));
                    anchored.add(new ActionCandidate(power, candidate.orders(), logPrior, candidate.source()));
                }
                pools.put(power, List.copyOf(anchored));
                generation.put(power, new Generation(policySamples, policyDuplicates, seconds(generationStart)));
            }
            double generationSeconds = seconds(start);
            out.printf(Locale.ROOT, "%nMETHOD %s generation_seconds=%.6f%n", method, generationSeconds);
            if (generator instanceof PolicyActionGenerator)
                out.printf(Locale.ROOT, "Proposal samples=%d duplicates=%d%n", draws, duplicates);
            Game simulation = game(root);
            JointActionEvaluator searchTrials = JointActionEvaluator.uncached(simulation, evaluator);
            start = System.nanoTime();
            JointSearchResult solved = profile.solver(evaluator, generator, limit, iterations, searchSeed,
                    PolicyOutput.AVERAGE).solve(simulation, pools, searchTrials);
            double searchSeconds = seconds(start);
            out.printf(Locale.ROOT, "Search seconds=%.6f; root_work=%s%n", searchSeconds, searchTrials.counts());
            JointActionEvaluator checks = JointActionEvaluator.uncached(game(root), evaluator);
            start = System.nanoTime();
            for (var entry : pools.entrySet()) {
                Nation power = entry.getKey();
                List<ActionCandidate> candidates = entry.getValue();
                var result = solved.results().get(power);
                var prior = ActionDistribution.fromPriors(candidates);
                double[][] values = values(candidates, opponents, checks);
                double[] searchValues = mixture(values, result.distribution().probabilities());
                double[] anchorValues = mixture(values, prior.probabilities());
                Estimate searchEstimate = estimate(searchValues), anchorEstimate = estimate(anchorValues);
                if (method == Method.UNIFORM) reference.put(power, searchValues);
                if (method == Method.PLAUSIBLE) heuristicReference.put(power, searchValues);
                if (method == Method.LEARNED) learnedReference.put(power, searchValues);
                if (method == Method.HYBRID) hybridReference.put(power, searchValues);
                if (method == Method.GRU) gruReference.put(power, searchValues);
                double best = Arrays.stream(values).mapToDouble(row -> estimate(row).mean()).max().orElseThrow();
                List<MovementCoordination.Counts> coordination = candidates.stream()
                        .map(candidate -> MovementCoordination.inspect(root, candidate)).toList();
                int badSupports = 0, badConvoys = 0, badActions = 0;
                double badMass = 0;
                for (int index = 0; index < candidates.size(); index++) {
                    var counts = coordination.get(index);
                    if (counts.mismatchedSupports() > 0) badSupports++;
                    if (counts.mismatchedConvoys() > 0) badConvoys++;
                    if (counts.mismatched()) {
                        badActions++;
                        badMass += result.distribution().probabilities().get(index);
                    }
                }
                var generated = generation.get(power);
                observations.add(new Observation(method, power, candidates.size(), generated.samples(), generated.duplicates(),
                        coordination.stream().mapToInt(MovementCoordination.Counts::ownSupports).sum(),
                        coordination.stream().mapToInt(MovementCoordination.Counts::mismatchedSupports).sum(),
                        coordination.stream().mapToInt(MovementCoordination.Counts::ownConvoys).sum(),
                        coordination.stream().mapToInt(MovementCoordination.Counts::mismatchedConvoys).sum(),
                        badActions, badMass, logMass(candidates), anchorEstimate.mean(), searchEstimate.mean(),
                        searchEstimate.standardError(), best, generated.seconds()));
                if (!reported.contains(power)) continue;
                out.printf(Locale.ROOT, "POWER %s pool=%d/%d log_anchor_mass=%.10f%n",
                        power, candidates.size(), limit, logMass(candidates));
                if (candidates.size() < limit) out.println("SHORT_POOL: actual evaluation work is below the requested budget.");
                if (hybrid.containsKey(power)) {
                    var counts = hybrid.get(power);
                    out.printf(Locale.ROOT, "Hybrid quotas=%d/%d available=%d/%d retained=%d/%d overlap=%d (learned/heuristic)%n",
                            counts.learnedQuota(), counts.heuristicQuota(), counts.learnedAvailable(), counts.heuristicAvailable(),
                            counts.learnedSelected(), counts.heuristicSelected(), counts.overlap());
                }
                out.printf(Locale.ROOT, "Fresh anchor_pool=%.8f se=%.8f search=%.8f se=%.8f best_in_pool=%.8f%n",
                        anchorEstimate.mean(), anchorEstimate.standardError(), searchEstimate.mean(), searchEstimate.standardError(), best);
                paired(out, "UNIFORM", searchValues, reference.get(power));
                if (heuristicReference.containsKey(power))
                    paired(out, "PLAUSIBLE", searchValues, heuristicReference.get(power));
                if (method == Method.HYBRID || method == Method.GRU || method == Method.GRU_HYBRID)
                    paired(out, "LEARNED", searchValues, learnedReference.get(power));
                if (method == Method.GRU_HYBRID) {
                    paired(out, "HYBRID", searchValues, hybridReference.get(power));
                    paired(out, "GRU", searchValues, gruReference.get(power));
                }
                out.printf(Locale.ROOT, "Coordination support_mismatch_actions=%d convoy_mismatch_actions=%d either=%d/%d search_mass=%.8f%n",
                        badSupports, badConvoys, badActions, candidates.size(), badMass);
                out.println("candidate source log_learned_proposal log_anchor anchor_pool search Q fresh_value bad_own_supports bad_own_convoys"
                        + (gru == null ? "" : " log_gru_proposal"));
                Bound policy = bound.get(power);
                for (int index = 0; index < candidates.size(); index++) {
                    ActionCandidate candidate = candidates.get(index);
                    out.printf(Locale.ROOT, "%s-P%d %s %.10f %.10f %.8f %.8f %.8f %.8f %d %d%s%n",
                            power, index, candidate.source(),
                            policy.proposal().logProbability(policy.encoding().encode(candidate.orders())),
                            candidate.logPrior(), prior.probabilities().get(index), result.distribution().probabilities().get(index),
                            result.meanValues().get(index), estimate(values[index]).mean(),
                            coordination.get(index).mismatchedSupports(), coordination.get(index).mismatchedConvoys(),
                            policy.gru() == null ? "" : String.format(Locale.ROOT, " %.10f",
                                    policy.gru().logProbability(policy.encoding().encode(candidate.orders()))));
                    out.println("  " + String.join("; ", candidate.orders().stream().map(policy.encoding()::token).toList()));
                }
            }
            double freshSeconds = seconds(start);
            out.printf(Locale.ROOT, "Fresh seconds=%.6f; root_work=%s%n", freshSeconds, checks.counts());
            work.add(new Work(method, generationSeconds, searchSeconds, freshSeconds, searchTrials.counts(), checks.counts()));
            if (!simulation.snapshot().equals(root) || simulation.getSnapshot() != null)
                throw new IllegalStateException("Experiment changed its search root");
        }
        out.println("Errors describe opponent sampling only, conditional on fixed pools/search/rollout seeds.");
        out.println("best_in_pool is selected on these samples and is optimistic; it is not a tested policy.");
        out.println("These year-end estimates are diagnostics, not win rates or evidence of full-game strength.");
        out.println("END POLICY PROPOSAL EXPERIMENT");
        if (out.checkError()) throw new IOException("Could not write experiment output");
        return new Result(root, hashes, observations, work);
    }

    private static List<Map<Nation, ActionCandidate>> opponents(Map<Nation, Bound> policies, int count, long seed) {
        Random random = new Random(seed);
        List<Map<Nation, ActionCandidate>> samples = new ArrayList<>();
        for (int sample = 0; sample < count; sample++) {
            Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
            for (var entry : policies.entrySet()) {
                Bound bound = entry.getValue();
                var action = bound.anchor().sample(random);
                joint.put(entry.getKey(), new ActionCandidate(entry.getKey(), bound.encoding().decode(action),
                        bound.anchor().logProbability(action), ActionCandidate.Source.POLICY_SAMPLE));
            }
            samples.add(Collections.unmodifiableMap(joint));
        }
        return List.copyOf(samples);
    }

    private static double[][] values(List<ActionCandidate> candidates, List<Map<Nation, ActionCandidate>> opponents,
                                     JointActionEvaluator checks) {
        double[][] values = new double[candidates.size()][opponents.size()];
        for (int index = 0; index < candidates.size(); index++)
            for (int sample = 0; sample < opponents.size(); sample++) {
                Map<Nation, ActionCandidate> joint = new EnumMap<>(opponents.get(sample));
                ActionCandidate candidate = candidates.get(index);
                joint.put(candidate.power(), candidate);
                values[index][sample] = checks.evaluate(joint).get(candidate.power());
            }
        return values;
    }

    private static double[] mixture(double[][] values, List<Double> probabilities) {
        double[] result = new double[values[0].length];
        for (int action = 0; action < values.length; action++)
            for (int sample = 0; sample < result.length; sample++)
                result[sample] += probabilities.get(action) * values[action][sample];
        return result;
    }

    private static Estimate estimate(double[] values) {
        double mean = Arrays.stream(values).average().orElseThrow(), squared = 0;
        for (double value : values) squared += (value - mean) * (value - mean);
        return new Estimate(mean, Math.sqrt(squared / (values.length * (values.length - 1.0))));
    }

    private static void paired(PrintStream out, String reference, double[] values, double[] baseline) {
        double[] differences = new double[values.length];
        for (int sample = 0; sample < values.length; sample++)
            differences[sample] = values[sample] - baseline[sample];
        Estimate delta = estimate(differences);
        out.printf(Locale.ROOT, "Paired search_delta_vs_%s=%.8f se=%.8f%n",
                reference, delta.mean(), delta.standardError());
    }

    private static double logMass(List<ActionCandidate> candidates) {
        double maximum = candidates.stream().mapToDouble(ActionCandidate::logPrior).max().orElseThrow();
        return maximum + Math.log(candidates.stream().mapToDouble(c -> Math.exp(c.logPrior() - maximum)).sum());
    }

    private static double seconds(long start) { return (System.nanoTime() - start) / 1e9; }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static GameState_Movement position(Position position) {
        GameState_Movement opening = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        if (position == Position.OPENING) return opening;
        Map<Province, Province> moved = Map.ofEntries(
                Map.entry(Province.Lon, Province.NTH), Map.entry(Province.Edi, Province.NWG), Map.entry(Province.Lvp, Province.Yor),
                Map.entry(Province.Par, Province.Bur), Map.entry(Province.Mar, Province.Spa), Map.entry(Province.Bre, Province.MAO),
                Map.entry(Province.Mun, Province.Ruh), Map.entry(Province.Ber, Province.Mun), Map.entry(Province.Kie, Province.Den),
                Map.entry(Province.Ven, Province.Tyr), Map.entry(Province.Rom, Province.Ven), Map.entry(Province.Nap, Province.ION),
                Map.entry(Province.Vie, Province.Gal), Map.entry(Province.Bud, Province.Ser), Map.entry(Province.Tri, Province.Alb),
                Map.entry(Province.Mos, Province.Ukr), Map.entry(Province.War, Province.Sil),
                Map.entry(Province.Sev, Province.Rum), Map.entry(Province.StpSC, Province.BOT),
                Map.entry(Province.Con, Province.Bul), Map.entry(Province.Ank, Province.BLA), Map.entry(Province.Smy, Province.Con));
        Map<UnitId, Province> units = new LinkedHashMap<>();
        opening.board().locations().forEach((unit, location) -> units.put(unit, moved.getOrDefault(location, location)));
        Map<Province, Nation> centers = new EnumMap<>(opening.board().owners());
        centers.putAll(Map.of(Province.Spa, Nation.FRANCE, Province.Den, Nation.GERMANY, Province.Ser, Nation.AUSTRIA,
                Province.Rum, Nation.RUSSIA, Province.Bul, Nation.TURKEY));
        return new GameState_Movement(new GameMoment(1902, GamePhase.FALL_MOVEMENT), new BoardState(units, centers));
    }
}