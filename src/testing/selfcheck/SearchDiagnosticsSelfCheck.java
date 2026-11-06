package testing.selfcheck;

import _app.CounterSearchApp;
import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import performance.PerformanceStats;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Cache boundaries, response reports, processor wiring, and convergence targets.
 */
public final class SearchDiagnosticsSelfCheck {

    private static int checks;
    private static final Evaluator EVALUATOR = new RolloutEvaluator(
            new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
            new UniformActionGenerator(), SearchDiagnosticsSelfCheck::game, 1, 1, 7);

    private SearchDiagnosticsSelfCheck() {      }

    public static void main(String[] args) {
        caching();
        failures();
        adjustments();
        continuationPolicy();
        responseReports();
        experiments();
        System.out.println("SearchDiagnosticsSelfCheck passed: " + checks + " checks");
    }

    private static void caching() {
        var fixture = CounterSearchApp.fixture();
        Game game = game(fixture.root());
        AtomicInteger calls = new AtomicInteger();
        Evaluator evaluator = state -> {
            calls.incrementAndGet();
            return EVALUATOR.evaluate(state);
        };
        JointActionEvaluator cache = JointActionEvaluator.deterministic(
                game, evaluator, 1, new PerformanceStats(true));
        Map<Nation, ActionCandidate> first = joint(fixture, 0, 0);
        Map<Nation, Double> expected = cache.evaluate(first);
        ActionCandidate france = first.get(Nation.FRANCE);
        List<OrderForm> reversed = new ArrayList<>(france.orders());
        Collections.reverse(reversed);
        Map<Nation, ActionCandidate> relabeled = new EnumMap<>(first);
        relabeled.put(Nation.FRANCE, new ActionCandidate(Nation.FRANCE, reversed, 0,
                ActionCandidate.Source.EXPLORATION));
        require(cache.evaluate(relabeled).equals(expected), "Metadata or order order changed a payoff");
        require(calls.get() == 1 && cache.counts().hits() == 1, "Equivalent submissions did not hit the cache");
        expect(UnsupportedOperationException.class, () -> expected.clear());
        cache.evaluate(joint(fixture, 1, 0));
        cache.evaluate(first);
        require(calls.get() == 3 && cache.counts().evictions() == 2 && cache.counts().entries() == 1,
                "Bounded eviction did not retain one entry");
        JointActionEvaluator full = JointActionEvaluator.deterministic(
                game, evaluator, 64, new PerformanceStats(true));
        DiLPiKLSearch solver = solver(evaluator, 128);
        JointSearchResult uncached = solver.solve(game, fixture.probes());
        JointSearchResult cached = solver.solve(game, fixture.probes(), full);
        require(cached.equals(uncached), "Caching changed sampling or Q");
        require(full.counts().simulations() == 4, "Two complete pools should need four unique profiles");
        require(full.counts().requests() == 128 * 3, "Joint request count differs from the solver budget");
        require(solver.solve(game, fixture.probes(), full).equals(cached), "A warm cache changed a solve");
        require(full.counts().simulations() == 4, "A repeated solve missed cached profiles");
        Search baseline = new Search(evaluator, (request, catalog) -> fixture.probes().get(request.power()), 2, 4, 7);
        require(baseline.search(game, Nation.FRANCE).equals(baseline.search(game, Nation.FRANCE, full)),
                "Caching changed baseline Search");
        require(full.timings().stream().anyMatch(row -> row.name().equals("fixed-pool solve")),
                "Solve timing was omitted");
        expect(IllegalArgumentException.class, () -> solver.solve(game(fixture.root()), fixture.probes(), full));
        expect(IllegalArgumentException.class, () -> solver(EVALUATOR, 1).solve(game, fixture.probes(), full));
        game.takeSnapshot();
        expect(IllegalStateException.class, () -> full.evaluate(first));
        game.restoreFromSnapshot();
        require(full.evaluate(first).equals(expected), "A rejected checkpoint invalidated the cache");
        require(game.snapshot().equals(fixture.root()) && game.processorHistory().isEmpty(),
                "Cached search changed the root");
        game.resolveMovement(List.of());
        expect(IllegalStateException.class, () -> full.evaluate(first));
    }

