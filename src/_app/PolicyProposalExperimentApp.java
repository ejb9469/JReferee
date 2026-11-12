package _app;

import agents.encoding.MovementEncoding;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.policy.LinearMovementPolicy;
import agents.policy.MovementPolicy;
import agents.search.*;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Compares proposal methods with a shared anchor and fixed search budgets.
 * Fresh checks use the same full-policy opponent draws for every method.
 * Opening and contact boards are fixtures; no dataset split is opened.
 */
public final class PolicyProposalExperimentApp {

    private enum Method { UNIFORM, PLAUSIBLE, FULL_UNIFORM, LEARNED, HYBRID }
    private enum Position { OPENING, CONTACT }
    private static final String USAGE = "Usage: PolicyProposalExperimentApp "
            + "[--proposal-model <policy.bin>] [--anchor-model <policy.bin>] "
            + "[--position <opening|contact>] [--power <all|nation>] "
            + "[--candidates <1..256>] [--iterations <positive-int>] "
            + "[--rollouts <positive-int>] [--rollout-candidates <positive-int>] "
            + "[--opponent-samples <at-least-two>] [--pool-seed <long>] "
            + "[--search-seed <long>] [--rollout-seed <long>] [--opponent-seed <long>]";

    private record Bound(MovementEncoding encoding, MovementPolicy.Prepared anchor,
                         MovementPolicy.Prepared proposal) { }
    private record Estimate(double mean, double standardError) { }

    private PolicyProposalExperimentApp() { }

