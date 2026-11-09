package testing.selfcheck;

import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import performance.PerformanceStats;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Paper profiles, exact and sampled receipts, and unchanged search state.
 */
public final class PaperBaselineSelfCheck {

    private static final SearchProfile PROFILE = new Profile(
            new LambdaDistribution(List.of(0.1, 0.8), List.of(0.25, 0.75)),
            0.2, TemperatureSchedule.ADAPTIVE);
    private static final ActionGenerator EXPLICIT = (request, catalog) -> {
        throw new AssertionError("Explicit pools must not invoke a generator");
    };
    private static int checks;

    private PaperBaselineSelfCheck() { }

    private record Profile(LambdaDistribution population, double agentLambda,
                           TemperatureSchedule temperature) implements SearchProfile { }

    private record Fixture(GameState root, Map<Nation, List<ActionCandidate>> pools,
                           Map<Nation, UnitId> units) { }

    public static void main(String[] args) {
        profiles();
        receipts();
        smallLambda();
        System.out.println("PaperBaselineSelfCheck passed: " + checks + " checks");
    }

    private static void profiles() {
        require(PaperSearchProfile.forState(fixture(1901, GamePhase.SPRING_MOVEMENT).root())
                == PaperSearchProfile.OPENING, "Spring 1901 must use the opening profile");
        require(PaperSearchProfile.forState(fixture(1901, GamePhase.FALL_MOVEMENT).root())
                == PaperSearchProfile.STANDARD, "Fall 1901 must use the standard profile");
        require(PaperSearchProfile.forState(fixture(1902, GamePhase.SPRING_MOVEMENT).root())
                == PaperSearchProfile.STANDARD, "A later first invocation must use the standard profile");
        expect(NullPointerException.class, () -> PaperSearchProfile.forState(null));
        double[][] types = {{0.01, StrictMath.sqrt(0.001), 0.1, StrictMath.sqrt(0.1)},
                {0.0001, 0.001, 0.01, 0.1}};
        for (PaperSearchProfile profile : PaperSearchProfile.values()) {
            for (int type = 0; type < 4; type++) {
                close(profile.population().values().get(type), types[profile.ordinal()][type],
                        "Paper lambda differs");
                close(profile.population().probabilities().get(type), 0.25, "Types must be uniform");
            }
            close(profile.agentLambda(), types[profile.ordinal()][0], "Playing lambda differs");
            require(profile.temperature() == TemperatureSchedule.ADAPTIVE, "Paper temperature must be adaptive");
            Fixture fixture = fixture(profile == PaperSearchProfile.OPENING ? 1901 : 1907,
                    GamePhase.SPRING_MOVEMENT);
            Game game = game(fixture.root());
            Evaluator evaluator = matrix(fixture);
            JointSearchResult actual = profile.solver(evaluator, EXPLICIT, 2, 37, 7L).solve(game, fixture.pools());
            JointSearchResult expected = new DiLPiKLSearch(evaluator, EXPLICIT, 2, 37,
                    profile.population(), profile.agentLambda(), 7L, profile.temperature())
                    .solve(game, fixture.pools());
            require(actual.equals(expected), "Profile factory must preserve solver settings");
            List<TypeReceipt> receipts = TypeReceipt.exact(profile, actual,
                    JointActionEvaluator.uncached(game, evaluator));
            require(receipts.size() == 12, "Each nation must have four type receipts");
            require(receipts.stream().filter(TypeReceipt::playing).count() == 3,
                    "Each nation must have one playing type");
        }
    }

