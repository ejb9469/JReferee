package _app;

import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.Nation;
import game.Game;
import misc.scoring.ScoringSystem;
import performance.PerformanceStats;
import phase.Order;
import testing.reference.TwoActionReference;

import java.util.*;

/**
 * Compares final and averaged policies with an independent two-action reference.
 * Defaults to seeds 7, 17, and 31. Optional arguments select one seed and budget.
 */
public final class PaperSearchConsoleApp {

    private static final int[] MARKS = {128, 512, 2_048, 8_192, 32_768, 131_072};
    private static final double REFERENCE_TOLERANCE = 1e-8;

    private PaperSearchConsoleApp() { }

    private record Summary(long seed, int iterations, PolicyOutput output, double probabilityError,
                           double regularizedGain, double referenceError) { }

    public static void main(String[] args) {
        if (args.length > 2)
            throw new IllegalArgumentException("Usage: PaperSearchConsoleApp [seed] [iterations]");
        long[] seeds = args.length > 0 ? new long[]{Long.parseLong(args[0])} : new long[]{7, 17, 31};
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : MARKS[MARKS.length - 1];
        if (iterations < 1)
            throw new IllegalArgumentException("Iteration budget must be positive");
        List<Integer> checkpoints = new ArrayList<>();
        for (int mark : MARKS)
            if (mark < iterations)
                checkpoints.add(mark);
        checkpoints.add(iterations);
        CounterSearchApp.Fixture fixture = CounterSearchApp.fixture();
        PaperSearchProfile profile = PaperSearchProfile.forState(fixture.root());
        System.out.println("BEGIN PAPER CHECKPOINT COMPARISON");
        System.out.println("Profile: " + profile + "; seeds: " + Arrays.toString(seeds));
        System.out.println("Checkpoints: " + checkpoints);
        System.out.println("Population lambdas: " + profile.population().values());
        System.out.println("Playing lambda: " + profile.agentLambda() + "; temperature: " + profile.temperature());
        System.out.println("One continuous solve per seed. Checkpoints do not restart search.");
        System.out.println("FINAL = policy after the update; AVERAGE = mean policies used during the run.");
        System.out.println("Q = past payoff average; fresh = score against the selected opposing population.");
        System.out.println("Temperatures are final recorded values in both output modes.");
        System.out.println("Reference tolerance: " + REFERENCE_TOLERANCE + "; this is not a convergence threshold.");
        System.out.println("Fixture: uniform anchor, six-province scope, winter Sum-of-Squares.");
        System.out.println("Continuations omit retreats and stop before winter adjustments.");
        for (Nation nation : fixture.root().aliveNations()) {
            List<ActionCandidate> candidates = fixture.probes().get(nation);
            for (int action = 0; action < candidates.size(); action++) {
                System.out.println(nation + " action " + action);
                for (OrderForm form : candidates.get(action).orders()) {
                    if (!(form instanceof Order order))
                        throw new IllegalStateException("Unexpected non-movement fixture order");
                    System.out.println("    " + OrderForm.format(order, fixture.root().board().locationOf(order.unit())));
                }
            }
        }
        List<Summary> summaries = new ArrayList<>();
        for (long seed : seeds)
            summaries.addAll(run(fixture, profile, seed, iterations, checkpoints));
        System.out.println("\nSUMMARY");
        System.out.println("seed iterations output distance_from_50_50 remaining_improvement reference_error");
        for (Summary summary : summaries)
            System.out.printf(Locale.ROOT, "%d %d %s %.10f %.10f %.3g%n", summary.seed(),
                    summary.iterations(), summary.output(), summary.probabilityError(),
                    summary.regularizedGain(), summary.referenceError());
        System.out.println("Distance and improvement are maxima across all nations and types.");
        System.out.println("Reference agreement checks the implementation, not full-game strength or convergence.");
        System.out.println("END PAPER CHECKPOINT COMPARISON");
    }

