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
 * solve. No match is played and no chosen action is submitted.</p>
 *
 * <p>OPENING is Spring 1901. WINTER uses the board entering Winter 1901 in
 * the original seed-7 demonstration, independently of experiment settings.
 * Explicit pool, search, and rollout seeds are diagnostic streams; they do
 * not reproduce a seat's full-game search by using the game seed alone.</p>
 */
public final class AgentExperimentConsoleApp {

    private static final String USAGE = "Usage: AgentExperimentConsoleApp "
            + "[--position <opening|winter>] [--power <nation>] "
            + "[--root-generator <uniform|plausible>] [--rollout-generator <uniform|plausible>] "
            + "[--candidates <count>] [--iterations <count>] "
            + "[--rollouts <count[,count...]>] [--rollout-candidates <count>] "
            + "[--pool-seed <long>] [--search-seed <long>] [--rollout-seed <long>] "
            + "[--movement-policy <policy>]";

    private enum Position { OPENING, WINTER }  // only 2 possible for this particular experiment

    private AgentExperimentConsoleApp() { }

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            System.out.println("Defaults: opening, FRANCE, uniform/uniform, 4 candidates, 8 iterations, "
                    + "rollouts 1,4, all seeds 7, JUDGE.");
            System.out.println("--rollout-candidates defaults to --candidates. Both outputs are always printed.");
            return;
        }
        Position position = Position.OPENING;
        Nation power = Nation.FRANCE;
        AgentSettings.Generator rootGenerator = AgentSettings.Generator.UNIFORM;
        AgentSettings.Generator rolloutGenerator = AgentSettings.Generator.UNIFORM;
        int candidates = 4, iterations = 8;
        Integer rolloutCandidates = null;
        List<Integer> budgets = List.of(1, 4);
        long poolSeed = 7, searchSeed = 7, rolloutSeed = 7;
        MovementProcessor.Policy movementPolicy = MovementProcessor.Policy.JUDGE;

        for (int index = 0; index < args.length; index++) {
            if (index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);
            switch (args[index]) {
                case "--position" -> position = Position.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--power" -> power = Nation.valueOf(args[++index].toUpperCase(Locale.ROOT));
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
                case "--movement-policy" -> movementPolicy = MovementProcessor.Policy.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException(USAGE);
            }
        }
        if (budgets.stream().anyMatch(count -> count < 1) || new HashSet<>(budgets).size() != budgets.size())
            throw new IllegalArgumentException("Rollout budgets must be positive and distinct");
        int continuationLimit = rolloutCandidates == null ? candidates : rolloutCandidates;
        AgentSettings first = new AgentSettings(AgentSettings.Method.DIL_PIKL, candidates, iterations,
                budgets.getFirst(), continuationLimit, PolicyOutput.AVERAGE, rootGenerator, rolloutGenerator);
        GameRunner.Options configuration = options(first, movementPolicy);
        GameState root = position == Position.OPENING
                ? StandardGameFactory.create1901().snapshot() : winter(configuration);
        Map<Nation, List<ActionCandidate>> pools = pools(root, rootGenerator, candidates, poolSeed);
        PaperSearchProfile profile = PaperSearchProfile.forState(root);

        System.out.println("BEGIN FIXED-POSITION EXPERIMENT");
        System.out.println("Position: " + position + " at " + root.moment() + "; reported nation: " + power);
        System.out.println("Rules: " + configuration.rulesetId() + "; scoring=" + configuration.scoring());
        System.out.println("Root generator: " + rootGenerator + "; candidates=" + candidates);
        System.out.println("Rollout generator: " + rolloutGenerator + "; candidates=" + continuationLimit);
        System.out.println("Iterations: " + iterations + "; rollout budgets=" + budgets);
        System.out.printf(Locale.ROOT, "Seeds: pools=%d search=%d rollout=%d%n", poolSeed, searchSeed, rolloutSeed);
        System.out.println("Profile: " + profile + "; population=" + profile.population()
                + "; playing_lambda=" + profile.agentLambda() + "; temperature=" + profile.temperature());
        System.out.println("Q describes sampled search opponents, not a fresh policy evaluation.");
        System.out.println("Changing rollout settings can change the subsequent sampled opponent history.");
        System.out.println("The root pools below remain fixed across every rollout budget.");
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
            JointSearchResult last = solved.withOutput(PolicyOutput.FINAL);
            double elapsed = (System.nanoTime() - started) / 1e9;
            SearchResult average = solved.results().get(power), finale = last.results().get(power);
            System.out.printf(Locale.ROOT, "%nROLLOUTS %d; samples_per_candidate=%d; final_kappa=%.10g%n",
                    count, average.samplesPerCandidate(), solved.temperature(power, profile.agentLambda()));
            System.out.println("candidate Q p_FINAL p_AVERAGE");
            for (int index = 0; index < pools.get(power).size(); index++)
                System.out.printf(Locale.ROOT, "%s-P%d %.10f %.10f %.10f%n", power, index,
                        average.meanValues().get(index), finale.distribution().probabilities().get(index),
                        average.distribution().probabilities().get(index));
            System.out.println("Root work: " + trials.counts());
            System.out.printf(Locale.ROOT, "Solve elapsed: %.3f seconds%n", elapsed);
        }
        System.out.println("END FIXED-POSITION EXPERIMENT");
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
                                                            AgentSettings.Generator choice, int limit, long seed) {
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