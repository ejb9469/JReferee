package _app;

import agents.generation.ActionCandidate;
import agents.generation.ActionDistribution;
import agents.search.AdaptiveDiLPiKLSearch;
import agents.search.JointSearchResult;
import agents.search.SearchResult;
import agents.search.JointActionEvaluator;
import agents.search.ResponseSearch;
import adjudication.Justice;
import contracts.OrderForm;
import domain.Nation;
import game.GameState;
import phase.Order;
import phase.movement.MovementProcessor;
import performance.PerformanceStats;

import java.util.*;

/**
 * Prints the counter-search experiment without starting a server.
 *
 * <p>Runs complete-pool convergence checks, then controlled and plausible
 * expansion for three fixed seeds with fast/reference comparisons.
 * No arguments, console input, browser, or files are required.
 * CounterSearchApp.run() supplies the same experiment used by the web page.</p>
 *
 * <p>Each report includes the starting board, named probes, payoff matrix,
 * every completed solve, candidate additions, priors, values, and policies.
 * Action identifiers stay aligned across the append-only solve history.</p>
 *
 * <p>Deviation gains cover the named probes and retained actions only.
 * They are not full-game exploitability estimates. A decrease for a few
 * seeds does not establish playing strength or convergence.</p>
 */
public final class CounterSearchConsoleApp {

    private static final long[] SEEDS = {7L, 17L, 31L};
    private static final int[] ITERATIONS = {128, 512, 2_048, 8_192};

    private CounterSearchConsoleApp() {     }

    private record Summary(
            String mode, long seed, int solves, AdaptiveDiLPiKLSearch.StopReason stop,
            double initialGain, double finalGain, boolean referenceMatches
    ) {
    }