    private static void failures() {
        var fixture = CounterSearchApp.fixture();
        Game game = game(fixture.root());
        Map<Nation, ActionCandidate> joint = joint(fixture, 0, 0);
        AtomicInteger calls = new AtomicInteger();
        Evaluator changing = state -> values(calls.incrementAndGet(), 0);
        JointActionEvaluator uncached = JointActionEvaluator.uncached(game, changing);
        require(!uncached.evaluate(joint).equals(uncached.evaluate(joint)),
                "Uncached evaluation froze a changing payoff");
        require(uncached.counts().entries() == 0 && uncached.counts().simulations() == 2,
                "Uncached calls retained results");
        AtomicInteger attempts = new AtomicInteger();
        Evaluator sometimesInvalid = state -> values(attempts.incrementAndGet() == 1 ? Double.NaN : 0.5, 0.5);
        JointActionEvaluator cache = JointActionEvaluator.deterministic(
                game, sometimesInvalid, 4, new PerformanceStats(true));
        expect(IllegalStateException.class, () -> cache.evaluate(joint));
        require(cache.counts().entries() == 0 && cache.counts().failures() == 1, "Invalid payoff was cached");
        cache.evaluate(joint);
        cache.evaluate(joint);
        require(attempts.get() == 2, "A failed result was retained or a successful result was lost");
        expect(IllegalArgumentException.class, () -> cache.evaluate(Map.of(
                Nation.FRANCE, joint.get(Nation.GERMANY), Nation.GERMANY, joint.get(Nation.FRANCE))));
        expect(IllegalArgumentException.class, () -> cache.evaluate(Map.of(Nation.FRANCE, joint.get(Nation.FRANCE))));
        expect(IllegalArgumentException.class, () -> JointActionEvaluator.deterministic(
                game, EVALUATOR, 0, new PerformanceStats(false)));
        expect(IllegalArgumentException.class, () -> DiLPiKLSearch.policy(
                fixture.probes().get(Nation.FRANCE), List.of(0.0, Double.NaN), 0.03));
        require(game.getSnapshot() == null && game.snapshot().equals(fixture.root()),
                "Failed evaluation did not leave the restored root");
    }

