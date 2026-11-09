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
import java.util.stream.IntStream;

/**
 * Policy averaging boundaries, seven-nation mixtures, and selected-output receipts.
 */
public final class PolicyOutputSelfCheck {

    private static int checks;

    private PolicyOutputSelfCheck() { }

    private record Profile(LambdaDistribution population, double agentLambda,
                           TemperatureSchedule temperature) implements SearchProfile { }

    public static void main(String[] args) {
        for (TemperatureSchedule temperature : TemperatureSchedule.values())
            check(temperature);
        System.out.println("PolicyOutputSelfCheck passed: " + checks + " checks");
    }

    private static void check(TemperatureSchedule temperature) {
        Map<Nation, Province> origins = Map.of(Nation.AUSTRIA, Province.Vie, Nation.ENGLAND, Province.Lvp,
                Nation.FRANCE, Province.Par, Nation.GERMANY, Province.Ber, Nation.ITALY, Province.Rom,
                Nation.RUSSIA, Province.Mos, Nation.TURKEY, Province.Con);
        Map<Nation, Province> targets = Map.of(Nation.AUSTRIA, Province.Boh, Nation.ENGLAND, Province.Wal,
                Nation.FRANCE, Province.Pic, Nation.GERMANY, Province.Pru, Nation.ITALY, Province.Tus,
                Nation.RUSSIA, Province.Ukr, Nation.TURKEY, Province.Bul);
        Map<Nation, UnitId> units = new EnumMap<>(Nation.class);
        Map<UnitId, Province> locations = new HashMap<>();
        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values()) {
            UnitId unit = new UnitId(nation, UnitType.ARMY, origins.get(nation));
            units.put(nation, unit);
            locations.put(unit, origins.get(nation));
            centers.put(origins.get(nation), nation);
            double prior = nation == Nation.RUSSIA ? 1 : 0.25 + 0.05 * nation.ordinal();
            List<ActionCandidate> candidates = List.of(action(nation, StrictMath.log(prior), Order.hold(unit)),
                    action(nation, StrictMath.log(1 - prior), Order.move(unit, targets.get(nation))));
            pools.put(nation, nation == Nation.GERMANY ? List.of(candidates.getFirst()) : candidates);
        }
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, centers));
        Game game = Game.fromState(root, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
        Evaluator evaluator = state -> {
            Map<Nation, Integer> moved = new EnumMap<>(Nation.class);
            for (Nation nation : Nation.values())
                moved.put(nation, state.board().locationOf(units.get(nation)) == targets.get(nation) ? 1 : 0);
            int total = moved.values().stream().mapToInt(Integer::intValue).sum();
            Map<Nation, Double> scores = new EnumMap<>(Nation.class);
            for (Nation nation : Nation.values())
                scores.put(nation, 0.1 + 0.6 * moved.get(nation) + 0.02 * (total - moved.get(nation)));
            return scores;
        };
        SearchProfile profile = new Profile(new LambdaDistribution(
                List.of(0.1, 0.8, 0.4), List.of(0.25, 0.75, 0.0)), 0.2, temperature);
        ActionGenerator generator = (request, catalog) -> pools.get(request.power());
        JointActionEvaluator trials = JointActionEvaluator.deterministic(game, evaluator, 64, new PerformanceStats(false));
        DiLPiKLSearch solver = profile.solver(evaluator, generator, 2, 9, 17);
        List<JointSearchResult> history = solver.trace(game, pools, IntStream.rangeClosed(1, 9).boxed().toList(), trials);
        require(trials.counts().requests() == 7 * 9, "Output tracking must not add evaluation requests");
        long requests = trials.counts().requests();
        List<JointSearchResult> averages = history.stream()
                .map(result -> result.withOutput(PolicyOutput.AVERAGE, profile)).toList();
        require(trials.counts().requests() == requests, "Output selection must not evaluate actions");

        for (Nation nation : Nation.values()) {
            List<ActionCandidate> candidates = pools.get(nation);
            long supported = candidates.stream().filter(candidate -> Double.isFinite(candidate.logPrior())).count();
            List<Double> startup = temperature == TemperatureSchedule.ZERO
                    ? ActionDistribution.fromPriors(candidates).probabilities()
                    : candidates.stream().map(candidate -> Double.isFinite(candidate.logPrior()) ? 1.0 / supported : 0.0).toList();
            for (double lambda : history.getFirst().temperatures().get(nation).keySet()) {
                double[] sums = startup.stream().mapToDouble(Double::doubleValue).toArray();
                for (int step = 0; step < history.size(); step++) {
                    List<Double> average = averages.get(step).policy(nation, lambda).probabilities();
                    List<Double> next = history.get(step).policy(nation, lambda).probabilities();
                    for (int action = 0; action < sums.length; action++) {
                        close(average.get(action), sums[action] / (step + 1), "Average must include exactly the used policies");
                        sums[action] += next.get(action);
                    }
                }
            }
        }
        JointSearchResult last = history.getLast(), average = averages.getLast();
        require(last.output() == PolicyOutput.FINAL && average.output() == PolicyOutput.AVERAGE, "Output labels differ");
        require(average.withOutput(PolicyOutput.FINAL, profile).equals(last), "Output selection must be reversible");
        require(profile.solver(evaluator, generator, 2, 9, 17, PolicyOutput.AVERAGE)
                .solve(game, pools, trials).equals(average), "Configured output must match snapshot selection");
        SearchResult finalSearch = solver.search(game, Nation.FRANCE, trials);
        SearchResult averageSearch = profile.solver(evaluator, generator, 2, 9, 17, PolicyOutput.AVERAGE)
                .search(game, Nation.FRANCE, trials);
        require(finalSearch.meanValues().equals(averageSearch.meanValues()), "search() must keep the same Q updates");
        require(!finalSearch.distribution().equals(averageSearch.distribution()), "search() must honor its output choice");
        for (Nation nation : Nation.values()) {
            List<Double> mixture = average.populationPolicies().get(nation).probabilities();
            for (int action = 0; action < mixture.size(); action++)
                close(mixture.get(action), 0.25 * average.policy(nation, 0.1).probabilities().get(action)
                        + 0.75 * average.policy(nation, 0.8).probabilities().get(action), "Population weights differ");
            require(average.results().get(nation).distribution().equals(average.policy(nation, 0.2)),
                    "The output-only playing type must retain its own average");
            close(average.policy(Nation.RUSSIA, 0.4).probabilities().get(1), 0, "Zero-prior action gained mass");
        }

        requests = trials.counts().requests();
        List<TypeReceipt> receipts = TypeReceipt.exact(profile, average, trials);
        require(trials.counts().requests() - requests == 64, "Exact checks must enumerate each joint action once");
        require(receipts.size() == 28, "Every nation needs population and output-only type receipts");
        for (TypeReceipt receipt : receipts) {
            require(receipt.output() == PolicyOutput.AVERAGE, "Receipt output differs");
            require(receipt.probabilities().equals(average.policy(receipt.power(), receipt.lambda()).probabilities()),
                    "Receipt must use the recorded average");
            double otherMoves = 0;
            for (Nation opponent : Nation.values())
                if (opponent != receipt.power()) {
                    List<Double> probabilities = average.populationPolicies().get(opponent).probabilities();
                    otherMoves += probabilities.size() == 1 ? 0 : probabilities.get(1);
                }
            for (int action = 0; action < receipt.freshValues().size(); action++)
                close(receipt.freshValues().get(action), 0.1 + 0.6 * action + 0.02 * otherMoves,
                        "Fresh values must use the average opposing population");
            double gain = 0;
            List<ActionCandidate> candidates = pools.get(receipt.power());
            List<Double> response = DiLPiKLSearch.policy(candidates, receipt.freshValues(), receipt.lambda()).probabilities();
            for (int action = 0; action < candidates.size(); action++) {
                double prior = candidates.get(action).logPrior();
                if (!Double.isFinite(prior))
                    continue;
                double p = receipt.probabilities().get(action), b = response.get(action);
                gain += (b - p) * receipt.freshValues().get(action)
                        - receipt.lambda() * (b * (StrictMath.log(b) - prior) - p * (StrictMath.log(p) - prior));
            }
            close(receipt.regularizedGain(), gain, "Average-policy regularized improvement differs");
            require(receipt.values().size() == 9 + 3 * candidates.size(), "Numeric receipt layout changed");
            require(receipt.toString().contains("Policy: AVERAGE; opponents: AVERAGE population")
                    && receipt.toString().contains("Final temperature:"), "Receipt labels are missing");
        }
        requests = trials.counts().requests();
        List<TypeReceipt> sampled = TypeReceipt.sampled(profile, average, trials, 500, 31);
        require(trials.counts().requests() - requests == 7 * 500, "Sampled evaluation work differs");
        require(sampled.equals(TypeReceipt.sampled(profile, average, trials, 500, 31)), "Sampled receipts must replay");
        checkSamples(sampled, average);
        require(game.snapshot().equals(root) && game.getSnapshot() == null && game.processorHistory().isEmpty(),
                "Output selection and receipts must preserve the game");
        require(trials.counts().failures() == 0, "Evaluation failed");

        expect(UnsupportedOperationException.class, () -> average.averagePolicies().clear());
        expect(UnsupportedOperationException.class, () -> average.averagePolicies().get(Nation.FRANCE).clear());
        expect(UnsupportedOperationException.class, () -> average.policy(Nation.FRANCE, 0.2).probabilities().clear());
        Map<Nation, Map<Double, ActionDistribution>> mutable = new EnumMap<>(average.averagePolicies());
        Map<Double, ActionDistribution> types = new LinkedHashMap<>(mutable.get(Nation.FRANCE));
        mutable.put(Nation.FRANCE, types);
        JointSearchResult copied = new JointSearchResult(average.results(), average.populationPolicies(),
                average.temperatures(), mutable, PolicyOutput.AVERAGE);
        types.clear();
        mutable.clear();
        require(copied.equals(average), "Average maps must be copied deeply");
        JointSearchResult legacy = new JointSearchResult(last.results(), last.populationPolicies(), last.temperatures());
        require(legacy.withOutput(PolicyOutput.FINAL, profile).equals(legacy), "Legacy final results must remain usable");
        expect(IllegalArgumentException.class, () -> legacy.withOutput(PolicyOutput.AVERAGE, profile));
        expect(IllegalArgumentException.class, () -> new JointSearchResult(last.results(),
                last.populationPolicies(), last.temperatures(), Map.of(), PolicyOutput.AVERAGE));
        expect(IllegalArgumentException.class, () -> average.policy(Nation.FRANCE, 0.9));
        expect(NullPointerException.class, () -> profile.solver(evaluator, generator, 2, 9, 17, null));
        SearchProfile wrong = new Profile(new LambdaDistribution(List.of(0.1, 0.8, 0.4), List.of(0.75, 0.25, 0.0)),
                0.2, temperature);
        expect(IllegalArgumentException.class, () -> TypeReceipt.exact(wrong, average, trials));
    }

    private static void checkSamples(List<TypeReceipt> receipts, JointSearchResult solved) {
        double[][] sums = new double[7][2], squares = new double[7][2];
        Random random = new Random(31);
        for (int sample = 0; sample < 500; sample++) {
            int[] moved = new int[7];
            int total = 0;
            for (Nation nation : Nation.values()) {
                moved[nation.ordinal()] = random.nextDouble()
                        < solved.populationPolicies().get(nation).probabilities().getFirst() ? 0 : 1;
                total += moved[nation.ordinal()];
            }
            for (Nation nation : Nation.values())
                for (int action = 0; action < 2; action++) {
                    double score = 0.1 + 0.6 * action + 0.02 * (total - moved[nation.ordinal()]);
                    sums[nation.ordinal()][action] += score;
                    squares[nation.ordinal()][action] += score * score;
                }
        }
        for (TypeReceipt receipt : receipts) {
            require(receipt.output() == PolicyOutput.AVERAGE, "Sampled output label differs");
            for (int action = 0; action < receipt.freshValues().size(); action++) {
                double mean = sums[receipt.power().ordinal()][action] / 500;
                double variance = (squares[receipt.power().ordinal()][action] - 500 * mean * mean) / 499;
                close(receipt.freshValues().get(action), mean, "Sampled average opponents differ");
                close(receipt.standardErrors().get(action), StrictMath.sqrt(Math.max(0, variance) / 500),
                        "Sampled standard error differs");
            }
        }
    }

    private static ActionCandidate action(Nation nation, double prior, Order order) {
        return new ActionCandidate(nation, List.<OrderForm>of(order), prior, ActionCandidate.Source.HEURISTIC);
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