    private static void receipts() {
        Fixture fixture = fixture(1907, GamePhase.FALL_MOVEMENT);
        Game game = game(fixture.root());
        Evaluator evaluator = matrix(fixture);
        JointActionEvaluator trials = JointActionEvaluator.deterministic(
                game, evaluator, 8, new PerformanceStats(false));
        DiLPiKLSearch solver = PROFILE.solver(evaluator, EXPLICIT, 2, 71, 31L);
        JointSearchResult solved = solver.solve(game, fixture.pools(), trials);
        long requests = trials.counts().requests();
        List<TypeReceipt> exact = TypeReceipt.exact(PROFILE, solved, trials);
        require(trials.counts().requests() - requests == 8, "Exact checks must enumerate each joint action once");
        require(exact.size() == 9, "An output-only lambda needs its own receipt");
        for (TypeReceipt receipt : exact) {
            List<Double> fresh = directValues(receipt.power(), solved);
            for (int action = 0; action < 2; action++) {
                close(receipt.freshValues().get(action), fresh.get(action), "Exact opponent average differs");
                close(receipt.standardErrors().get(action), 0, "Exact values have no sampling error");
            }
            checkMeasures(receipt, solved, fixture.pools().get(receipt.power()));
            close(receipt.populationWeight(), receipt.lambda() == 0.1 ? 0.25 : receipt.lambda() == 0.8 ? 0.75 : 0,
                    "Population weight differs");
            require(receipt.playing() == (receipt.lambda() == 0.2), "Playing flag differs");
            require(receipt.opponentSamples() == 0, "Exact checks must be labelled");
            List<Double> numbers = receipt.values();
            require(numbers.size() == 15, "Numeric receipt must have nine header values and three per action");
            List<Double> header = List.of(receipt.lambda(), receipt.populationWeight(), receipt.playing() ? 1.0 : 0.0,
                    receipt.temperature(), 0.0, receipt.expectedScore(), receipt.responseResidual(),
                    receipt.regularizedGain(), receipt.temperatureResidual());
            require(numbers.subList(0, 9).equals(header), "Numeric header order differs");
            for (int action = 0; action < 2; action++) {
                close(numbers.get(9 + 3 * action), receipt.probabilities().get(action), "Numeric probability differs");
                close(numbers.get(10 + 3 * action), fresh.get(action), "Numeric fresh value differs");
                close(numbers.get(11 + 3 * action), 0, "Numeric standard error differs");
            }
            expect(UnsupportedOperationException.class, () -> numbers.add(1.0));
            expect(UnsupportedOperationException.class, () -> receipt.probabilities().clear());
        }
        expect(UnsupportedOperationException.class, () -> exact.clear());
        int samples = 2_000;
        requests = trials.counts().requests();
        List<TypeReceipt> sampled = TypeReceipt.sampled(PROFILE, solved, trials, samples, 17L);
        require(trials.counts().requests() - requests == 4L * samples,
                "Sampled checks must reuse the played joint payoff");
        require(sampled.equals(TypeReceipt.sampled(PROFILE, solved, trials, samples, 17L)),
                "A repeated diagnostic seed must reproduce every receipt");
        checkSamples(sampled, solved, samples, 17L);
        require(solved.equals(solver.solve(game, fixture.pools(), trials)),
                "Diagnostics must not change later seeded search results");
        require(game.snapshot().equals(fixture.root()) && game.getSnapshot() == null
                && game.processorHistory().isEmpty(), "Search and receipts must restore the game");
        require(trials.counts().simulations() == 8 && trials.counts().failures() == 0,
                "The deterministic fixture should use only eight simulations");

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            String text = exact.getFirst().toString();
            require(text.contains("DiL-piKL TYPE RECEIPT") && text.contains("----------------"),
                    "Receipt layout is missing");
            require(text.contains("Population weight: 0.250000") && text.contains("exact opponent enumeration"),
                    "Receipt numbers and exact label must be locale-independent");
            require(sampled.getFirst().toString().contains("2000 opponent samples"),
                    "Estimated receipts must print their sample count");
        } finally {
            Locale.setDefault(previous);
        }
        expect(IllegalArgumentException.class, () -> TypeReceipt.sampled(PROFILE, solved, trials, 1, 7L));
        SearchProfile wrong = new Profile(
                new LambdaDistribution(List.of(0.1, 0.8), List.of(0.75, 0.25)), 0.2, TemperatureSchedule.ADAPTIVE);
        expect(IllegalArgumentException.class, () -> TypeReceipt.exact(wrong, solved, trials));
        JointSearchResult missing = new JointSearchResult(solved.results(), solved.populationPolicies());
        expect(IllegalArgumentException.class, () -> TypeReceipt.exact(PROFILE, missing, trials));
        expect(IllegalArgumentException.class, () -> new TypeReceipt(Nation.FRANCE, 0.1, 1, true,
                0, 0, List.of(0.5), List.of(1.0), List.of(0.0), 0, 0, 0));
        expect(IllegalArgumentException.class, () -> new TypeReceipt(Nation.FRANCE, 0.1, 1, true,
                0, 0, List.of(1.0), List.of(1.0), List.of(0.1), 0, 0, 0));
    }

    private static void checkMeasures(TypeReceipt receipt, JointSearchResult solved, List<ActionCandidate> pool) {
        List<Double> fresh = receipt.freshValues();
        List<Double> response = directPolicy(pool, fresh, receipt.lambda(), 0);
        List<Double> tempered = directPolicy(pool, fresh, receipt.lambda(), receipt.temperature());
        List<Double> current = directPolicy(pool, solved.results().get(receipt.power()).meanValues(),
                receipt.lambda(), receipt.temperature());
        double residual = 0, temperatureResidual = 0, gain = 0, score = 0;
        for (int action = 0; action < pool.size(); action++) {
            double p = current.get(action), b = response.get(action);
            close(receipt.probabilities().get(action), p, "Type policy differs");
            residual = Math.max(residual, Math.abs(p - b));
            temperatureResidual = Math.max(temperatureResidual, Math.abs(p - tempered.get(action)));
            gain += (b - p) * fresh.get(action) - receipt.lambda()
                    * (b * (StrictMath.log(b) - pool.get(action).logPrior())
                    - p * (StrictMath.log(p) - pool.get(action).logPrior()));
            score += p * fresh.get(action);
        }
        close(receipt.expectedScore(), score, "Expected score differs");
        close(receipt.responseResidual(), residual, "Regularized response difference differs");
        close(receipt.temperatureResidual(), temperatureResidual, "Finite-temperature difference differs");
        close(receipt.regularizedGain(), gain, "Regularized improvement differs");
    }

    private static void checkSamples(List<TypeReceipt> receipts, JointSearchResult solved, int samples, long seed) {
        List<Nation> nations = new ArrayList<>(solved.populationPolicies().keySet());
        Map<Nation, double[]> sums = new EnumMap<>(Nation.class);
        Map<Nation, double[]> squares = new EnumMap<>(Nation.class);
        nations.forEach(nation -> {
            sums.put(nation, new double[2]);
            squares.put(nation, new double[2]);
        });
        Random random = new Random(seed);
        for (int sample = 0; sample < samples; sample++) {
            Map<Nation, Integer> played = new EnumMap<>(Nation.class);
            for (Nation nation : nations)
                played.put(nation, random.nextDouble()
                        < solved.populationPolicies().get(nation).probabilities().getFirst() ? 0 : 1);
            for (Nation nation : nations)
                for (int action = 0; action < 2; action++) {
                    Map<Nation, Integer> joint = new EnumMap<>(played);
                    joint.put(nation, action);
                    double score = payoff(nation, joint);
                    sums.get(nation)[action] += score;
                    squares.get(nation)[action] += score * score;
                }
        }
        for (TypeReceipt receipt : receipts) {
            require(receipt.opponentSamples() == samples, "Receipt sample count differs");
            for (int action = 0; action < 2; action++) {
                double mean = sums.get(receipt.power())[action] / samples;
                double variance = (squares.get(receipt.power())[action] - samples * mean * mean) / (samples - 1);
                close(receipt.freshValues().get(action), mean, "Sampled mean differs from independent replay");
                close(receipt.standardErrors().get(action), StrictMath.sqrt(Math.max(0, variance) / samples),
                        "Standard error differs from independent replay");
            }
        }
    }

    private static void smallLambda() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(unit, Province.Pic), Map.of(Province.Par, Nation.FRANCE)));
        List<ActionCandidate> pool = List.of(
                action(Nation.FRANCE, StrictMath.log(0.5), Order.hold(unit)),
                action(Nation.FRANCE, StrictMath.log(0.5), Order.move(unit, Province.Bel)),
                action(Nation.FRANCE, Double.NEGATIVE_INFINITY, Order.move(unit, Province.Par)));
        SearchProfile profile = new Profile(LambdaDistribution.fixed(1e-4), 1e-4, TemperatureSchedule.ZERO);
        ActionDistribution policy = DiLPiKLSearch.policy(pool, List.of(0.0, 0.0, 0.0), 1e-4);
        JointSearchResult solved = new JointSearchResult(
                Map.of(Nation.FRANCE, new SearchResult(policy, List.of(0.0, 0.0, 0.0), 1)),
                Map.of(Nation.FRANCE, policy), Map.of(Nation.FRANCE, Map.of(1e-4, 0.0)));
        Evaluator evaluator = state -> {
            Map<Nation, Double> scores = zeros();
            Province location = state.board().locationOf(unit);
            scores.put(Nation.FRANCE, location == Province.Bel ? 1.0 : location == Province.Par ? 100.0 : 0.0);
            return scores;
        };
        JointActionEvaluator trials = JointActionEvaluator.uncached(game(root), evaluator);
        TypeReceipt receipt = TypeReceipt.exact(profile, solved, trials).getFirst();
        close(receipt.responseResidual(), 0.5, "Underflowed response must remain measurable");
        close(receipt.regularizedGain(), 0.5 - 1e-4 * StrictMath.log(2), "Small-lambda gain must stay finite");
        close(receipt.probabilities().get(2), 0, "Zero-prior action must stay excluded");
        close(receipt.freshValues().get(2), 100, "Excluded actions retain their measured values");
        close(receipt.expectedScore(), 0.5, "Excluded action must not affect the current payoff");
        TypeReceipt sampled = TypeReceipt.sampled(profile, solved, trials, 2, 7L).getFirst();
        require(sampled.freshValues().equals(receipt.freshValues()), "Single-player values need no opponent draws");
        require(sampled.standardErrors().equals(List.of(0.0, 0.0, 0.0)), "Constant values have zero standard error");
    }

    private static List<Double> directValues(Nation nation, JointSearchResult solved) {
        List<Double> values = new ArrayList<>();
        for (int action = 0; action < 2; action++) {
            double value = 0;
            for (int bits = 0; bits < 8; bits++) {
                Map<Nation, Integer> joint = new EnumMap<>(Nation.class);
                joint.put(Nation.FRANCE, bits & 1);
                joint.put(Nation.ENGLAND, (bits >> 1) & 1);
                joint.put(Nation.ITALY, (bits >> 2) & 1);
                if (joint.get(nation) != action)
                    continue;
                double weight = 1;
                for (Nation opponent : joint.keySet())
                    if (opponent != nation)
                        weight *= solved.populationPolicies().get(opponent).probabilities().get(joint.get(opponent));
                value += weight * payoff(nation, joint);
            }
            values.add(value);
        }
        return values;
    }

    private static List<Double> directPolicy(List<ActionCandidate> pool, List<Double> values,
                                             double lambda, double kappa) {
        List<Double> weights = new ArrayList<>();
        for (int action = 0; action < pool.size(); action++)
            weights.add(StrictMath.exp((values.get(action) + lambda * pool.get(action).logPrior()) / (lambda + kappa)));
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        return weights.stream().map(weight -> weight / total).toList();
    }

    private static Fixture fixture(int year, GamePhase phase) {
        Map<Nation, UnitId> units = new EnumMap<>(Nation.class);
        units.put(Nation.FRANCE, new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Pic));
        units.put(Nation.ENGLAND, new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Lon));
        units.put(Nation.ITALY, new UnitId(Nation.ITALY, UnitType.ARMY, Province.Ven));
        Map<UnitId, Province> locations = Map.of(units.get(Nation.FRANCE), Province.Pic,
                units.get(Nation.ENGLAND), Province.Lon, units.get(Nation.ITALY), Province.Ven);
        GameState root = new GameState_Movement(new GameMoment(year, phase),
                new BoardState(locations, Map.of(Province.Par, Nation.FRANCE,
                        Province.Lon, Nation.ENGLAND, Province.Ven, Nation.ITALY)));
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        for (Nation nation : units.keySet()) {
            double prior = nation == Nation.FRANCE ? 0.8 : nation == Nation.ENGLAND ? 0.4 : 0.6;
            Province target = nation == Nation.FRANCE ? Province.Bel : nation == Nation.ENGLAND ? Province.NTH : Province.Tyr;
            pools.put(nation, List.of(action(nation, StrictMath.log(prior), Order.hold(units.get(nation))),
                    action(nation, StrictMath.log(1 - prior), Order.move(units.get(nation), target))));
        }
        return new Fixture(root, pools, units);
    }

    private static Evaluator matrix(Fixture fixture) {
        return state -> {
            Map<Nation, Integer> played = new EnumMap<>(Nation.class);
            played.put(Nation.FRANCE, state.board().locationOf(fixture.units().get(Nation.FRANCE)) == Province.Bel ? 1 : 0);
            played.put(Nation.ENGLAND, state.board().locationOf(fixture.units().get(Nation.ENGLAND)) == Province.NTH ? 1 : 0);
            played.put(Nation.ITALY, state.board().locationOf(fixture.units().get(Nation.ITALY)) == Province.Tyr ? 1 : 0);
            Map<Nation, Double> scores = zeros();
            played.keySet().forEach(nation -> scores.put(nation, payoff(nation, played)));
            return scores;
        };
    }

    private static double payoff(Nation nation, Map<Nation, Integer> actions) {
        boolean equal = actions.get(Nation.FRANCE).equals(actions.get(Nation.ENGLAND));
        return switch (nation) {
            case FRANCE -> (equal ? 0.8 : 0.2) + (actions.get(Nation.ITALY) == 1 ? 0.05 : -0.05);
            case ENGLAND -> equal ? 0.2 : 0.8;
            case ITALY -> actions.get(Nation.ITALY) == (equal ? 0 : 1) ? 0.7 : 0.3;
            default -> 0;
        };
    }

    private static Map<Nation, Double> zeros() {
        Map<Nation, Double> scores = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            scores.put(nation, 0.0);
        return scores;
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