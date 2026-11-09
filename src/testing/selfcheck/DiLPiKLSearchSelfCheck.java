package testing.selfcheck;

import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Standalone piKL equations, sampled updates, and game-restoration checks.
 */
public final class DiLPiKLSearchSelfCheck {

    private static final Evaluator SOS = new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES);
    private static int checks;

    private DiLPiKLSearchSelfCheck() { }

    public static void main(String[] args) {
        lambdaDistributions();
        analyticPolicies();
        sampledUpdates();
        phaseIntegration();
        failures();
        System.out.println("DiLPiKLSearchSelfCheck passed: " + checks + " checks");
    }

    private static void lambdaDistributions() {
        List<Double> values = new ArrayList<>(List.of(0.1, 0.8));
        List<Double> probabilities = new ArrayList<>(List.of(0.25, 0.75));
        LambdaDistribution distribution = new LambdaDistribution(values, probabilities);
        values.clear();
        probabilities.clear();
        require(distribution.values().equals(List.of(0.1, 0.8)), "Lambda values must be copied");
        expect(UnsupportedOperationException.class, () -> distribution.probabilities().clear());
        expect(UnsupportedOperationException.class, () -> distribution.values().clear());
        LambdaDistribution forced = new LambdaDistribution(List.of(0.1, 0.8), List.of(0.0, 1.0));
        Random random = new Random(9L);
        for (int sample = 0; sample < 20; sample++)
            close(forced.sample(random), 0.8, "A zero-mass type must never be selected");
        close(LambdaDistribution.fixed(0.1).sample(random), 0.1, "Singleton types must be fixed");
        require(LambdaDistribution.uniform(0.1, 0.8).probabilities().equals(List.of(0.5, 0.5)),
                "Uniform type populations must divide probability evenly");
        for (double invalid : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY })
            expect(IllegalArgumentException.class, () -> LambdaDistribution.fixed(invalid));
        expect(IllegalArgumentException.class, () -> LambdaDistribution.uniform());
        expect(IllegalArgumentException.class, () -> LambdaDistribution.uniform(0.1, 0.1));
        expect(IllegalArgumentException.class, () -> new LambdaDistribution(List.of(0.1), List.of(0.5)));
        expect(IllegalArgumentException.class, () -> new LambdaDistribution(List.of(0.1), List.of()));
        expect(IllegalArgumentException.class, () -> new LambdaDistribution(List.of(0.1), List.of(Double.NaN)));
        expect(NullPointerException.class, () -> distribution.sample(null));
        expect(NullPointerException.class, () -> LambdaDistribution.uniform((double[]) null));
    }

    private static void analyticPolicies() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic), Map.of(Province.Par, Nation.FRANCE)));
        List<ActionCandidate> actions = List.of(
                action(Nation.FRANCE, StrictMath.log(0.75), Order.hold(french)),
                action(Nation.FRANCE, StrictMath.log(0.25), Order.move(french, Province.Bel)));
        ActionGenerator generator = (request, catalog) -> actions;
        Evaluator utility = state -> values(state.board().locationOf(french) == Province.Bel ? 1 : 0, 0);
        SearchResult result = new DiLPiKLSearch(utility, generator, 2, 1,
                LambdaDistribution.fixed(0.5), 0.5, 7L).search(game(root), Nation.FRANCE);
        close(result.distribution().probabilities().get(1), 1.0 / (1 + 3 * StrictMath.exp(-2)),
                "One update must implement anchor-weighted softmax(Q/lambda)");
        require(result.meanValues().equals(List.of(0.0, 1.0)), "Result values must exclude the KL penalty");
        require(result.samplesPerCandidate() == 1, "One iteration must give each candidate one observation");
        require(result.distribution().candidates().equals(actions), "Original priors and candidate order must survive");
        expect(UnsupportedOperationException.class, () -> result.meanValues().clear());
        expect(UnsupportedOperationException.class, () -> result.distribution().probabilities().clear());

        SearchResult strongAnchor = new DiLPiKLSearch(utility, generator, 2, 1,
                LambdaDistribution.fixed(0.5), 1e10, 7L).search(game(root), Nation.FRANCE);
        close(strongAnchor.distribution().probabilities().get(1), 0.25, "A large agent lambda must approach the anchor");
        SearchResult shifted = new DiLPiKLSearch(state -> values(
                state.board().locationOf(french) == Province.Bel ? 14 : 10, 0),
                generator, 2, 1, LambdaDistribution.fixed(2), 2, 7L).search(game(root), Nation.FRANCE);
        close(shifted.distribution().probabilities().get(1), result.distribution().probabilities().get(1),
                "Payoff translation and matching payoff/lambda scaling must preserve the policy");

        List<ActionCandidate> tinyPriors = List.of(
                action(Nation.FRANCE, 0, Order.hold(french)),
                action(Nation.FRANCE, -1000, Order.move(french, Province.Bel)),
                action(Nation.FRANCE, Double.NEGATIVE_INFINITY, Order.move(french, Province.Par)));
        SearchResult tiny = new DiLPiKLSearch(state -> values(
                state.board().locationOf(french) == Province.Bel ? 1001
                        : state.board().locationOf(french) == Province.Par ? 100000 : 0, 0),
                (request, catalog) -> tinyPriors, 3, 2, LambdaDistribution.fixed(1), 1, 3L)
                .search(game(root), Nation.FRANCE);
        close(tiny.distribution().probabilities().get(1), 1.0 / (1 + StrictMath.exp(-1)),
                "Finite log priors must remain recoverable after ordinary probability underflow");
        close(tiny.distribution().probabilities().get(2), 0, "A zero-prior action must never get policy mass");
        close(tiny.meanValues().get(2), 100000, "Even zero-prior candidates must retain their measured values");

        SearchResult extreme = new DiLPiKLSearch(state -> values(
                state.board().locationOf(french) == Province.Bel ? Double.MAX_VALUE : -Double.MAX_VALUE, 0),
                generator, 2, 1, LambdaDistribution.fixed(Double.MAX_VALUE), Double.MAX_VALUE, 0L)
                .search(game(root), Nation.FRANCE);
        close(extreme.distribution().probabilities().get(1), result.distribution().probabilities().get(1),
                "Finite extreme payoffs must avoid overflow in relative logits");
    }

    private static void sampledUpdates() {
        Fixture fixture = fixture();
        Game game = game(fixture.state());
        game.resolveMovement(List.of());
        GameState root = game.snapshot();
        var history = game.processorHistory();
        Map<Nation, Integer> generated = new EnumMap<>(Nation.class);
        ActionGenerator generator = (request, catalog) -> {
            require(request.state().equals(root), "Every pool must be generated at the root");
            generated.merge(request.power(), 1, Integer::sum);
            return fixture.generator().generate(request, catalog);
        };
        List<List<Integer>> evaluated = new ArrayList<>();
        Evaluator matrix = state -> {
            require(game.snapshot().equals(root) && game.getSnapshot() == null,
                    "Every payoff must be evaluated after peek restores the root");
            require(game.processorHistory().equals(history), "Root history must survive all trials");
            int france = state.board().locationOf(fixture.french()) == Province.Bel ? 1 : 0;
            int england = state.board().locationOf(fixture.english()) == Province.NTH ? 1 : 0;
            evaluated.add(List.of(france, england));
            return values(france == england ? 1 : 0, france == england ? 0 : 1);
        };
        int iterations = 17;
        long seed = 54L;
        LambdaDistribution population = new LambdaDistribution(List.of(0.1, 0.8), List.of(0.25, 0.75));
        DiLPiKLSearch solver = new DiLPiKLSearch(matrix, generator, 2, iterations, population, 0.2, seed);
        SearchResult franceResult = solver.search(game, Nation.FRANCE);
        require(generated.equals(Map.of(Nation.FRANCE, 1, Nation.ENGLAND, 1)), "Pools must be frozen for the whole search");
        require(evaluated.size() == iterations * 3, "The played profile must be evaluated once and reused across players");

        // Independent replay of the two-action game, using direct exponentials and payoff sums.
        Random random = new Random(seed);
        for (Nation nation : root.aliveNations())
            random.nextLong();
        Map<Nation, double[]> sums = new EnumMap<>(Nation.class);
        for (Nation nation : root.aliveNations())
            sums.put(nation, new double[2]);
        for (int iteration = 0; iteration < iterations; iteration++) {
            Map<Nation, Integer> played = new EnumMap<>(Nation.class);
            for (Nation nation : root.aliveNations()) {
                double lambda = random.nextDouble() < 0.25 ? 0.1 : 0.8;
                double firstPrior = nation == Nation.FRANCE ? 0.8 : 0.4;
                double first = firstPrior * StrictMath.exp(sums.get(nation)[0] / Math.max(1, iteration) / lambda);
                double second = (1 - firstPrior) * StrictMath.exp(sums.get(nation)[1] / Math.max(1, iteration) / lambda);
                played.put(nation, random.nextDouble() < first / (first + second) ? 0 : 1);
            }
            int france = played.get(Nation.FRANCE);
            int england = played.get(Nation.ENGLAND);
            require(evaluated.get(iteration * 3).equals(List.of(france, england)),
                    "All players must sample their own type from pre-update policies");
            require(evaluated.subList(iteration * 3, iteration * 3 + 3).containsAll(
                            List.of(List.of(1 - france, england), List.of(france, 1 - england))),
                    "Every deviation must keep all opponents at the sampled action");
            for (int action = 0; action < 2; action++) {
                sums.get(Nation.FRANCE)[action] += action == england ? 1 : 0;
                sums.get(Nation.ENGLAND)[action] += action == france ? 0 : 1;
            }
        }
        for (int action = 0; action < 2; action++)
            close(franceResult.meanValues().get(action), sums.get(Nation.FRANCE)[action] / iterations,
                    "Action values must average the whole opponent history");
        double first = 0.8 * StrictMath.exp(franceResult.meanValues().get(0) / 0.2);
        double second = 0.2 * StrictMath.exp(franceResult.meanValues().get(1) / 0.2);
        close(franceResult.distribution().probabilities().get(0), first / (first + second),
                "The returned policy must use the final Q values and agent lambda");

        List<List<Integer>> firstRun = List.copyOf(evaluated);
        evaluated.clear();
        SearchResult otherLambda = new DiLPiKLSearch(matrix, generator, 2, iterations, population, 0.7, seed)
                .search(game, Nation.FRANCE);
        require(evaluated.equals(firstRun) && otherLambda.meanValues().equals(franceResult.meanValues()),
                "The output lambda must not change population play during search");
        require(!otherLambda.distribution().probabilities().equals(franceResult.distribution().probabilities()),
                "Changing the output lambda must change the final regularized policy");
        evaluated.clear();
        SearchResult englandResult = solver.search(game, Nation.ENGLAND);
        require(evaluated.equals(firstRun), "The requested nation must not change population play");
        for (int action = 0; action < 2; action++)
            close(englandResult.meanValues().get(action), sums.get(Nation.ENGLAND)[action] / iterations,
                    "Each nation must learn its own payoff vector");
    }

    private static void phaseIntegration() {
        Game game = StandardGameFactory.create1901();
        GameState initial = game.snapshot();
        Evaluator rollout = new RolloutEvaluator(SOS, new UniformActionGenerator(),
                DiLPiKLSearchSelfCheck::game, 1, 1, 0L);
        DiLPiKLSearch solver = new DiLPiKLSearch(rollout, 2, 2,
                LambdaDistribution.uniform(0.01, 0.1), 0.01, 7L);
        SearchResult result = solver.search(game, Nation.FRANCE);
        require(result.equals(solver.search(game, Nation.FRANCE)), "Seeded movement searches must repeat");
        restored(game, initial);

        UnitId french = army(Nation.FRANCE, Province.Bur);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        UnitId support = army(Nation.GERMANY, Province.Ruh);
        Game battle = game(new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY, Province.Mun, Nation.GERMANY))));
        Simulator simulator = new Simulator();
        GameState_Retreats retreats = (GameState_Retreats) simulator.advance(battle, Map.of(
                Nation.FRANCE, action(Nation.FRANCE, 0, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY, 0, Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur))));
        GameState springRetreat = new GameState_Retreats(new GameMoment(1907, GamePhase.SPRING_RETREAT),
                retreats.board(), retreats.movementResult());
        GameState winter = simulator.peek(game(retreats), Map.of(
                Nation.FRANCE, action(Nation.FRANCE, 0), Nation.GERMANY, action(Nation.GERMANY, 0)));
        for (GameState phase : List.of(springRetreat, retreats, winter)) {
            Game worker = game(phase);
            solver.search(worker, Nation.FRANCE);
            restored(worker, phase);
        }
    }

    private static void failures() {
        Fixture fixture = fixture();
        LambdaDistribution population = LambdaDistribution.fixed(0.1);
        for (double invalid : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY })
            expect(IllegalArgumentException.class, () -> new DiLPiKLSearch(SOS, 2, 2, population, invalid, 0L));
        expect(NullPointerException.class, () -> new DiLPiKLSearch(null, 2, 2, population, 0.1, 0L));
        expect(NullPointerException.class, () -> new DiLPiKLSearch(SOS, null, 2, 2, population, 0.1, 0L));
        expect(NullPointerException.class, () -> new DiLPiKLSearch(SOS, 2, 2, null, 0.1, 0L));
        expect(IllegalArgumentException.class, () -> new DiLPiKLSearch(SOS, 0, 2, population, 0.1, 0L));
        expect(IllegalArgumentException.class, () -> new DiLPiKLSearch(SOS, 2, 0, population, 0.1, 0L));
        DiLPiKLSearch solver = new DiLPiKLSearch(SOS, 2, 2, population, 0.1, 0L);
        expect(NullPointerException.class, () -> solver.search(null, Nation.FRANCE));
        expect(IllegalArgumentException.class, () -> solver.search(game(fixture.state()), Nation.TURKEY));
        Game saved = game(fixture.state());
        saved.takeSnapshot();
        GameState checkpoint = saved.getSnapshot();
        expect(IllegalStateException.class, () -> solver.search(saved, Nation.FRANCE));
        require(saved.getSnapshot() == checkpoint, "BestResponseSearch must leave an existing checkpoint untouched");
        saved.restoreFromSnapshot();

        ActionGenerator noSupport = (request, catalog) -> fixture.generator().generate(request, catalog).stream()
                .map(candidate -> new ActionCandidate(candidate.power(), candidate.orders(),
                        Double.NEGATIVE_INFINITY, candidate.source())).toList();
        Game worker = game(fixture.state());
        expect(IllegalStateException.class, () -> new DiLPiKLSearch(state -> {
            throw new AssertionError("All-zero priors must fail before simulation");
        }, noSupport, 2, 2, population, 0.1, 0L).search(worker, Nation.FRANCE));
        restored(worker, fixture.state());

        IllegalStateException failure = new IllegalStateException("Injected failure");
        int[] calls = { 0 };
        expect(IllegalStateException.class, () -> new DiLPiKLSearch(state -> {
            if (++calls[0] == 2)
                throw failure;
            return SOS.evaluate(state);
        }, fixture.generator(), 2, 2, population, 0.1, 0L).search(worker, Nation.FRANCE));
        restored(worker, fixture.state());
        expect(IllegalStateException.class, () -> new DiLPiKLSearch(state -> Map.of(Nation.FRANCE, 1.0),
                fixture.generator(), 2, 2, population, 0.1, 0L).search(worker, Nation.FRANCE));
        restored(worker, fixture.state());
        Game partial = Game.fromState(new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                fixture.state().board()), movement(), input -> { throw failure; }, new AdjustmentProcessor());
        GameState before = partial.snapshot();
        require(expect(IllegalStateException.class, () -> new DiLPiKLSearch(SOS, fixture.generator(),
                        2, 2, population, 0.1, 0L).search(partial, Nation.FRANCE)) == failure,
                "A failed phase transition must propagate its original exception");
        restored(partial, before);
    }

    private static Fixture fixture() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        UnitId english = new UnitId(UUID.randomUUID(), Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic, english, Province.Lon),
                        Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND)));
        ActionGenerator generator = (request, catalog) -> request.power() == Nation.FRANCE
                ? List.of(action(Nation.FRANCE, StrictMath.log(0.8), Order.hold(french)),
                action(Nation.FRANCE, StrictMath.log(0.2), Order.move(french, Province.Bel)))
                : List.of(action(Nation.ENGLAND, StrictMath.log(0.4), Order.hold(english)),
                action(Nation.ENGLAND, StrictMath.log(0.6), Order.move(english, Province.NTH)));
        return new Fixture(state, french, english, generator);
    }

    private static Game game(GameState state) {
        return Game.fromState(state, movement(), new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static MovementProcessor movement() {
        return new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L);
    }

    private static UnitId army(Nation nation, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, UnitType.ARMY, origin);
    }

    private static ActionCandidate action(Nation nation, double prior, OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), prior, ActionCandidate.Source.HEURISTIC);
    }

    private static Map<Nation, Double> values(double france, double england) {
        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            result.put(nation, nation == Nation.FRANCE ? france : nation == Nation.ENGLAND ? england : 0.0);
        return result;
    }

    private static void restored(Game game, GameState before) {
        require(game.snapshot().equals(before) && game.getSnapshot() == null, "The root and checkpoint must be restored");
        require(game.processorHistory().isEmpty(), "Speculative history must be discarded");
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-10, message + ": got " + actual);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static RuntimeException expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return exception;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + exception, exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private record Fixture(GameState state, UnitId french, UnitId english, ActionGenerator generator) { }

}