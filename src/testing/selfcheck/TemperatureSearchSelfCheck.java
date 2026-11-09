package testing.selfcheck;

import _app.CounterSearchApp;
import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;
import performance.PerformanceStats;

import java.util.*;

/**
 * Full piKL formula, utility statistics, and independent sampled replay.
 */
public final class TemperatureSearchSelfCheck {

    private static final LambdaDistribution POPULATION = new LambdaDistribution(
            List.of(0.1, 0.8, 2.5), List.of(0.25, 0.75, 0.0));
    private static final double AGENT = 0.2;
    private static final long SEED = 54L;
    private static int checks;

    private TemperatureSearchSelfCheck() { }

    private record Fixture(GameState root, Map<Nation, List<ActionCandidate>> pools,
                           UnitId french, UnitId english) { }

    public static void main(String[] args) {
        equations();
        sampledReplay();
        cachingAndCompatibility();
        utilityScale();
        constantAndExtremeUtilities();
        System.out.println("TemperatureSearchSelfCheck passed: " + checks + " checks");
    }

    private static void equations() {
        List<ActionCandidate> candidates = fixture().pools().get(Nation.FRANCE);
        List<Double> values = List.of(0.0, 1.0);
        double expected = 1.0 / (1 + StrictMath.pow(4, 2.0 / 5.0) * StrictMath.exp(-1.0 / 5.0));
        close(DiLPiKLSearch.policy(candidates, values, 2, 3).probabilities().get(1), expected,
                "Kappa must temper the log prior as well as Q");
        require(DiLPiKLSearch.policy(candidates, values, 2).equals(
                        DiLPiKLSearch.policy(candidates, values, 2, 0)),
                "The old helper must retain the kappa=0 response");
        close(DiLPiKLSearch.policy(candidates, List.of(0.0, 0.0), 2, 3).probabilities().get(0),
                StrictMath.pow(4, 0.4) / (1 + StrictMath.pow(4, 0.4)),
                "Nonzero temperature must flatten a nonuniform anchor");

        List<ActionCandidate> supported = new ArrayList<>(candidates);
        supported.add(action(Nation.FRANCE, Double.NEGATIVE_INFINITY, Order.move(fixture().french(), Province.Par)));
        ActionDistribution startup = DiLPiKLSearch.policy(supported,
                List.of(0.0, 0.0, Double.MAX_VALUE), 0.1, Double.POSITIVE_INFINITY);
        require(startup.probabilities().equals(List.of(0.5, 0.5, 0.0)),
                "Uniform startup must retain zero-prior exclusion");
        ActionDistribution tiny = DiLPiKLSearch.policy(List.of(
                        action(Nation.FRANCE, 0, Order.hold(fixture().french())),
                        action(Nation.FRANCE, -1000, Order.move(fixture().french(), Province.Bel))),
                List.of(0.0, 1002.0), 1, 1);
        close(tiny.probabilities().get(1), 1.0 / (1 + StrictMath.exp(-1)),
                "Tiny finite priors must remain recoverable with temperature");

        double maximum = Double.MAX_VALUE;
        ActionDistribution extreme = DiLPiKLSearch.policy(candidates,
                List.of(-maximum, maximum), maximum, maximum);
        close(extreme.probabilities().get(1), 1.0 / (1 + 2 * StrictMath.exp(-1)),
                "Finite lambda and kappa must survive an overflowing denominator");
        close(DiLPiKLSearch.policy(candidates, values, Double.MIN_VALUE, Double.MIN_VALUE)
                .probabilities().get(1), 1, "Tiny denominators must produce a finite normalized policy");
        require(candidates.equals(extreme.candidates()), "Policy construction must preserve stored priors");

        require(TemperatureSchedule.ADAPTIVE.kappa(0, 0) == Double.POSITIVE_INFINITY,
                "Adaptive startup must be explicit");
        close(TemperatureSchedule.ADAPTIVE.kappa(1, 0), 0, "One observation has zero variance");
        close(TemperatureSchedule.ADAPTIVE.kappa(100, 2), 0.06, "Section 2.2 coefficient differs");
        close(TemperatureSchedule.ZERO.kappa(100, 2), 0, "Zero mode must ignore utility variation");
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY})
            expect(IllegalArgumentException.class, () -> DiLPiKLSearch.policy(candidates, values, 1, invalid));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY})
            expect(IllegalArgumentException.class, () -> TemperatureSchedule.ADAPTIVE.kappa(2, invalid));
        expect(IllegalArgumentException.class, () -> TemperatureSchedule.ADAPTIVE.kappa(-1, 0));
        expect(NullPointerException.class, () -> solver(matrix(fixture()), 1, null));
    }

    private static void sampledReplay() {
        Fixture fixture = fixture();
        Game game = game(fixture.root());
        List<List<Integer>> evaluated = new ArrayList<>();
        Evaluator evaluator = state -> {
            require(game.snapshot().equals(fixture.root()) && game.getSnapshot() == null,
                    "Evaluation must see the restored root");
            evaluated.add(profile(state, fixture));
            return matrix(fixture).evaluate(state);
        };
        int iterations = 37;
        JointSearchResult actual = solver(evaluator, iterations, TemperatureSchedule.ADAPTIVE)
                .solve(game, fixture.pools());
        require(evaluated.size() == 3 * iterations, "Temperature observations must not add joint evaluations");

        // Independent replay uses payoff sums and a two-pass population variance.
        Set<Double> lambdas = new LinkedHashSet<>(POPULATION.values());
        lambdas.add(AGENT);
        Map<Nation, double[]> sums = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, List<Double>>> observations = new EnumMap<>(Nation.class);
        for (Nation nation : fixture.root().aliveNations()) {
            sums.put(nation, new double[2]);
            Map<Double, List<Double>> types = new LinkedHashMap<>();
            for (double lambda : lambdas)
                types.put(lambda, new ArrayList<>());
            observations.put(nation, types);
        }
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < iterations; iteration++) {
            Map<Nation, Map<Double, double[]>> policies = new EnumMap<>(Nation.class);
            Map<Nation, Integer> played = new EnumMap<>(Nation.class);
            for (Nation nation : fixture.root().aliveNations()) {
                double[] means = Arrays.stream(sums.get(nation)).map(value -> value
                        / Math.max(1, observations.get(nation).get(AGENT).size())).toArray();
                Map<Double, double[]> types = new LinkedHashMap<>();
                for (double lambda : lambdas)
                    types.put(lambda, referencePolicy(fixture.pools().get(nation), means, lambda,
                            referenceKappa(observations.get(nation).get(lambda))));
                policies.put(nation, types);
                double lambda = random.nextDouble() < 0.25 ? 0.1 : 0.8;
                played.put(nation, random.nextDouble() < types.get(lambda)[0] ? 0 : 1);
            }
            int france = played.get(Nation.FRANCE);
            int england = played.get(Nation.ENGLAND);
            require(evaluated.get(3 * iteration).equals(List.of(france, england)),
                    "Sampling must use Q and temperature from the preceding iteration");
            require(evaluated.subList(3 * iteration, 3 * iteration + 3).containsAll(
                            List.of(List.of(1 - france, england), List.of(france, 1 - england))),
                    "Counterfactual opponents must remain frozen");
            for (Nation nation : fixture.root().aliveNations()) {
                double[] payoffs = nation == Nation.FRANCE
                        ? new double[]{england == 0 ? 1 : 0, england == 1 ? 1 : 0}
                        : new double[]{france == 0 ? 0 : 1, france == 1 ? 0 : 1};
                for (double lambda : lambdas) {
                    double[] probabilities = policies.get(nation).get(lambda);
                    observations.get(nation).get(lambda).add(
                            probabilities[0] * payoffs[0] + probabilities[1] * payoffs[1]);
                }
                for (int action = 0; action < 2; action++)
                    sums.get(nation)[action] += payoffs[action];
            }
        }
        for (Nation nation : fixture.root().aliveNations()) {
            double[] means = Arrays.stream(sums.get(nation)).map(value -> value / iterations).toArray();
            SearchResult result = actual.results().get(nation);
            for (int action = 0; action < 2; action++)
                close(result.meanValues().get(action), means[action], "Q must keep equal-weight raw payoffs");
            require(result.samplesPerCandidate() == iterations, "Observation count differs");
            double[] mixture = new double[2];
            for (double lambda : lambdas) {
                double kappa = referenceKappa(observations.get(nation).get(lambda));
                close(actual.temperature(nation, lambda), kappa, "Per-type final temperature differs");
                double[] policy = referencePolicy(fixture.pools().get(nation), means, lambda, kappa);
                if (lambda == AGENT)
                    for (int action = 0; action < 2; action++)
                        close(result.distribution().probabilities().get(action), policy[action],
                                "Agent policy must use completed Q and its own completed temperature");
                int type = POPULATION.values().indexOf(lambda);
                if (type >= 0)
                    for (int action = 0; action < 2; action++)
                        mixture[action] += POPULATION.probabilities().get(type) * policy[action];
            }
            for (int action = 0; action < 2; action++)
                close(actual.populationPolicies().get(nation).probabilities().get(action), mixture[action],
                        "Population output must mix the completed type policies");
            require(Math.abs(actual.temperature(nation, 0.1) - actual.temperature(nation, 0.8)) > 1e-5,
                    "Different types must have independent utility moments");
        }

        List<List<Integer>> firstRun = List.copyOf(evaluated);
        evaluated.clear();
        JointSearchResult otherAgent = new DiLPiKLSearch(evaluator, 2, iterations, POPULATION, 0.7,
                SEED, TemperatureSchedule.ADAPTIVE).solve(game, fixture.pools());
        require(evaluated.equals(firstRun), "Tracking a different output type must not change population play");
        require(otherAgent.populationPolicies().equals(actual.populationPolicies()),
                "The output lambda must not change final population policies");
        for (Nation nation : fixture.root().aliveNations())
            for (double lambda : POPULATION.values())
                close(otherAgent.temperature(nation, lambda), actual.temperature(nation, lambda),
                        "Output-only moments must not affect population moments");
        expect(UnsupportedOperationException.class, () -> actual.temperatures().clear());
        expect(UnsupportedOperationException.class, () -> actual.temperatures().get(Nation.FRANCE).clear());
        expect(IllegalArgumentException.class, () -> actual.temperature(Nation.FRANCE, 0.3));
        JointSearchResult legacy = new JointSearchResult(actual.results(), actual.populationPolicies());
        expect(IllegalArgumentException.class, () -> legacy.temperature(Nation.FRANCE, AGENT));
    }

    private static void cachingAndCompatibility() {
        Fixture fixture = fixture();
        Game game = game(fixture.root());
        Evaluator evaluator = matrix(fixture);
        DiLPiKLSearch adaptive = solver(evaluator, 37, TemperatureSchedule.ADAPTIVE);
        JointActionEvaluator trials = JointActionEvaluator.deterministic(game, evaluator, 4, new PerformanceStats(false));
        JointSearchResult cached = adaptive.solve(game, fixture.pools(), trials);
        require(cached.equals(adaptive.solve(game, fixture.pools())),
                "Cache hits must still contribute every utility observation");
        require(trials.counts().requests() == 111 && trials.counts().simulations() == 4,
                "The repeated matrix profiles must use the cache");
        JointSearchResult zero = solver(evaluator, 37, TemperatureSchedule.ZERO).solve(game, fixture.pools());
        require(zero.equals(new DiLPiKLSearch(evaluator, 2, 37, POPULATION, AGENT, SEED)
                .solve(game, fixture.pools())), "Old constructors must preserve zero mode");
        for (Map<Double, Double> types : zero.temperatures().values())
            for (double kappa : types.values())
                close(kappa, 0, "Zero mode must report zero temperatures");
        require(!zero.populationPolicies().equals(cached.populationPolicies()),
                "Adaptive temperature must be active in the actual solver");
        require(CounterSearchApp.Options.standard(MovementProcessor.Policy.JUDGE).temperature()
                        == TemperatureSchedule.ADAPTIVE,
                "The updated demonstration must enable adaptive temperature");
        require(new CounterSearchApp.Options(MovementProcessor.Policy.JUDGE, 1, 0, 0).temperature()
                        == TemperatureSchedule.ZERO,
                "Existing explicit-budget calls must retain zero mode");
    }

    private static void utilityScale() {
        Fixture fixture = fixture();
        Evaluator base = matrix(fixture);
        JointSearchResult original = solver(base, 37, TemperatureSchedule.ADAPTIVE)
                .solve(game(fixture.root()), fixture.pools());
        Evaluator shifted = state -> {
            Map<Nation, Double> values = base.evaluate(state);
            return values(4 * values.get(Nation.FRANCE) + 10, 4 * values.get(Nation.ENGLAND) + 10);
        };
        LambdaDistribution population = new LambdaDistribution(
                POPULATION.values().stream().map(lambda -> 4 * lambda).toList(), POPULATION.probabilities());
        JointSearchResult scaled = new DiLPiKLSearch(shifted, 2, 37, population, 4 * AGENT, SEED,
                TemperatureSchedule.ADAPTIVE).solve(game(fixture.root()), fixture.pools());
        for (Nation nation : fixture.root().aliveNations()) {
            for (int action = 0; action < 2; action++) {
                close(scaled.results().get(nation).meanValues().get(action),
                        4 * original.results().get(nation).meanValues().get(action) + 10,
                        "Utility scaling must preserve the sampled opponent history");
                close(scaled.populationPolicies().get(nation).probabilities().get(action),
                        original.populationPolicies().get(nation).probabilities().get(action),
                        "Scaling utilities and lambdas together must preserve policies");
            }
            for (double lambda : POPULATION.values())
                close(scaled.temperature(nation, 4 * lambda), 4 * original.temperature(nation, lambda),
                        "Temperature must scale with raw utility, independent of utility translation");
        }
    }

    private static void constantAndExtremeUtilities() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(unit, Province.Pic), Map.of(Province.Par, Nation.FRANCE)));
        Map<Nation, List<ActionCandidate>> pools = Map.of(Nation.FRANCE,
                List.of(action(Nation.FRANCE, 0, Order.hold(unit))));
        for (double utility : new double[]{0, 7, Double.MAX_VALUE}) {
            JointSearchResult result = solver(state -> values(utility, 0), 8, TemperatureSchedule.ADAPTIVE)
                    .solve(game(root), pools);
            for (double kappa : result.temperatures().get(Nation.FRANCE).values())
                close(kappa, 0, "Constant utilities must have zero temperature");
        }
        int[] calls = {0};
        Evaluator alternating = state -> values((++calls[0] % 2 == 0 ? -1 : 1) * Double.MAX_VALUE, 0);
        JointSearchResult extreme = solver(alternating, 8, TemperatureSchedule.ADAPTIVE).solve(game(root), pools);
        double expected = (3.0 / 10.0) * (Double.MAX_VALUE / StrictMath.sqrt(8));
        for (double kappa : extreme.temperatures().get(Nation.FRANCE).values()) {
            require(Double.isFinite(kappa), "Finite extreme utilities must not overflow variance");
            close(kappa / expected, 1, "Extreme-utility population deviation differs");
        }
        require(calls[0] == 8, "Each iteration must contribute exactly one observed utility vector");
    }

    private static double referenceKappa(List<Double> values) {
        if (values.isEmpty())
            return Double.POSITIVE_INFINITY;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance = values.stream().mapToDouble(value -> (value - mean) * (value - mean))
                .sum() / values.size();
        return 3 * StrictMath.sqrt(variance) / (10 * StrictMath.sqrt(values.size()));
    }

    private static double[] referencePolicy(List<ActionCandidate> candidates, double[] values,
                                            double lambda, double kappa) {
        if (kappa == Double.POSITIVE_INFINITY)
            return new double[]{0.5, 0.5};
        double[] weights = new double[2];
        for (int action = 0; action < 2; action++)
            weights[action] = StrictMath.exp(
                    (values[action] + lambda * candidates.get(action).logPrior()) / (lambda + kappa));
        double sum = weights[0] + weights[1];
        return new double[]{weights[0] / sum, weights[1] / sum};
    }

    private static DiLPiKLSearch solver(Evaluator evaluator, int iterations, TemperatureSchedule temperature) {
        return new DiLPiKLSearch(evaluator, 2, iterations, POPULATION, AGENT, SEED, temperature);
    }

    private static Fixture fixture() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId english = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic, english, Province.ENG),
                        Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND)));
        Map<Nation, List<ActionCandidate>> pools = Map.of(
                Nation.FRANCE, List.of(action(Nation.FRANCE, StrictMath.log(0.8), Order.hold(french)),
                        action(Nation.FRANCE, StrictMath.log(0.2), Order.move(french, Province.Bel))),
                Nation.ENGLAND, List.of(action(Nation.ENGLAND, StrictMath.log(0.4), Order.hold(english)),
                        action(Nation.ENGLAND, StrictMath.log(0.6), Order.move(english, Province.NTH))));
        return new Fixture(root, pools, french, english);
    }

    private static List<Integer> profile(GameState state, Fixture fixture) {
        return List.of(state.board().locationOf(fixture.french()) == Province.Bel ? 1 : 0,
                state.board().locationOf(fixture.english()) == Province.NTH ? 1 : 0);
    }

    private static Evaluator matrix(Fixture fixture) {
        return state -> {
            List<Integer> profile = profile(state, fixture);
            boolean equal = profile.get(0).equals(profile.get(1));
            return values(equal ? 1 : 0, equal ? 0 : 1);
        };
    }

    private static Map<Nation, Double> values(double france, double england) {
        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            result.put(nation, nation == Nation.FRANCE ? france : nation == Nation.ENGLAND ? england : 0.0);
        return result;
    }

    private static ActionCandidate action(Nation nation, double prior, Order... orders) {
        return new ActionCandidate(nation, new ArrayList<OrderForm>(List.of(orders)),
                prior, ActionCandidate.Source.HEURISTIC);
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-10, message + ": " + actual + " != " + expected);
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