    /**
     * Runs the fixed batch and exits. Command-line arguments are unused.
     * Failures propagate; a failed batch does not print the completion marker.
     */
    public static void main(String[] args) {
        System.out.println("BEGIN COUNTER SEARCH DIAGNOSTICS v2");
        System.out.println("Experiment: CounterSearchApp.run(seed, plausible)");
        System.out.println("Java: " + System.getProperty("java.runtime.version"));
        System.out.println("VM: " + System.getProperty("java.vm.name"));
        System.out.println("OS: " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        System.out.println("Seeds: " + Arrays.toString(SEEDS));
        System.out.println("Rewards: winter Sum-of-Squares; dislodged units receive no retreat orders.");
        System.out.println("Controlled mode supplies two plans per nation.");
        System.out.println("Plausible mode generates response proposals from the full board.");
        System.out.println("Q values average sampled opponent history, not the final opponent policy.");
        System.out.println("Deviation gains are exact only over the displayed experiment's probe menu.");
        System.out.println("Positive gain means a tested unilateral alternative improves raw payoff.");
        System.out.println("No stop reason certifies equilibrium. Results may worsen between solves.");
        System.out.println("Population and agent lambda: " + CounterSearchApp.LAMBDA);
        System.out.println("Complete-pool targets at 8192 iterations: probability error <= 0.05; raw gap <= 0.02.");
        System.out.println("Caching is explicitly enabled for this fixed-seed deterministic experiment.");
        System.out.println("Timings include failed attempts; nested categories overlap.");
        boolean targetsMet = true;
        for (MovementProcessor.Policy policy : List.of(
                MovementProcessor.Policy.JUDGE, MovementProcessor.Policy.SZYKMAN_JUSTICE))
            for (int iterations : ITERATIONS)
                for (long seed : SEEDS) {
                    CounterSearchApp.Benchmark benchmark = CounterSearchApp.benchmark(seed,
                            new CounterSearchApp.Options(policy, iterations, 0, 4_096));
                    printBenchmark(benchmark);
                    if (iterations == 8_192 && !benchmark.meetsTarget())
                        targetsMet = false;
                }
        List<Summary> summaries = new ArrayList<>();
        for (boolean plausible : new boolean[]{false, true}) {
            String mode = plausible ? "PLAUSIBLE" : "CONTROLLED";
            for (long seed : SEEDS) {
                System.out.printf(Locale.ROOT, "%nBEGIN RUN mode=%s seed=%d%n", mode, seed);
                System.out.flush();
                long started = System.nanoTime();
                CounterSearchApp.Report report = CounterSearchApp.run(seed, plausible,
                        CounterSearchApp.Options.standard(MovementProcessor.Policy.JUDGE));
                printReport(report);
                CounterSearchApp.Report reference = CounterSearchApp.run(seed, plausible,
                        CounterSearchApp.Options.standard(MovementProcessor.Policy.SZYKMAN_JUSTICE));
                boolean matches = report.search().equals(reference.search())
                        && report.checks().equals(reference.checks()) && report.matrix().equals(reference.matrix());
                System.out.println("REFERENCE COMPARISON search_and_values_match=" + matches);
                printWork(reference.options(), reference.timings(), reference.movement(), reference.trials());
                if (!matches)
                    printReport(reference);
                double initial = worstGain(report.gains().getFirst());
                double last = worstGain(report.gains().getLast());
                summaries.add(new Summary(mode, seed, report.search().solves().size(),
                        report.search().reason(), initial, last, matches));
                System.out.printf(Locale.ROOT,
                        "RUN SUMMARY initial_max_gain=%.10f final_max_gain=%.10f reduction=%.10f%n",
                        initial, last, initial - last);
                System.out.printf(Locale.ROOT, "Fast/reference pair elapsed seconds: %.3f%n",
                        (System.nanoTime() - started) / 1_000_000_000.0);
                System.out.printf(Locale.ROOT, "END RUN mode=%s seed=%d%n", mode, seed);
                System.out.flush();
            }
        }
        System.out.println("\nBATCH SUMMARY");
        System.out.println("mode seed solves stop initial_max_gain final_max_gain reduction reference_matches");
        for (Summary summary : summaries)
            System.out.printf(Locale.ROOT, "%s %d %d %s %.10f %.10f %.10f %s%n",
                    summary.mode(), summary.seed(), summary.solves(), summary.stop(),
                    summary.initialGain(), summary.finalGain(), summary.initialGain() - summary.finalGain(),
                    summary.referenceMatches());
        System.out.println("These are small tactical diagnostics, not a strength benchmark.");
        if (!targetsMet)
            throw new IllegalStateException("Complete-pool convergence targets were not met; inspect benchmark rows");
        System.out.println("END COUNTER SEARCH DIAGNOSTICS");
    }

    private static void printBenchmark(CounterSearchApp.Benchmark benchmark) {
        System.out.printf(Locale.ROOT,
                "%nBENCHMARK engine=%s seed=%d iterations=%d probability_error=%.10f raw_gap=%.10f target_met=%s%n",
                benchmark.options().policy(), benchmark.seed(), benchmark.options().iterations(),
                benchmark.probabilityError(), benchmark.maximumGap(), benchmark.meetsTarget());
        for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
            SearchResult result = benchmark.solve().results().get(nation);
            CounterSearchApp.PolicyCheck check = benchmark.checks().get(nation);
            for (int index = 0; index < result.meanValues().size(); index++)
                System.out.printf(Locale.ROOT,
                        "  %s-P%d p=%.10f historical_Q=%.10f fresh_value=%.10f response_residual=%.10f%n",
                        nation, index, result.distribution().probabilities().get(index),
                        result.meanValues().get(index), check.freshValues().get(index), check.responseResidual());
        }
        printWork(benchmark.options(), benchmark.timings(), benchmark.movement(), benchmark.trials());
    }

    private static void printWork(CounterSearchApp.Options options,
                                  List<PerformanceStats.Measurement> timings,
                                  MovementProcessor.Counts movement, JointActionEvaluator.Counts trials) {
        boolean plain = options.policy() == MovementProcessor.Policy.JUDGE;
        System.out.printf(Locale.ROOT,
                "CONFIG engine=%s configured_trials=%s shuffle_seed=%s iterations=%d rounds=%d cache_capacity=%d%n",
                options.policy(), plain ? "unused" : Integer.toString(Justice.NUM_TRIALS_DEFAULT),
                plain ? "unused" : Long.toString(Justice.SHUFFLE_SEED_DEFAULT),
                options.iterations(), options.expansionRounds(), options.cacheEntries());
        System.out.println("JOINT WORK " + trials);
        System.out.println("MOVEMENT WORK " + movement);
        for (PerformanceStats.Measurement timing : timings)
            System.out.printf(Locale.ROOT, "TIMING %s calls=%d total_ms=%.3f average_ms=%.6f%n",
                    timing.name(), timing.calls(), timing.totalNanos() / 1_000_000.0, timing.averageMillis());
    }

