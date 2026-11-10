package _app;

import adjudication.Justice;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementProcessor;

import java.util.*;

/**
 * Compares rollout budgets and policy outputs at one fixed position.
 *
 * <p>Root pools are generated once. Each rollout budget starts a fresh solve
 * with the same pools and search seed. FINAL and AVERAGE come from that same
 * solve. Sampled receipts then evaluate each output against its frozen
 * population policies, using fresh opponent draws and the same payoff
 * function. No match is played and no chosen action is submitted.</p>
 *
 * <p>OPENING is Spring 1901. WINTER uses the board entering Winter 1901 in
 * the original seed-7 demonstration, independently of experiment settings.
 * Explicit pool, search, and rollout seeds are diagnostic streams; they do
 * not reproduce a seat's full-game search by using the game seed alone.</p>
 */
public final class AgentExperimentConsoleApp {

    private static final String USAGE = "Usage: AgentExperimentConsoleApp "
            + "[--position <opening|winter>] [--power <all|nation>] "
            + "[--root-generator <uniform|plausible>] [--rollout-generator <uniform|plausible>] "
            + "[--candidates <count>] [--iterations <count>] "
            + "[--rollouts <count[,count...]>] [--rollout-candidates <count>] "
            + "[--pool-seed <long>] [--search-seed <long>] [--rollout-seed <long>] "
            + "[--receipt-samples <count>] [--receipt-seed <long>] "
            + "[--movement-policy <policy>]";

    private enum Position { OPENING, WINTER }  // only 2 possible for this particular experiment

