package testing.selfcheck;

import _app.CounterSearchApp;
import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import domain.Nation;
import game.Game;
import misc.scoring.ScoringSystem;
import performance.PerformanceStats;
import testing.reference.TwoActionReference;

import java.util.*;

/**
 * Checkpoint snapshots, both policy outputs, and independent matrix calculations.
 */
public final class SearchTraceSelfCheck {

    private static final int BUDGET = 257;
    private static final List<Integer> MARKS = List.of(1, 2, 3, 8, 32, 128, BUDGET);
    private static int checks;

    private SearchTraceSelfCheck() { }

    private record Profile(LambdaDistribution population, double agentLambda,
                           TemperatureSchedule temperature) implements SearchProfile { }

    public static void main(String[] args) {
        for (SearchProfile profile : List.of(PaperSearchProfile.STANDARD, PaperSearchProfile.OPENING,
                new Profile(new LambdaDistribution(List.of(0.1, 0.8), List.of(0.25, 0.75)),
                        0.2, TemperatureSchedule.ADAPTIVE),
                new Profile(LambdaDistribution.uniform(0.03, 0.1), 0.03, TemperatureSchedule.ZERO)))
            for (long seed : new long[]{7, 17, 31})
                compare(profile, seed);
        System.out.println("SearchTraceSelfCheck passed: " + checks + " checks");
    }

    private static void compare(SearchProfile profile, long seed) {
        CounterSearchApp.Fixture fixture = CounterSearchApp.fixture();
        Game game = Game.fromState(fixture.root());
        Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new ScopedActionGenerator(new UniformActionGenerator(), CounterSearchApp.SCOPE),
                Game::fromState, 1, 1, seed);
        JointActionEvaluator trials = JointActionEvaluator.deterministic(game, evaluator, 4, new PerformanceStats(false));
        List<Nation> nations = new ArrayList<>(fixture.root().aliveNations());
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        double[][] priors = new double[2][2];
        for (int player = 0; player < 2; player++) {
            Nation nation = nations.get(player);
            List<ActionCandidate> candidates = new ArrayList<>();
            for (int action = 0; action < 2; action++) {
                ActionCandidate original = fixture.probes().get(nation).get(action);
                double prior = player == 0 ? 0.75 : 0.35;
                priors[player][action] = StrictMath.log(action == 0 ? prior : 1 - prior);
                candidates.add(new ActionCandidate(nation, original.orders(), priors[player][action], original.source()));
            }
            pools.put(nation, List.copyOf(candidates));
        }
        double[][][] payoffs = new double[2][2][2];
        for (int first = 0; first < 2; first++)
            for (int second = 0; second < 2; second++) {
                Map<Nation, Double> values = trials.evaluate(Map.of(nations.get(0), pools.get(nations.get(0)).get(first),
                        nations.get(1), pools.get(nations.get(1)).get(second)));
                for (int player = 0; player < 2; player++)
                    payoffs[player][first][second] = values.get(nations.get(player));
            }
        DiLPiKLSearch solver = profile.solver(evaluator, new UniformActionGenerator(), 2, BUDGET, seed);
        JointSearchResult ordinary = solver.solve(game, pools, trials);
        long requests = trials.counts().requests();
        List<JointSearchResult> traced = solver.trace(game, pools, MARKS, trials);
        require(trials.counts().requests() - requests == 3L * BUDGET,
                "Checkpoints must not evaluate additional joint actions");
        require(traced.size() == MARKS.size(), "Every checkpoint needs a snapshot");
        require(traced.getLast().equals(ordinary), "Tracing must preserve the final result exactly");
        require(solver.trace(game, pools, List.of(BUDGET), trials).getFirst().equals(ordinary),
                "A final-only trace must match solve()");
        List<JointSearchResult> averaged = profile.solver(evaluator, new UniformActionGenerator(),
                2, BUDGET, seed, PolicyOutput.AVERAGE).trace(game, pools, MARKS, trials);
        for (int index = 0; index < MARKS.size(); index++)
            require(averaged.get(index).withOutput(PolicyOutput.FINAL, profile).equals(traced.get(index)),
                    "Output choice must preserve the entire search trajectory");
        expect(UnsupportedOperationException.class, traced::clear);
        List<TwoActionReference.Snapshot> reference =
                TwoActionReference.run(payoffs, priors, profile, BUDGET, seed, MARKS);
        for (int index = 0; index < MARKS.size(); index++) {
            int completed = MARKS.get(index);
            require(reference.get(index).iterations() == completed, "Reference checkpoint count differs");
            for (PolicyOutput output : PolicyOutput.values()) {
                JointSearchResult snapshot = traced.get(index).withOutput(output, profile);
                JointSearchResult prefix = profile.solver(evaluator, new UniformActionGenerator(),
                        2, completed, seed, output).solve(game, pools, trials);
                require(snapshot.equals(prefix), "Checkpoint must equal the same-seed prefix solve");
                for (int player = 0; player < 2; player++) {
                    Nation nation = nations.get(player);
                    require(snapshot.results().get(nation).samplesPerCandidate() == completed,
                            "Snapshot must report its completed iteration count");
                    TwoActionReference.Player expected = reference.get(index).players().get(player);
                    same(snapshot.results().get(nation).meanValues(), expected.values(), "Q");
                    same(snapshot.populationPolicies().get(nation).probabilities(), expected.population(output), "population");
                    for (var entry : snapshot.temperatures().get(nation).entrySet()) {
                        double lambda = entry.getKey();
                        close(entry.getValue(), expected.temperatures().get(lambda), "temperature");
                        same(snapshot.policy(nation, lambda).probabilities(),
                                expected.policies(output).get(lambda), "type policy");
                    }
                    same(snapshot.results().get(nation).distribution().probabilities(),
                            expected.policies(output).get(profile.agentLambda()), "playing policy");
                }
            }
        }
        require(game.snapshot().equals(fixture.root()) && game.getSnapshot() == null
                && game.processorHistory().isEmpty(), "Tracing must preserve the game");
        require(trials.counts().simulations() == 4 && trials.counts().failures() == 0,
                "All comparisons should share four deterministic outcomes");
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(), trials));
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(0, BUDGET), trials));
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(2, 2, BUDGET), trials));
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(3, 2, BUDGET), trials));
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(BUDGET + 1), trials));
        expect(IllegalArgumentException.class, () -> solver.trace(game, pools, List.of(128), trials));
        expect(NullPointerException.class, () -> solver.trace(game, pools, null, trials));
        expect(IllegalArgumentException.class, () -> TwoActionReference.run(payoffs, priors, profile, BUDGET, seed, List.of()));
        expect(IllegalArgumentException.class, () -> TwoActionReference.run(payoffs, priors, profile, BUDGET, seed,
                List.of(2, 1, BUDGET)));
        expect(UnsupportedOperationException.class, () -> reference.getFirst().players().clear());
        expect(UnsupportedOperationException.class, () -> reference.getFirst().players().getFirst().policies().clear());
        expect(UnsupportedOperationException.class,
                () -> reference.getFirst().players().getFirst().averagePolicies().clear());
    }

    private static void same(List<Double> actual, List<Double> expected, String name) {
        require(actual.size() == expected.size(), name + " dimensions differ");
        for (int index = 0; index < actual.size(); index++)
            close(actual.get(index), expected.get(index), name);
    }

    private static void close(double actual, double expected, String name) {
        require(Math.abs(actual - expected) < 1e-9, name + " differs: " + actual + " != " + expected);
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