    private static void printReport(CounterSearchApp.Report report) {
        GameState root = report.root();
        System.out.println("Root: " + root.moment());
        System.out.println("Requested nation: " + report.search().power());
        System.out.println("Stop reason: " + report.search().reason());
        System.out.println("Completed solves: " + report.search().solves().size());
        System.out.println("Units:");
        root.board().locations().entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(entry -> System.out.println("  " + OrderForm.unitText(entry.getKey(), entry.getValue())));
        System.out.println("Owned supply centers:");
        root.board().owners().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println("  " + entry.getKey() + " = " + entry.getValue()));
        System.out.println("\nNAMED PROBES");
        for (Nation nation : Nation.values()) {
            List<ActionCandidate> probes = report.probes().get(nation);
            if (probes == null)
                continue;
            for (int index = 0; index < probes.size(); index++) {
                ActionCandidate candidate = probes.get(index);
                System.out.printf(Locale.ROOT, "%s-P%d log_prior=%.10f%n", nation, index, candidate.logPrior());
                printOrders(root, candidate);
            }
        }
        System.out.println("\nNAMED-PLAN PAYOFF MATRIX");
        System.out.println("Rows: FRANCE-Pn; columns: GERMANY-Pn; entries: French winter reward.");
        for (int row = 0; row < report.matrix().size(); row++)
            for (int column = 0; column < report.matrix().get(row).size(); column++)
                System.out.printf(Locale.ROOT, "FRANCE-P%d GERMANY-P%d %.10f%n",
                        row, column, report.matrix().get(row).get(column));
        for (int index = 0; index < report.search().solves().size(); index++) {
            JointSearchResult solve = report.search().solves().get(index);
            System.out.printf(Locale.ROOT, "%nSOLVE %d %s%n", index,
                    index == 0 ? "(restricted baseline)" : "(expanded pools, fresh Q)");
            for (Nation nation : Nation.values()) {
                SearchResult result = solve.results().get(nation);
                if (result == null)
                    continue;
                List<ActionCandidate> candidates = result.distribution().candidates();
                ActionDistribution population = solve.populationPolicies().get(nation);
                ActionDistribution anchor = ActionDistribution.fromPriors(candidates);
                int previousSize = index == 0 ? 0 : report.search().solves().get(index - 1)
                        .results().get(nation).distribution().candidates().size();
                System.out.printf(Locale.ROOT, "%s candidates=%d observations_per_candidate=%d deviation_gain=%.10f%n",
                        nation, candidates.size(), result.samplesPerCandidate(), report.gains().get(index).get(nation));
                System.out.printf(Locale.ROOT, "  agent_mass=%.12f population_mass=%.12f%n",
                        mass(result.distribution()), mass(population));
                CounterSearchApp.PolicyCheck check = report.checks().get(index).get(nation);
                System.out.printf(Locale.ROOT,
                        "  fresh_current=%.10f best_retained=%.10f best_probe=%.10f within_pool_gap=%.10f missing_probe_gap=%.10f response_residual=%.10f%n",
                        check.currentValue(), check.bestRetained(), check.bestProbe(), check.withinPoolGap(),
                        check.missingProbeGap(), check.responseResidual());
                for (int action = 0; action < candidates.size(); action++) {
                    ActionCandidate candidate = candidates.get(action);
                    String status = index == 0 ? "INITIAL" : action >= previousSize ? "ADDED" : "RETAINED";
                    System.out.printf(Locale.ROOT,
                            "  %s-A%d %s source=%s log_prior=%.10f pool_anchor=%.10f Q=%.10f fresh=%.10f agent_p=%.10f population_p=%.10f%n",
                            nation, action, status, candidate.source(), candidate.logPrior(),
                            anchor.probabilities().get(action), result.meanValues().get(action),
                            check.freshValues().get(action),
                            result.distribution().probabilities().get(action), population.probabilities().get(action));
                    printOrders(root, candidate);
                }
            }
            System.out.printf(Locale.ROOT, "MAX DEVIATION GAIN %.10f%n", worstGain(report.gains().get(index)));
        }
        for (AdaptiveDiLPiKLSearch.Expansion expansion : report.search().expansions())
            printExpansion(report, expansion);
        printWork(report.options(), report.timings(), report.movement(), report.trials());
    }

    private static void printExpansion(CounterSearchApp.Report report,
                                       AdaptiveDiLPiKLSearch.Expansion expansion) {
        ResponseSearch.Report discovery = expansion.report();
        System.out.printf(Locale.ROOT,
                "%nDISCOVERY after_solve=%d seed=%d proposal_limit=%d samples=%d additions_limit=%d minimum_gain=%.10f%n",
                expansion.solveIndex(), discovery.seed(), discovery.proposalLimit(), discovery.opponentSamples(),
                discovery.additionsPerNation(), discovery.minimumGain());
        for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
            List<ResponseSearch.Proposal> proposals = discovery.proposals().get(nation);
            Map<ResponseSearch.Decision, Integer> counts = new EnumMap<>(ResponseSearch.Decision.class);
            proposals.forEach(proposal -> counts.merge(proposal.decision(), 1, Integer::sum));
            System.out.println(nation + " proposed=" + proposals.size() + " decisions=" + counts
                    + " pool_limit_rejections=" + expansion.blocked().get(nation).size());
            for (ResponseSearch.Proposal proposal : proposals) {
                String decision = expansion.blocked().get(nation).contains(proposal.candidate())
                        ? "POOL_LIMIT" : proposal.decision().name();
                System.out.print("  PROPOSAL " + decision);
                if (proposal.measured().isPresent()) {
                    ResponseSearch.Response value = proposal.measured().get();
                    System.out.printf(Locale.ROOT, " mean=%.10f incumbent=%.10f sampled_gain=%.10f%n",
                            value.meanValue(), value.incumbentValue(), value.meanValue() - value.incumbentValue());
                } else {
                    System.out.println(" not_evaluated");
                }
                printOrders(report.root(), proposal.candidate());
            }
        }
        ActionCandidate munich = report.probes().get(Nation.GERMANY).get(1);
        Set<OrderForm> orders = Set.copyOf(munich.orders());
        boolean retained = report.search().solves().get(expansion.solveIndex()).populationPolicies()
                .get(Nation.GERMANY).candidates().stream()
                .anyMatch(candidate -> Set.copyOf(candidate.orders()).equals(orders));
        List<ResponseSearch.Decision> decisions = discovery.proposals().get(Nation.GERMANY).stream()
                .filter(proposal -> Set.copyOf(proposal.candidate().orders()).equals(orders))
                .map(ResponseSearch.Proposal::decision).distinct().toList();
        CounterSearchApp.PolicyCheck check = report.checks().get(expansion.solveIndex()).get(Nation.GERMANY);
        System.out.printf(Locale.ROOT,
                "MUNICH DEFENSE retained=%s proposed=%s decisions=%s fresh_gain_over_best_retained=%.10f%n",
                retained, !decisions.isEmpty(), decisions, check.probeValues().get(1) - check.bestRetained());
    }

    /**
     * Uses current board locations, not unit creation provinces.
     * This experiment contains movement orders only.
     */
    private static void printOrders(GameState root, ActionCandidate candidate) {
        List<Order> orders = new ArrayList<>();
        for (OrderForm form : candidate.orders()) {
            if (!(form instanceof Order order))
                throw new IllegalStateException("Unexpected non-movement demonstration order");
            orders.add(order);
        }
        orders.sort(Comparator.comparing(order -> root.board().locationOf(order.unit())));
        for (Order order : orders)
            System.out.println("    " + OrderForm.format(order, root.board().locationOf(order.unit())));
    }

    private static double mass(ActionDistribution distribution) {
        return distribution.probabilities().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double worstGain(Map<Nation, Double> gains) {
        return gains.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

}