package _app;

import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import adjudication.Justice;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import contracts.OrderForm;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;
import performance.PerformanceStats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Local counter-search demonstration, using actual Diplomacy adjudication.
 *
 * <p>France can concentrate an attack on Belgium or Munich. Germany's Ruhr
 * army can reinforce either defender. The initial pools omit France's Munich
 * attack and Germany's Munich defense.</p>
 *
 * <p>Controlled mode supplies two hand-built plans per nation to isolate the
 * expansion algorithm. Plausible mode generates responses from the full board.
 * Neither mode measures full-game strength. Retreats are deliberately omitted
 * during evaluation, and SoS rewards are measured at the resulting winter.</p>
 */
public final class CounterSearchApp {

    private CounterSearchApp() {        }

    public static final double LAMBDA = 0.03;

    public record Options(MovementProcessor.Policy policy, int iterations,
                          int expansionRounds, int cacheEntries) {
        public Options {
            Objects.requireNonNull(policy, "policy");
            if (iterations < 1 || expansionRounds < 0 || cacheEntries < 0)
                throw new IllegalArgumentException("Invalid demonstration budgets");
        }

        public static Options standard(MovementProcessor.Policy policy) {
            return new Options(policy, 2_048, 8, 4_096);
        }
    }

    public record Fixture(GameState root, ActionPrior prior, Map<Nation, List<ActionCandidate>> probes) {
        public Fixture {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(prior, "prior");
            probes = copyProbes(probes);
        }
    }

    /**
     * Fresh expected values against the final opposing population policy.
     * Residual compares that policy with the lambda=0.03 regularized response.
     */
    public record PolicyCheck(List<Double> freshValues, List<Double> probeValues, double currentValue,
                              double bestRetained, double bestProbe, double withinPoolGap,
                              double missingProbeGap, double responseResidual) {
        public PolicyCheck {
            freshValues = List.copyOf(freshValues);
            probeValues = List.copyOf(probeValues);
        }

        public double gain() {
            return withinPoolGap + missingProbeGap;
        }
    }

    public record Report(GameState root, Map<Nation, List<ActionCandidate>> probes,
                         List<List<Double>> matrix, AdaptiveDiLPiKLSearch.Result search,
                         List<Map<Nation, PolicyCheck>> checks, Options options,
                         List<PerformanceStats.Measurement> timings, MovementProcessor.Counts movement,
                         JointActionEvaluator.Counts trials) {
        public Report {
            probes = copyProbes(probes);
            matrix = matrix.stream().map(List::copyOf).toList();
            checks = checks.stream().map(Map::copyOf).toList();
            timings = List.copyOf(timings);
            if (checks.size() != search.solves().size())
                throw new IllegalArgumentException("Every completed solve requires its own diagnostics");
        }

        public List<Map<Nation, Double>> gains() {
            return checks.stream().map(check -> {
                Map<Nation, Double> values = new EnumMap<>(Nation.class);
                check.forEach((nation, value) -> values.put(nation, value.gain()));
                return Collections.unmodifiableMap(values);
            }).toList();
        }
    }

    public record Benchmark(long seed, Options options, JointSearchResult solve,
                            Map<Nation, PolicyCheck> checks, List<PerformanceStats.Measurement> timings,
                            MovementProcessor.Counts movement, JointActionEvaluator.Counts trials) {
        public Benchmark {
            checks = Map.copyOf(checks);
            timings = List.copyOf(timings);
        }

        public double probabilityError() {
            return solve.populationPolicies().values().stream()
                    .flatMap(policy -> policy.probabilities().stream())
                    .mapToDouble(probability -> Math.abs(probability - 0.5)).max().orElseThrow();
        }

        public double maximumGap() {
            return checks.values().stream().mapToDouble(PolicyCheck::withinPoolGap).max().orElseThrow();
        }

        public boolean meetsTarget() {
            return probabilityError() <= 0.05 && maximumGap() <= 0.02;
        }
    }

    private record Context(Game game, Evaluator evaluator, JointActionEvaluator trials,
                           MovementProcessor.Diagnostics movement) {
    }