    private static void adjustments() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        Game game = game(new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(unit, Province.Par),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE, Province.Mar, Nation.FRANCE))));
        game.resolveMovement(List.of());
        require(game.phase() == GamePhase.WINTER_ADJUSTMENT, "Adjustment fixture did not reach winter");
        GameState root = game.snapshot();
        JointActionEvaluator cache = JointActionEvaluator.deterministic(
                game, state -> values(0, 0), 8, new PerformanceStats(false));
        WaiveOrder waive = new WaiveOrder(Nation.FRANCE);
        for (int count = 1; count <= 2; count++)
            cache.evaluate(Map.of(Nation.FRANCE, new ActionCandidate(Nation.FRANCE,
                    Collections.nCopies(count, waive), 0, ActionCandidate.Source.HEURISTIC)));
        require(cache.counts().entries() == 2 && cache.counts().simulations() == 2,
                "Cache keys lost repeated-waive multiplicity");
        expect(IllegalArgumentException.class, () -> cache.evaluate(Map.of(Nation.FRANCE,
                new ActionCandidate(Nation.FRANCE, List.of(waive, waive, waive), 0,
                        ActionCandidate.Source.HEURISTIC))));
        require(game.snapshot().equals(root) && game.getSnapshot() == null, "Adjustment root was not restored");
    }

    private static void continuationPolicy() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(unit, Province.Par), Map.of(Province.Par, Nation.FRANCE)));
        PerformanceStats timings = new PerformanceStats(true);
        MovementProcessor.Diagnostics diagnostics = new MovementProcessor.Diagnostics(timings);
        Function<GameState, Game> games = state -> Game.fromState(state,
                new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0, diagnostics),
                new RetreatProcessor(), new AdjustmentProcessor());
        Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), games, 1, 1, 7);
        JointActionEvaluator trials = JointActionEvaluator.uncached(games.apply(root), evaluator, timings);
        trials.evaluate(Map.of(Nation.FRANCE, new ActionCandidate(Nation.FRANCE, List.of(Order.hold(unit)), 0,
                ActionCandidate.Source.HEURISTIC)));
        require(diagnostics.snapshot().movements() == 2 && diagnostics.snapshot().plainCalls() == 2,
                "Spring root and Fall continuation did not share the fast policy");
        require(diagnostics.snapshot().shuffledTrials() == 0, "Continuation silently used Justice");
    }

    private static void responseReports() {
        var fixture = CounterSearchApp.fixture();
        Game game = game(fixture.root());
        var probes = fixture.probes();
        Map<Nation, List<ActionCandidate>> initial = Map.of(
                Nation.FRANCE, List.of(probes.get(Nation.FRANCE).getFirst()),
                Nation.GERMANY, List.of(probes.get(Nation.GERMANY).getFirst()));
        List<OrderForm> extraOrders = new ArrayList<>(probes.get(Nation.FRANCE).get(1).orders());
        UnitId pic = ((Order) extraOrders.getFirst()).unit();
        extraOrders.set(0, Order.move(pic, Province.Par));
        ActionCandidate extra = new ActionCandidate(Nation.FRANCE, extraOrders,
                fixture.prior().logProbability(Nation.FRANCE, extraOrders), ActionCandidate.Source.HEURISTIC);
        ActionGenerator generator = (request, catalog) -> request.power() == Nation.FRANCE
                ? List.of(probes.get(Nation.FRANCE).get(0), probes.get(Nation.FRANCE).get(1),
                extra, probes.get(Nation.FRANCE).get(1))
                : probes.get(Nation.GERMANY);
        JointActionEvaluator trials = JointActionEvaluator.deterministic(game, EVALUATOR, 64,
                new PerformanceStats(true));
        JointSearchResult solved = solver(EVALUATOR, 1).solve(game, initial, trials);
        ResponseSearch responses = new ResponseSearch(EVALUATOR, generator, 4, 4, 1, 0.001);
        ResponseSearch.Report report = responses.discover(game, solved, fixture.prior(), 7, trials);
        require(count(report, Nation.FRANCE, ResponseSearch.Decision.DUPLICATE) == 2, "Duplicate count differs");
        require(count(report, Nation.FRANCE, ResponseSearch.Decision.SELECTED) == 1, "Selected count differs");
        require(count(report, Nation.FRANCE, ResponseSearch.Decision.ADDITION_LIMIT) == 1,
                "Addition-limit rejection was omitted");
        require(count(report, Nation.GERMANY, ResponseSearch.Decision.BELOW_GAIN) == 1,
                "Threshold rejection was omitted");
        ActionPrior excluding = (nation, orders) -> nation == Nation.FRANCE && orders.equals(extraOrders)
                ? Double.NEGATIVE_INFINITY : fixture.prior().logProbability(nation, orders);
        report = responses.discover(game, solved, excluding, 7, trials);
        require(count(report, Nation.FRANCE, ResponseSearch.Decision.ZERO_PRIOR) == 1, "Zero-prior rejection omitted");
        final ResponseSearch.Report immutable = report;
        expect(UnsupportedOperationException.class, () -> immutable.proposals().clear());
        expect(UnsupportedOperationException.class, () -> immutable.proposals().get(Nation.FRANCE).clear());
        Evaluator positional = state -> values(state.board().locationOf(pic) == Province.Par ? 1 : 0, 0);
        JointActionEvaluator positionalTrials = JointActionEvaluator.deterministic(
                game, positional, 64, new PerformanceStats(false));
        DiLPiKLSearch solver = solver(positional, 2);
        ResponseSearch positionalResponses = new ResponseSearch(positional, generator, 4, 4, 1, 0);
        Map<Nation, List<ActionCandidate>> capped = Map.of(
                Nation.FRANCE, probes.get(Nation.FRANCE), Nation.GERMANY, initial.get(Nation.GERMANY));
        var result = new AdaptiveDiLPiKLSearch(solver, positionalResponses, 2, 2, 7)
                .search(game, Nation.FRANCE, capped, fixture.prior(), positionalTrials);
        require(result.reason() == AdaptiveDiLPiKLSearch.StopReason.POOL_LIMIT, "Pool cap was not reported");
        require(result.expansions().getFirst().blocked().get(Nation.FRANCE).equals(List.of(extra)),
                "Blocked addition was not retained in the report");
        require(result.solves().size() == 1, "An unchanged pool was needlessly re-solved");
    }

    private static void experiments() {
        for (MovementProcessor.Policy policy : List.of(
                MovementProcessor.Policy.JUDGE, MovementProcessor.Policy.SZYKMAN_JUSTICE))
            for (long seed : new long[]{7, 17, 31}) {
                var benchmark = CounterSearchApp.benchmark(seed, new CounterSearchApp.Options(policy, 8_192, 0, 64));
                require(benchmark.meetsTarget(), "Complete-pool convergence target failed: " + seed);
                require(benchmark.trials().simulations() == 4, "Complete benchmark was not cached by joint profile");
            }
        var cached = CounterSearchApp.run(7, false, new CounterSearchApp.Options(
                MovementProcessor.Policy.JUDGE, 128, 3, 64));
        var uncached = CounterSearchApp.run(7, false, new CounterSearchApp.Options(
                MovementProcessor.Policy.JUDGE, 128, 3, 0));
        require(cached.search().equals(uncached.search()) && cached.checks().equals(uncached.checks()),
                "Caching changed expansion, frozen samples, or diagnostic values");
        require(cached.search().expansions().size() == 3, "Terminal unsuccessful discovery was omitted");
        for (var check : cached.checks())
            for (var value : check.values()) {
                close(value.gain(), value.withinPoolGap() + value.missingProbeGap(), "Gap decomposition differs");
                require(value.responseResidual() >= 0 && value.responseResidual() <= 1, "Invalid response residual");
            }
        var finalPools = new EnumMap<Nation, List<ActionCandidate>>(Nation.class);
        cached.search().joint().populationPolicies().forEach((nation, policy) -> finalPools.put(nation, policy.candidates()));
        require(solver(EVALUATOR, 128).solve(game(cached.root()), finalPools).equals(cached.search().joint()),
                "Expanded pools did not receive a fresh final solve");
    }

    private static long count(ResponseSearch.Report report, Nation nation, ResponseSearch.Decision decision) {
        return report.proposals().get(nation).stream().filter(proposal -> proposal.decision() == decision).count();
    }

    private static DiLPiKLSearch solver(Evaluator evaluator, int iterations) {
        return new DiLPiKLSearch(evaluator, 2, iterations, LambdaDistribution.fixed(0.03), 0.03, 7);
    }

    private static Map<Nation, ActionCandidate> joint(CounterSearchApp.Fixture fixture, int france, int germany) {
        return Map.of(Nation.FRANCE, fixture.probes().get(Nation.FRANCE).get(france),
                Nation.GERMANY, fixture.probes().get(Nation.GERMANY).get(germany));
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static Map<Nation, Double> values(double france, double germany) {
        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            result.put(nation, nation == Nation.FRANCE ? france : nation == Nation.GERMANY ? germany : 0.0);
        return result;
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-10, message);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return;
            throw new AssertionError("Expected " + type.getSimpleName(), exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}