    public static void main(String[] args) throws Exception {
        Path proposalPath = Path.of("data", "movement-linear-v1", "policy.bin"), anchorPath = null;
        Position position = Position.OPENING;
        Set<Nation> reported = EnumSet.allOf(Nation.class);
        int limit = 8, iterations = 16, rollouts = 1, rolloutCandidates = 8, samples = 16;
        long poolSeed = 7, searchSeed = 7, rolloutSeed = 7, opponentSeed = 17;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) {
                System.out.println(USAGE);
                System.out.println("Defaults: opening, all powers, 8 candidates, 16 iterations, "
                        + "1 rollout, 8 rollout candidates, 16 opponent samples; seeds 7/7/7/17.");
                System.out.println("Anchor defaults to the proposal model. Rules: JUDGE; scoring: SUM_OF_SQUARES.");
                return;
            }
            if (!seen.add(args[index]) || index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);
            switch (args[index]) {
                case "--proposal-model" -> proposalPath = Path.of(args[++index]);
                case "--anchor-model" -> anchorPath = Path.of(args[++index]);
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
        var proposal = LinearMovementPolicy.load(proposalPath);
        var anchor = anchorPath.equals(proposalPath) ? proposal : LinearMovementPolicy.load(anchorPath);
        GameState_Movement root = position(position);
        Map<Nation, Bound> bound = new EnumMap<>(Nation.class);
        for (Nation power : root.aliveNations()) {
            MovementEncoding encoding = new MovementEncoding(root, power);
            var input = encoding.input();
            var prepared = anchor.prepare(input);
            bound.put(power, new Bound(encoding, prepared, proposal == anchor ? prepared : proposal.prepare(input)));
        }
        List<Map<Nation, ActionCandidate>> opponents = opponents(bound, samples, opponentSeed);
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), PolicyProposalExperimentApp::game, rollouts, rolloutCandidates, rolloutSeed);
        PaperSearchProfile profile = PaperSearchProfile.forState(root);

        System.out.println("BEGIN POLICY PROPOSAL EXPERIMENT v2");
        System.out.println("Position=" + position + "; moment=" + root.moment());
        System.out.println("CONTACT is a constructed Fall 1902 board, not a replayed source position.");
        System.out.println("Proposal SHA-256=" + hash(proposalPath));
        System.out.println("Anchor SHA-256=" + hash(anchorPath));
        System.out.printf(Locale.ROOT, "Budgets: candidates=%d iterations=%d rollouts=%d rollout_candidates=%d opponent_samples=%d%n",
                limit, iterations, rollouts, rolloutCandidates, samples);
        System.out.printf(Locale.ROOT, "Seeds: pool=%d search=%d rollout=%d opponents=%d%n",
                poolSeed, searchSeed, rolloutSeed, opponentSeed);
        System.out.println("Rules=JUDGE:1:0; scoring=SUM_OF_SQUARES; profile=" + profile + "; output=AVERAGE");
        System.out.println("All methods use the same full-catalog anchor; this isolates proposal changes.");
        System.out.println("UNIFORM/PLAUSIBLE retain convoy-free construction and their forced all-hold candidate.");
        System.out.println("FULL_UNIFORM/LEARNED sample full catalogs without inserting all-hold or greedy actions.");
        System.out.println("HYBRID constructs up to two full pools, reserves half for each, then fills unused places.");
        System.out.println("An odd extra place goes to learned proposals. Duplicates keep the first retained source.");
        System.out.println("Coordination counts own support/convoy mismatches only; no orders are repaired or filtered.");
        System.out.println("Continuation policy is unchanged convoy-free uniform, ending at this year's winter.");
        System.out.println("Q uses each solve's own opponents. Fresh values use common independent anchor draws.");
        System.out.println("Anchor pool mass measures coverage, not playing strength. No model is fitted here.");
        System.out.println("log_learned_proposal scores every method under the loaded model, not its construction process.");
        System.out.println("Budgets match by candidates/iterations/samples, not wall time; actual work is reported.");
        System.out.println("Generation timing excludes model loading and shared reference-policy preparation.");
        System.out.println("--power filters tables only. All seven powers are searched and evaluated.");
        System.out.println("Board=" + bound.values().iterator().next().encoding().input().units());
        System.out.println("Centers=" + root.board().owners());

        Map<Nation, double[]> reference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> heuristicReference = new EnumMap<>(Nation.class);
        Map<Nation, double[]> learnedReference = new EnumMap<>(Nation.class);
        for (Method method : Method.values()) {
            ActionGenerator generator = switch (method) {
                case UNIFORM -> new UniformActionGenerator();
                case PLAUSIBLE -> new PlausibleActionGenerator();
                case FULL_UNIFORM -> new PolicyActionGenerator(new LinearMovementPolicy(1024), anchor);
                case LEARNED -> new PolicyActionGenerator(proposal, anchor);
                case HYBRID -> new HybridActionGenerator(proposal, anchor);
            };
            Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
            Map<Nation, HybridActionGenerator.Result> hybrid = new EnumMap<>(Nation.class);
            Random random = new Random(poolSeed);
            long start = System.nanoTime(), draws = 0, duplicates = 0;
            for (var entry : bound.entrySet()) {
                Nation power = entry.getKey();
                Bound policy = entry.getValue();
                var request = new ActionGenerationRequest(root, power, limit, random.nextLong());
                List<ActionCandidate> raw;
                if (generator instanceof PolicyActionGenerator learned) {
                    var generated = learned.propose(request, policy.encoding());
                    raw = generated.candidates();
                    draws += generated.samples();
                    duplicates += generated.duplicates();
                } else if (generator instanceof HybridActionGenerator combined) {
                    var generated = combined.propose(request, policy.encoding());
                    hybrid.put(power, generated);
                    raw = generated.candidates();
                } else raw = generator.generate(request, policy.encoding().catalog());
                List<ActionCandidate> anchored = new ArrayList<>();
                for (ActionCandidate candidate : raw) {
                    double logPrior = policy.anchor().logProbability(policy.encoding().encode(candidate.orders()));
                    anchored.add(new ActionCandidate(power, candidate.orders(), logPrior, candidate.source()));
                }
                pools.put(power, List.copyOf(anchored));
            }
            System.out.printf(Locale.ROOT, "%nMETHOD %s generation_seconds=%.6f%n", method, seconds(start));
            if (generator instanceof PolicyActionGenerator)
                System.out.printf(Locale.ROOT, "Proposal samples=%d duplicates=%d%n", draws, duplicates);
            Game simulation = game(root);
            JointActionEvaluator searchTrials = JointActionEvaluator.uncached(simulation, evaluator);
            start = System.nanoTime();
            JointSearchResult solved = profile.solver(evaluator, generator, limit, iterations, searchSeed,
                    PolicyOutput.AVERAGE).solve(simulation, pools, searchTrials);
            System.out.printf(Locale.ROOT, "Search seconds=%.6f; root_work=%s%n", seconds(start), searchTrials.counts());
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
                if (!reported.contains(power)) continue;
                double best = Arrays.stream(values).mapToDouble(row -> estimate(row).mean()).max().orElseThrow();
                System.out.printf(Locale.ROOT, "POWER %s pool=%d/%d log_anchor_mass=%.10f%n",
                        power, candidates.size(), limit, logMass(candidates));
                if (candidates.size() < limit) System.out.println("SHORT_POOL: actual evaluation work is below the requested budget.");
                if (hybrid.containsKey(power)) {
                    var counts = hybrid.get(power);
                    System.out.printf(Locale.ROOT, "Hybrid quotas=%d/%d available=%d/%d retained=%d/%d overlap=%d (learned/heuristic)%n",
                            counts.learnedQuota(), counts.heuristicQuota(), counts.learnedAvailable(), counts.heuristicAvailable(),
                            counts.learnedSelected(), counts.heuristicSelected(), counts.overlap());
                }
                System.out.printf(Locale.ROOT, "Fresh anchor_pool=%.8f se=%.8f search=%.8f se=%.8f best_in_pool=%.8f%n",
                        anchorEstimate.mean(), anchorEstimate.standardError(), searchEstimate.mean(), searchEstimate.standardError(), best);
                paired("UNIFORM", searchValues, reference.get(power));
                if (heuristicReference.containsKey(power))
                    paired("PLAUSIBLE", searchValues, heuristicReference.get(power));
                if (method == Method.HYBRID)
                    paired("LEARNED", searchValues, learnedReference.get(power));
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
                System.out.printf(Locale.ROOT, "Coordination support_mismatch_actions=%d convoy_mismatch_actions=%d either=%d/%d search_mass=%.8f%n",
                        badSupports, badConvoys, badActions, candidates.size(), badMass);
                System.out.println("candidate source log_learned_proposal log_anchor anchor_pool search Q fresh_value bad_own_supports bad_own_convoys");
                Bound policy = bound.get(power);
                for (int index = 0; index < candidates.size(); index++) {
                    ActionCandidate candidate = candidates.get(index);
                    System.out.printf(Locale.ROOT, "%s-P%d %s %.10f %.10f %.8f %.8f %.8f %.8f %d %d%n",
                            power, index, candidate.source(),
                            policy.proposal().logProbability(policy.encoding().encode(candidate.orders())),
                            candidate.logPrior(), prior.probabilities().get(index), result.distribution().probabilities().get(index),
                            result.meanValues().get(index), estimate(values[index]).mean(),
                            coordination.get(index).mismatchedSupports(), coordination.get(index).mismatchedConvoys());
                    System.out.println("  " + String.join("; ", candidate.orders().stream().map(policy.encoding()::token).toList()));
                }
            }
            System.out.printf(Locale.ROOT, "Fresh seconds=%.6f; root_work=%s%n", seconds(start), checks.counts());
            if (!simulation.snapshot().equals(root) || simulation.getSnapshot() != null)
                throw new IllegalStateException("Experiment changed its search root");
        }
        System.out.println("Errors describe opponent sampling only, conditional on fixed pools/search/rollout seeds.");
        System.out.println("best_in_pool is selected on these samples and is optimistic; it is not a tested policy.");
        System.out.println("These year-end estimates are diagnostics, not win rates or evidence of full-game strength.");
        System.out.println("END POLICY PROPOSAL EXPERIMENT");
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

    private static void paired(String reference, double[] values, double[] baseline) {
        double[] differences = new double[values.length];
        for (int sample = 0; sample < values.length; sample++)
            differences[sample] = values[sample] - baseline[sample];
        Estimate delta = estimate(differences);
        System.out.printf(Locale.ROOT, "Paired search_delta_vs_%s=%.8f se=%.8f%n",
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