    private static List<Summary> run(CounterSearchApp.Fixture fixture, SearchProfile profile,
                                     long seed, int iterations, List<Integer> checkpoints) {
        Game game = Game.fromState(fixture.root());
        Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new ScopedActionGenerator(new UniformActionGenerator(), CounterSearchApp.SCOPE),
                Game::fromState, 1, 1, seed);
        DiLPiKLSearch solver = profile.solver(evaluator, new UniformActionGenerator(),
                2, iterations, seed, PolicyOutput.AVERAGE);
        JointActionEvaluator trials = JointActionEvaluator.deterministic(
                game, evaluator, 4, new PerformanceStats(false));
        List<Nation> nations = new ArrayList<>(fixture.root().aliveNations());
        if (nations.size() != 2 || fixture.probes().values().stream().anyMatch(pool -> pool.size() != 2))
            throw new IllegalStateException("The reference fixture needs two nations with two actions each");
        double[][][] payoffs = new double[2][2][2];
        double[][] priors = new double[2][2];
        for (int first = 0; first < 2; first++)
            for (int second = 0; second < 2; second++) {
                Map<Nation, Double> scores = trials.evaluate(Map.of(
                        nations.get(0), fixture.probes().get(nations.get(0)).get(first),
                        nations.get(1), fixture.probes().get(nations.get(1)).get(second)));
                for (int player = 0; player < 2; player++)
                    payoffs[player][first][second] = scores.get(nations.get(player));
            }
        for (int player = 0; player < 2; player++)
            for (int action = 0; action < 2; action++)
                priors[player][action] = fixture.probes().get(nations.get(player)).get(action).logPrior();
        requireBalanced(payoffs, priors);
        System.out.printf(Locale.ROOT, "%nSEED %d; first nation's payoff matrix: %s%n",
                seed, Arrays.deepToString(payoffs[0]));
        List<JointSearchResult> history = solver.trace(game, fixture.probes(), checkpoints, trials);
        List<TwoActionReference.Snapshot> reference =
                TwoActionReference.run(payoffs, priors, profile, iterations, seed, checkpoints);
        List<Summary> summaries = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            TwoActionReference.Snapshot expected = reference.get(index);
            System.out.printf(Locale.ROOT, "%nCHECKPOINT seed=%d iteration=%d%n", seed, checkpoints.get(index));
            for (PolicyOutput output : PolicyOutput.values()) {
                JointSearchResult solved = history.get(index).withOutput(output);
                List<TypeReceipt> receipts = TypeReceipt.exact(solved, trials);
                double referenceError = 0, probabilityError = 0, gain = 0;
                System.out.println("OUTPUT " + output);
                for (int player = 0; player < nations.size(); player++) {
                    Nation nation = nations.get(player);
                    referenceError = Math.max(referenceError, difference(solved.results().get(nation).meanValues(),
                            expected.players().get(player).values()));
                    referenceError = Math.max(referenceError, difference(solved.populationPolicies().get(nation).probabilities(),
                            expected.players().get(player).population(output)));
                    System.out.println(nation + " Q=" + solved.results().get(nation).meanValues()
                            + " population=" + solved.populationPolicies().get(nation).probabilities());
                }
                System.out.println("nation lambda final_temperature p0 p1 fresh0 fresh1 response_difference remaining_improvement");
                for (TypeReceipt receipt : receipts) {
                    TwoActionReference.Player player = expected.players().get(nations.indexOf(receipt.power()));
                    referenceError = Math.max(referenceError, difference(receipt.probabilities(),
                            player.policies(output).get(receipt.lambda())));
                    referenceError = Math.max(referenceError,
                            Math.abs(receipt.temperature() - player.temperatures().get(receipt.lambda())));
                    for (double probability : receipt.probabilities())
                        probabilityError = Math.max(probabilityError, Math.abs(probability - 0.5));
                    gain = Math.max(gain, receipt.regularizedGain());
                    System.out.printf(Locale.ROOT, "%s %.5g %.5g %.8f %.8f %.8f %.8f %.8f %.8f%n",
                            receipt.power(), receipt.lambda(), receipt.temperature(),
                            receipt.probabilities().get(0), receipt.probabilities().get(1),
                            receipt.freshValues().get(0), receipt.freshValues().get(1),
                            receipt.responseResidual(), receipt.regularizedGain());
                }
                System.out.printf(Locale.ROOT, "Reference maximum absolute difference: %.3g%n", referenceError);
                if (!Double.isFinite(referenceError) || referenceError > REFERENCE_TOLERANCE)
                    throw new IllegalStateException("Independent reference differs at seed " + seed
                            + ", iteration " + checkpoints.get(index) + ", output " + output);
                summaries.add(new Summary(seed, checkpoints.get(index), output, probabilityError, gain, referenceError));
                if (index == history.size() - 1) {
                    System.out.println("\nPLAYING-TYPE RECEIPTS: " + output);
                    receipts.stream().filter(TypeReceipt::playing).forEach(System.out::print);
                }
            }
        }
        if (!game.snapshot().equals(fixture.root()) || game.getSnapshot() != null
                || !game.processorHistory().isEmpty())
            throw new IllegalStateException("Checkpoint comparison changed the simulation root");
        System.out.println("Evaluation work: " + trials.counts());
        return List.copyOf(summaries);
    }

    private static double difference(List<Double> left, List<Double> right) {
        double error = 0;
        for (int index = 0; index < left.size(); index++)
            error = Math.max(error, Math.abs(left.get(index) - right.get(index)));
        return error;
    }

    private static void requireBalanced(double[][][] payoffs, double[][] priors) {
        for (int player = 0; player < 2; player++)
            if (priors[player][0] != priors[player][1]
                    || Math.abs(payoffs[player][0][0] - payoffs[player][1][1]) > 1e-12
                    || Math.abs(payoffs[player][0][1] - payoffs[player][1][0]) > 1e-12)
                throw new IllegalStateException("The fixture no longer has the expected balanced form");
        for (int first = 0; first < 2; first++)
            for (int second = 0; second < 2; second++)
                if (Math.abs(payoffs[0][first][second] + payoffs[1][first][second] - 1) > 1e-12)
                    throw new IllegalStateException("The fixture is no longer constant-sum");
    }

}