    private AgentExperimentConsoleApp() { }

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            System.out.println("Defaults: opening, all nations, uniform/uniform, 4 candidates, 8 iterations, "
                    + "rollouts 1,4, pool/search/rollout seeds 7, JUDGE.");
            System.out.println("--rollout-candidates defaults to --candidates. Both outputs are always printed.");
            System.out.println("Receipts: 32 opponent samples per output, seed 17; 0 samples disables them.");
            System.out.println("--power filters result tables only; search and receipts still cover all living nations.");
            return;
        }
        Position position = Position.OPENING;
        Set<Nation> reported = EnumSet.allOf(Nation.class);
        AgentSettings.Generator rootGenerator = AgentSettings.Generator.UNIFORM;
        AgentSettings.Generator rolloutGenerator = AgentSettings.Generator.UNIFORM;
        int candidates = 4, iterations = 8, receiptSamples = 32;
        Integer rolloutCandidates = null;
        List<Integer> budgets = List.of(1, 4);
        long poolSeed = 7, searchSeed = 7, rolloutSeed = 7, receiptSeed = 17;
        MovementProcessor.Policy movementPolicy = MovementProcessor.Policy.JUDGE;

        for (int index = 0; index < args.length; index++) {
            if (index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);
            switch (args[index]) {
                case "--position" -> position = Position.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--power" -> {
                    String value = args[++index];
                    reported = value.equalsIgnoreCase("all") ? EnumSet.allOf(Nation.class)
                            : EnumSet.of(Nation.valueOf(value.toUpperCase(Locale.ROOT)));
                }
                case "--root-generator" -> rootGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--rollout-generator" -> rolloutGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--candidates" -> candidates = Integer.parseInt(args[++index]);
                case "--iterations" -> iterations = Integer.parseInt(args[++index]);
                case "--rollouts" -> budgets = Arrays.stream(args[++index].split(",", -1))
                        .map(Integer::parseInt).toList();
                case "--rollout-candidates" -> rolloutCandidates = Integer.parseInt(args[++index]);
                case "--pool-seed" -> poolSeed = Long.parseLong(args[++index]);
                case "--search-seed" -> searchSeed = Long.parseLong(args[++index]);
                case "--rollout-seed" -> rolloutSeed = Long.parseLong(args[++index]);
                case "--receipt-samples" -> receiptSamples = Integer.parseInt(args[++index]);
                case "--receipt-seed" -> receiptSeed = Long.parseLong(args[++index]);
                case "--movement-policy" -> movementPolicy = MovementProcessor.Policy.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException(USAGE);
            }
        }
        if (budgets.stream().anyMatch(count -> count < 1) || new HashSet<>(budgets).size() != budgets.size())
            throw new IllegalArgumentException("Rollout budgets must be positive and distinct");
        if (receiptSamples < 0 || receiptSamples == 1)
            throw new IllegalArgumentException("Receipt samples must be zero or at least two");
        int continuationLimit = rolloutCandidates == null ? candidates : rolloutCandidates;
        AgentSettings first = new AgentSettings(AgentSettings.Method.DIL_PIKL, candidates, iterations,
                budgets.getFirst(), continuationLimit, PolicyOutput.AVERAGE, rootGenerator, rolloutGenerator);
        GameRunner.Options configuration = options(first, movementPolicy);
        GameState root = position == Position.OPENING
                ? StandardGameFactory.create1901().snapshot() : winter(configuration);
        Map<Nation, List<ActionCandidate>> pools = pools(root, rootGenerator, candidates, poolSeed);
        PaperSearchProfile profile = PaperSearchProfile.forState(root);

        System.out.println("BEGIN FIXED-POSITION EXPERIMENT");
        System.out.println("Position: " + position + " at " + root.moment() + "; reported nations: " + reported);
        System.out.println("Rules: " + configuration.rulesetId() + "; scoring=" + configuration.scoring());
        System.out.println("Root generator: " + rootGenerator + "; candidates=" + candidates);
        System.out.println("Rollout generator: " + rolloutGenerator + "; candidates=" + continuationLimit);
        System.out.println("Iterations: " + iterations + "; rollout budgets=" + budgets);
        System.out.printf(Locale.ROOT, "Seeds: pools=%d search=%d rollout=%d%n", poolSeed, searchSeed, rolloutSeed);
        System.out.printf(Locale.ROOT, "Receipts: samples=%d per candidate per output; seed=%d%n",
                receiptSamples, receiptSeed);
        System.out.println("Profile: " + profile + "; population=" + profile.population()
                + "; playing_lambda=" + profile.agentLambda() + "; temperature=" + profile.temperature());
        System.out.println("Q describes sampled search opponents, not a fresh policy evaluation.");
        System.out.println("Changing rollout settings can change the subsequent sampled opponent history.");
        System.out.println("The root pools below remain fixed across every rollout budget.");
        System.out.println("p_FINAL/p_AVERAGE are playing policies; population columns mix the configured lambda types.");
        System.out.println("Fresh values use frozen opponent populations in the named output mode.");
        System.out.println("Receipt draws use a separate RNG; rollout settings and the payoff function stay unchanged.");
        System.out.println("Standard errors cover opponent sampling, not rollout uncertainty or response measures.");
        System.out.println("--power filters tables only; search and receipt work still cover all living nations.");
        printPools(root, pools);

        for (int count : budgets) {
            AgentSettings settings = new AgentSettings(AgentSettings.Method.DIL_PIKL, candidates, iterations,
                    count, continuationLimit, PolicyOutput.AVERAGE, rootGenerator, rolloutGenerator);
            GameRunner.Options rules = options(settings, movementPolicy);
            Game game = rules.createGame(root);
            var evaluator = new RolloutEvaluator(new ScoringEvaluator(rules.scoring()),
                    rolloutGenerator.create(), rules::createGame, count, continuationLimit, rolloutSeed);
            JointActionEvaluator trials = JointActionEvaluator.uncached(game, evaluator);
            long started = System.nanoTime();
            JointSearchResult solved = profile.solver(evaluator, rootGenerator.create(),
                    candidates, iterations, searchSeed, PolicyOutput.AVERAGE).solve(game, pools, trials);
            double elapsed = (System.nanoTime() - started) / 1e9;
            System.out.printf(Locale.ROOT, "%nROLLOUTS %d; samples_per_candidate=%d%n", count, iterations);
            printSearch(solved, reported);
            System.out.println("Root work: " + trials.counts());
            System.out.printf(Locale.ROOT, "Solve elapsed: %.3f seconds%n", elapsed);
            if (receiptSamples == 0) {
                System.out.println("Fresh policy evaluation disabled.");
                continue;
            }
            for (PolicyOutput output : List.of(PolicyOutput.FINAL, PolicyOutput.AVERAGE)) {
                JointSearchResult selected = solved.withOutput(output);
                JointActionEvaluator receiptTrials = JointActionEvaluator.uncached(rules.createGame(root), evaluator);
                System.out.printf(Locale.ROOT, "%nFRESH %s; opponent_samples=%d; receipt_seed=%d%n",
                        output, receiptSamples, receiptSeed);
                started = System.nanoTime();
                List<TypeReceipt> receipts = TypeReceipt.sampled(selected, receiptTrials, receiptSamples, receiptSeed);
                elapsed = (System.nanoTime() - started) / 1e9;
                for (TypeReceipt receipt : receipts)
                    if (receipt.playing() && reported.contains(receipt.power()))
                        printReceipt(receipt);
                System.out.println("Receipt root work: " + receiptTrials.counts());
                System.out.printf(Locale.ROOT, "Receipt elapsed: %.3f seconds%n", elapsed);
            }
        }
        System.out.println("END FIXED-POSITION EXPERIMENT");
    }

    private static void printSearch(JointSearchResult solved, Set<Nation> reported) {
        JointSearchResult last = solved.withOutput(PolicyOutput.FINAL);
        JointSearchResult average = solved.withOutput(PolicyOutput.AVERAGE);
        for (Nation power : solved.results().keySet()) {
            if (!reported.contains(power))
                continue;
            SearchResult result = solved.results().get(power);
            System.out.printf(Locale.ROOT, "SEARCH %s; playing_lambda=%.10g; final_kappa=%.10g%n",
                    power, solved.settings().agentLambda(), solved.temperature(power, solved.settings().agentLambda()));
            System.out.println("candidate Q p_FINAL p_AVERAGE population_FINAL population_AVERAGE");
            for (int index = 0; index < result.meanValues().size(); index++)
                System.out.printf(Locale.ROOT, "%s-P%d %.10f %.10f %.10f %.10f %.10f%n", power, index,
                        result.meanValues().get(index),
                        last.results().get(power).distribution().probabilities().get(index),
                        average.results().get(power).distribution().probabilities().get(index),
                        last.populationPolicies().get(power).probabilities().get(index),
                        average.populationPolicies().get(power).probabilities().get(index));
        }
    }

    private static void printReceipt(TypeReceipt receipt) {
        System.out.printf(Locale.ROOT, "NATION %s; playing_lambda=%.10g%n", receipt.power(), receipt.lambda());
        System.out.println("candidate fresh_value standard_error");
        for (int index = 0; index < receipt.freshValues().size(); index++)
            System.out.printf(Locale.ROOT, "%s-P%d %.10f %.10f%n", receipt.power(), index,
                    receipt.freshValues().get(index), receipt.standardErrors().get(index));
        System.out.printf(Locale.ROOT,
                "Playing policy: expected_score=%.10f response_difference=%.10f "
                        + "regularized_gain=%.10f temperature_difference=%.10f%n",
                receipt.expectedScore(), receipt.responseResidual(),
                receipt.regularizedGain(), receipt.temperatureResidual());
    }

    private static GameRunner.Options options(AgentSettings settings, MovementProcessor.Policy policy) {
        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            agents.put(nation, settings);
        return new GameRunner.Options(1903, 7, ScoringSystem.SUM_OF_SQUARES, policy,
                policy == MovementProcessor.Policy.JUDGE ? 1 : Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT, agents);
    }

    private static Map<Nation, List<ActionCandidate>> pools(GameState root,
                                                            AgentSettings.Generator choice,
                                                            int limit, long seed) {
        ActionGenerator generator = choice.create();
        LegalOrderGenerator legal = new LegalOrderGenerator();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        Random random = new Random(seed);
        for (Nation nation : root.aliveNations())
            pools.put(nation, List.copyOf(generator.generate(
                    new ActionGenerationRequest(root, nation, limit, random.nextLong()), legal.generate(root, nation))));
        return Collections.unmodifiableMap(pools);
    }

    private static GameState winter(GameRunner.Options rules) {
        BoardState opening = StandardGameFactory.create1901().board();
        Map<Province, Province> moved = Map.ofEntries(
                Map.entry(Province.Lvp, Province.Edi), Map.entry(Province.Edi, Province.NAO),
                Map.entry(Province.Lon, Province.Yor), Map.entry(Province.Mar, Province.Spa),
                Map.entry(Province.Bre, Province.Gas), Map.entry(Province.Ber, Province.Pru),
                Map.entry(Province.Kie, Province.Den), Map.entry(Province.Bud, Province.Ser),
                Map.entry(Province.Vie, Province.Boh), Map.entry(Province.Sev, Province.Rum));
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        opening.locations().forEach((unit, location) -> locations.put(unit, moved.getOrDefault(location, location)));
        Game game = rules.createGame(new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT),
                new BoardState(locations, opening.owners())));
        // Holding this fixed board supplies the adjustment context and Fall ownership.
        game.resolveMovement(locations.keySet().stream().map(Order::hold).toList());
        if (!(game.snapshot() instanceof GameState_Adjustments))
            throw new IllegalStateException("Winter fixture did not reach adjustments");
        return game.snapshot();
    }

    private static void printPools(GameState root, Map<Nation, List<ActionCandidate>> pools) {
        System.out.println("Board:");
        root.board().locations().entrySet().stream()
                .map(entry -> OrderForm.unitText(entry.getKey(), entry.getValue())).sorted()
                .forEach(unit -> System.out.println("  " + unit));
        System.out.println("Owned centers:");
        root.board().owners().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println("  " + entry.getKey() + " = " + entry.getValue()));
        for (var entry : pools.entrySet()) {
            List<ActionCandidate> candidates = entry.getValue();
            ActionDistribution anchor = ActionDistribution.fromPriors(candidates);
            System.out.println("POOL " + entry.getKey() + " candidates=" + candidates.size());
            for (int index = 0; index < candidates.size(); index++) {
                ActionCandidate candidate = candidates.get(index);
                System.out.printf(Locale.ROOT, "  %s-P%d source=%s log_prior=%.10f anchor_pool=%.10f%n",
                        entry.getKey(), index, candidate.source(), candidate.logPrior(), anchor.probabilities().get(index));
                if (candidate.orders().isEmpty())
                    System.out.println("    (no submitted orders)");
                else
                    candidate.orders().stream().map(order -> OrderForm.format(order,
                                    order instanceof Order movement ? root.board().locationOf(movement.unit())
                                            : order instanceof DisbandOrder disband ? root.board().locationOf(disband.unit())
                                            : order.origin())).sorted()
                            .forEach(order -> System.out.println("    " + order));
            }
        }
    }
}