    private static Map<Nation, List<ActionCandidate>> copyProbes(Map<Nation, List<ActionCandidate>> probes) {
        Map<Nation, List<ActionCandidate>> copy = new EnumMap<>(Nation.class);
        probes.forEach((nation, candidates) -> copy.put(nation, List.copyOf(candidates)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Supplies the same six-unit position and named plans to every experiment.
     */
    public static Fixture fixture() {
        UnitId pic = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        UnitId bur = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        UnitId tyr = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Tyr);
        UnitId bel = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Bel);
        UnitId mun = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId ruh = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ruh);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(pic, Province.Pic, bur, Province.Bur, tyr, Province.Tyr,
                        bel, Province.Bel, mun, Province.Mun, ruh, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE, Province.Bre, Nation.FRANCE,
                                Province.Bel, Nation.GERMANY, Province.Mun, Nation.GERMANY,
                                Province.Ber, Nation.GERMANY, Province.Kie, Nation.GERMANY)));
        ActionPrior prior = ActionPrior.convoyFreeUniform(root);
        Map<Nation, List<ActionCandidate>> probes = Map.of(
                Nation.FRANCE, List.of(
                        action(prior, Nation.FRANCE, Order.move(pic, Province.Bel),
                                Order.supportMove(bur, Province.Pic, Province.Bel), Order.hold(tyr)),
                        action(prior, Nation.FRANCE, Order.hold(pic),
                                Order.supportMove(bur, Province.Tyr, Province.Mun), Order.move(tyr, Province.Mun))),
                Nation.GERMANY, List.of(
                        action(prior, Nation.GERMANY, Order.hold(bel), Order.hold(mun),
                                Order.supportHold(ruh, Province.Bel)),
                        action(prior, Nation.GERMANY, Order.hold(bel), Order.hold(mun),
                                Order.supportHold(ruh, Province.Mun))));
        return new Fixture(root, prior, probes);
    }

    /**
     * Retains the reference adjudicator unless a caller selects another policy.
     * Root trials and continuations always use the same configured factory.
     */
    public static Report run(long seed, boolean plausible) {
        return run(seed, plausible, Options.standard(MovementProcessor.Policy.SZYKMAN_JUSTICE));
    }

    public static Report run(long seed, boolean plausible, Options options) {
        Fixture fixture = fixture();
        GameState root = fixture.root();
        Map<Nation, List<ActionCandidate>> probes = fixture.probes();
        Context context = context(fixture, seed, options);
        ActionGenerator proposals = plausible ? new PlausibleActionGenerator()
                : (request, catalog) -> probes.get(request.power());
        DiLPiKLSearch solver = solver(context, seed, options.iterations());
        ResponseSearch responses = new ResponseSearch(context.evaluator(), proposals, 32, 32, 1, 0.001);
        AdaptiveDiLPiKLSearch adaptive = new AdaptiveDiLPiKLSearch(
                solver, responses, 8, options.expansionRounds(), seed);
        Map<Nation, List<ActionCandidate>> initial = Map.of(
                Nation.FRANCE, List.of(probes.get(Nation.FRANCE).getFirst()),
                Nation.GERMANY, List.of(probes.get(Nation.GERMANY).getFirst()));
        AdaptiveDiLPiKLSearch.Result result;
        List<List<Double>> matrix = new ArrayList<>();
        List<Map<Nation, PolicyCheck>> checks = new ArrayList<>();
        try (var experiment = context.trials().measure("experiment")) {
            result = adaptive.search(context.game(), Nation.FRANCE, initial, fixture.prior(), context.trials());
            try (var diagnostics = context.trials().measure("policy diagnostics")) {
                for (ActionCandidate france : probes.get(Nation.FRANCE)) {
                    List<Double> row = new ArrayList<>();
                    for (ActionCandidate germany : probes.get(Nation.GERMANY))
                        row.add(context.trials().evaluate(Map.of(
                                Nation.FRANCE, france, Nation.GERMANY, germany)).get(Nation.FRANCE));
                    matrix.add(List.copyOf(row));
                }
                for (JointSearchResult solve : result.solves())
                    checks.add(checks(context.trials(), probes, solve));
            }
        }
        Game game = context.game();
        if (!game.snapshot().equals(root) || game.getSnapshot() != null || !game.processorHistory().isEmpty())
            throw new IllegalStateException("Demonstration changed the simulation root");
        return new Report(root, probes, matrix, result, checks, options,
                context.trials().timings(), context.movement().snapshot(), context.trials().counts());
    }

    /**
     * Solves both complete two-action pools without any response discovery.
     * This isolates finite-iteration policy error from proposal coverage.
     */
    public static Benchmark benchmark(long seed, Options options) {
        Fixture fixture = fixture();
        Context context = context(fixture, seed, options);
        JointSearchResult solve = solver(context, seed, options.iterations())
                .solve(context.game(), fixture.probes(), context.trials());
        Map<Nation, PolicyCheck> checks;
        try (var diagnostics = context.trials().measure("policy diagnostics")) {
            checks = checks(context.trials(), fixture.probes(), solve);
        }
        return new Benchmark(seed, options, solve, checks, context.trials().timings(),
                context.movement().snapshot(), context.trials().counts());
    }

    private static Context context(Fixture fixture, long seed, Options options) {
        PerformanceStats timings = new PerformanceStats(true);
        MovementProcessor.Diagnostics movement = new MovementProcessor.Diagnostics(timings);
        Function<GameState, Game> games = state -> Game.fromState(state,
                new MovementProcessor(options.policy(), Justice.NUM_TRIALS_DEFAULT,
                        Justice.SHUFFLE_SEED_DEFAULT, movement),
                new RetreatProcessor(), new AdjustmentProcessor());
        Game game = games.apply(fixture.root());
        Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), games, 1, 1, seed);
        JointActionEvaluator trials = options.cacheEntries() == 0
                ? JointActionEvaluator.uncached(game, evaluator, timings)
                : JointActionEvaluator.deterministic(game, evaluator, options.cacheEntries(), timings);
        return new Context(game, evaluator, trials, movement);
    }

    private static DiLPiKLSearch solver(Context context, long seed, int iterations) {
        return new DiLPiKLSearch(context.evaluator(), 2, iterations,
                LambdaDistribution.fixed(LAMBDA), LAMBDA, seed);
    }

    /**
     * Enumerates the opposing two-player pool under its final policy.
     * Named probes remain diagnostics and are not inserted into generated pools.
     */
    private static Map<Nation, PolicyCheck> checks(JointActionEvaluator trials,
                                                   Map<Nation, List<ActionCandidate>> probes,
                                                   JointSearchResult solve) {
        Map<Nation, PolicyCheck> result = new EnumMap<>(Nation.class);
        for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
            Nation other = nation == Nation.FRANCE ? Nation.GERMANY : Nation.FRANCE;
            ActionDistribution own = solve.populationPolicies().get(nation);
            ActionDistribution opponents = solve.populationPolicies().get(other);
            List<ActionCandidate> candidates = new ArrayList<>(own.candidates());
            candidates.addAll(probes.get(nation));
            List<Double> values = new ArrayList<>();
            double current = 0.0;
            double retained = Double.NEGATIVE_INFINITY;
            double probe = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < candidates.size(); index++) {
                ActionCandidate candidate = candidates.get(index);
                double value = 0.0;
                for (int opponent = 0; opponent < opponents.candidates().size(); opponent++) {
                    if (opponents.probabilities().get(opponent) == 0.0)
                        continue;
                    ActionCandidate response = opponents.candidates().get(opponent);
                    Map<Nation, Double> scores = trials.evaluate(Map.of(nation, candidate, other, response));
                    value += opponents.probabilities().get(opponent) * scores.get(nation);
                }
                values.add(value);
                if (index < own.candidates().size()) {
                    current += own.probabilities().get(index) * value;
                    retained = Math.max(retained, value);
                } else {
                    probe = Math.max(probe, value);
                }
            }
            List<Double> fresh = List.copyOf(values.subList(0, own.candidates().size()));
            ActionDistribution response = DiLPiKLSearch.policy(own.candidates(), fresh, LAMBDA);
            double residual = 0.0;
            for (int index = 0; index < fresh.size(); index++)
                residual = Math.max(residual,
                        Math.abs(response.probabilities().get(index) - own.probabilities().get(index)));
            result.put(nation, new PolicyCheck(fresh,
                    values.subList(own.candidates().size(), values.size()), current, retained, probe,
                    Math.max(0, retained - current), Math.max(0, probe - retained), residual));
        }
        return Collections.unmodifiableMap(result);
    }

    private static ActionCandidate action(ActionPrior prior, Nation nation, Order... orders) {
        List<OrderForm> submitted = new ArrayList<>(List.of(orders));
        return new ActionCandidate(nation, submitted, prior.logProbability(nation, submitted),
                ActionCandidate.Source.HEURISTIC);
    }

    // Browser interface \\
    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8098 : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", CounterSearchApp::handle);
        server.start();
        System.out.println("Counter-search demo: http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "GET required");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/run")) {
                send(exchange, 404, "Not found");
                return;
            }
            Map<String, String> query = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            long seed;
            boolean plausible;
            MovementProcessor.Policy policy;
            try {
                if (raw != null)
                    for (String field : raw.split("&")) {
                        String[] pair = field.split("=", 2);
                        query.put(pair[0], pair.length == 2
                                ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                    }
                seed = Long.parseLong(query.getOrDefault("seed", "7"));
                String mode = query.getOrDefault("mode", "controlled");
                if (!mode.equals("controlled") && !mode.equals("plausible"))
                    throw new IllegalArgumentException("Unknown proposal mode");
                plausible = mode.equals("plausible");
                policy = MovementProcessor.Policy.valueOf(query.getOrDefault("engine", "SZYKMAN_JUSTICE"));
            } catch (IllegalArgumentException exception) {
                send(exchange, 400, page("Invalid request: use an integer seed and listed modes.", 7, false,
                        MovementProcessor.Policy.SZYKMAN_JUSTICE));
                return;
            }
            try {
                send(exchange, 200, page(path.equals("/") ? "" : render(
                        run(seed, plausible, Options.standard(policy)), seed, plausible), seed, plausible, policy));
            } catch (RuntimeException exception) {
                exception.printStackTrace(System.err);
                send(exchange, 500, page("<h2>Experiment failed</h2><pre>" + escape(exception.toString()) + "</pre>",
                        seed, plausible, policy));
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String page(String result, long seed, boolean plausible, MovementProcessor.Policy policy) {
        StringBuilder engines = new StringBuilder();
        for (MovementProcessor.Policy option : MovementProcessor.Policy.values())
            engines.append("<option value=\"").append(option).append("\"")
                    .append(option == policy ? " selected" : "").append(">").append(option).append("</option>");
        return """
                <!doctype html><html lang="en"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>JReferee counter search</title>
                <style>
                body{font:16px system-ui;margin:2rem auto;padding:0 1rem;max-width:1100px;background:#f5f7fa;color:#172033}
                table{border-collapse:collapse;width:100%;background:white;margin:1rem 0}
                td,th{border:1px solid #ccd3df;padding:.6rem;text-align:left}th{background:#e5eaf2}
                pre{white-space:pre-wrap}button,input,select{font:inherit;padding:.4rem}
                details{margin:1rem 0}small{color:#45516a}
                </style><body><h1>JReferee: finding missing counters</h1>
                <p>Fall 1907. France: A Pic, A Bur, A Tyr. Germany: A Bel, A Mun, A Ruh.
                Ruhr can reinforce only one defender. Burgundy can support either French attack.</p>
                <form action="/run"><label>Seed <input name="seed" value="%SEED%" size="12"></label>
                <label>Proposals <select name="mode"><option value="controlled" %CONTROLLED%>Controlled two-plan menu</option>
                <option value="plausible" %PLAUSIBLE%>PlausibleActionGenerator</option></select></label>
                <label>Engine <select name="engine">%ENGINES%</select></label>
                <button type="submit">Run comparison</button></form>
                <p><small>Actual adjudication and winter SoS rewards. Retreats are omitted.
                Controlled mode checks solver mechanics; generated mode checks a broader proposal pool.
                This is a tactical experiment, not a full-game strength benchmark.</small></p>
                """.replace("%SEED%", Long.toString(seed))
                .replace("%CONTROLLED%", plausible ? "" : "selected")
                .replace("%PLAUSIBLE%", plausible ? "selected" : "")
                .replace("%ENGINES%", engines)
                + result + "</body></html>";
    }

    public static String render(Report report, long seed, boolean plausible) {
        StringBuilder html = new StringBuilder("<h2>Results</h2><p>Seed: ").append(seed)
                .append("; proposals: ").append(plausible ? "PlausibleActionGenerator" : "controlled")
                .append("; engine: ").append(report.options().policy())
                .append("; iterations: ").append(report.options().iterations())
                .append("; expansion limit: ").append(report.options().expansionRounds())
                .append("; stop: ").append(report.search().reason()).append("</p>")
                .append("<p>Cache: ").append(report.trials().hits()).append(" hits / ")
                .append(report.trials().requests()).append(" requests; movement calls: ")
                .append(report.movement().movements()).append("; shuffled trials: ")
                .append(report.movement().shuffledTrials()).append(".</p>");
        html.append("<h3>French winter reward: named plans</h3><table><tr><th>French plan</th>")
                .append("<th>Defend Belgium</th><th>Defend Munich</th></tr>");
        for (int row = 0; row < 2; row++)
            html.append("<tr><th>").append(row == 0 ? "Attack Belgium" : "Attack Munich")
                    .append("</th><td>").append(number(report.matrix().get(row).get(0)))
                    .append("</td><td>").append(number(report.matrix().get(row).get(1))).append("</td></tr>");
        html.append("</table><h3>Fresh final-policy diagnostics</h3><p>Within-pool gain compares the current ")
                .append("mixture with its best retained action. ")
                .append("Missing-probe gain is the additional advantage of a named probe over the best retained action. ")
                .append("Residual measures disagreement with the regularized response at lambda=0.03. ")
                .append("These diagnostics cover this small experiment only.</p>")
                .append("<table><tr><th>Solve</th><th>Nation</th><th>Pool</th><th>Current payoff</th>")
                .append("<th>Within-pool gain</th><th>Missing-probe gain</th><th>Response residual</th></tr>");
        for (int index = 0; index < report.search().solves().size(); index++) {
            JointSearchResult solve = report.search().solves().get(index);
            for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
                PolicyCheck check = report.checks().get(index).get(nation);
                html.append("<tr><td>").append(index).append("</td><td>").append(nation)
                        .append("</td><td>").append(solve.results().get(nation).meanValues().size())
                        .append("</td><td>").append(number(check.currentValue()))
                        .append("</td><td>").append(number(check.withinPoolGap()))
                        .append("</td><td>").append(number(check.missingProbeGap()))
                        .append("</td><td>").append(number(check.responseResidual())).append("</td></tr>");
            }
        }
        html.append("</table>");
        for (int index = 0; index < report.search().solves().size(); index++) {
            JointSearchResult solve = report.search().solves().get(index);
            html.append("<details open><summary>Solve ").append(index).append(": policies and orders</summary>");
            for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
                ActionDistribution policy = solve.populationPolicies().get(nation);
                html.append("<h4>").append(nation).append("</h4><table><tr><th>Population probability</th>")
                        .append("<th>Historical Q</th><th>Fresh value</th><th>Orders</th></tr>");
                for (int action = 0; action < policy.candidates().size(); action++)
                    html.append("<tr><td>").append(number(policy.probabilities().get(action)))
                            .append("</td><td>").append(number(solve.results().get(nation).meanValues().get(action)))
                            .append("</td><td>").append(number(report.checks().get(index).get(nation).freshValues().get(action)))
                            .append("</td><td>").append(escape(orders(report.root(), policy.candidates().get(action))))
                            .append("</td></tr>");
                html.append("</table>");
            }
            html.append("</details>");
        }
        for (AdaptiveDiLPiKLSearch.Expansion expansion : report.search().expansions()) {
            html.append("<details><summary>Discovery after solve ").append(expansion.solveIndex())
                    .append("</summary><table><tr><th>Nation</th><th>Decision</th><th>Measured gain</th><th>Orders</th></tr>");
            for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY))
                for (ResponseSearch.Proposal proposal : expansion.report().proposals().get(nation))
                    html.append("<tr><td>").append(nation).append("</td><td>")
                            .append(expansion.blocked().get(nation).contains(proposal.candidate())
                                    ? "POOL_LIMIT" : proposal.decision())
                            .append("</td><td>").append(proposal.measured()
                                    .map(value -> number(value.meanValue() - value.incumbentValue()))
                                    .orElse("not evaluated"))
                            .append("</td><td>").append(escape(orders(report.root(), proposal.candidate())))
                            .append("</td></tr>");
            html.append("</table></details>");
        }
        html.append("<h3>Elapsed timings</h3><p>Nested rows overlap.</p><table><tr><th>Stage</th>")
                .append("<th>Calls</th><th>Total milliseconds</th></tr>");
        for (PerformanceStats.Measurement timing : report.timings())
            html.append("<tr><td>").append(escape(timing.name())).append("</td><td>").append(timing.calls())
                    .append("</td><td>").append(number(timing.totalNanos() / 1_000_000.0)).append("</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    private static String orders(GameState root, ActionCandidate candidate) {
        List<String> descriptions = new ArrayList<>();
        for (OrderForm form : candidate.orders()) {
            if (!(form instanceof Order order))
                throw new IllegalStateException("Unexpected non-movement demonstration order");
            descriptions.add(OrderForm.format(order, root.board().locationOf(order.unit())));
        }
        return String.join("; ", descriptions);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}