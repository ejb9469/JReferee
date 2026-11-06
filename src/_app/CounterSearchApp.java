package _app;

import agents.evaluation.*;
import agents.generation.*;
import agents.search.*;
import agents.simulation.Simulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import contracts.OrderForm;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

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

    private CounterSearchApp() { }

    public record Report(GameState root, Map<Nation, List<ActionCandidate>> probes,
                         List<List<Double>> matrix, AdaptiveDiLPiKLSearch.Result search,
                         List<Map<Nation, Double>> gains) { }

    /**
     * Runs the same experiment used by the browser and the standalone checks.
     */
    public static Report run(long seed, boolean plausible) {
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
        ActionGenerator proposals = plausible ? new PlausibleActionGenerator()
                : (request, catalog) -> probes.get(request.power());
        Evaluator evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), Game::fromState, 1, 1, seed);
        DiLPiKLSearch solver = new DiLPiKLSearch(evaluator, 2, 128,
                LambdaDistribution.fixed(0.03), 0.03, seed);
        ResponseSearch responses = new ResponseSearch(evaluator, proposals, 32, 32, 1, 0.001);
        AdaptiveDiLPiKLSearch adaptive = new AdaptiveDiLPiKLSearch(solver, responses, 8, 3, seed);
        Map<Nation, List<ActionCandidate>> initial = Map.of(
                Nation.FRANCE, List.of(probes.get(Nation.FRANCE).getFirst()),
                Nation.GERMANY, List.of(probes.get(Nation.GERMANY).getFirst()));
        Game game = Game.fromState(root);
        AdaptiveDiLPiKLSearch.Result result = adaptive.search(game, Nation.FRANCE, initial, prior);
        List<List<Double>> matrix = new ArrayList<>();
        for (ActionCandidate france : probes.get(Nation.FRANCE)) {
            List<Double> row = new ArrayList<>();
            for (ActionCandidate germany : probes.get(Nation.GERMANY))
                row.add(payoffs(game, evaluator, france, germany).get(Nation.FRANCE));
            matrix.add(List.copyOf(row));
        }
        List<Map<Nation, Double>> gains = new ArrayList<>();
        for (JointSearchResult solve : result.solves())
            gains.add(gains(game, evaluator, probes, solve));
        if (!game.snapshot().equals(root) || game.getSnapshot() != null || !game.processorHistory().isEmpty())
            throw new IllegalStateException("Demonstration changed the simulation root");
        return new Report(root, probes, List.copyOf(matrix), result, List.copyOf(gains));
    }

    /**
     * Exact deviation gains against the displayed policies, over the named
     * probes and retained actions only. This is not full-game exploitability.
     */
    private static Map<Nation, Double> gains(Game game, Evaluator evaluator,
                                             Map<Nation, List<ActionCandidate>> probes, JointSearchResult solve) {
        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
            Nation other = nation == Nation.FRANCE ? Nation.GERMANY : Nation.FRANCE;
            ActionDistribution own = solve.populationPolicies().get(nation);
            ActionDistribution opponents = solve.populationPolicies().get(other);
            List<ActionCandidate> candidates = new ArrayList<>(own.candidates());
            candidates.addAll(probes.get(nation));
            double current = 0.0;
            double best = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < candidates.size(); index++) {
                ActionCandidate candidate = candidates.get(index);
                double value = 0.0;
                for (int opponent = 0; opponent < opponents.candidates().size(); opponent++) {
                    ActionCandidate response = opponents.candidates().get(opponent);
                    Map<Nation, Double> scores = nation == Nation.FRANCE
                            ? payoffs(game, evaluator, candidate, response)
                            : payoffs(game, evaluator, response, candidate);
                    value += opponents.probabilities().get(opponent) * scores.get(nation);
                }
                if (index < own.candidates().size())
                    current += own.probabilities().get(index) * value;
                best = Math.max(best, value);
            }
            result.put(nation, Math.max(0.0, best - current));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<Nation, Double> payoffs(Game game, Evaluator evaluator,
                                               ActionCandidate france, ActionCandidate germany) {
        return evaluator.evaluate(new Simulator().peek(game, Map.of(
                Nation.FRANCE, france, Nation.GERMANY, germany)));
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
            } catch (IllegalArgumentException exception) {
                send(exchange, 400, page("Invalid request: use an integer seed and a listed proposal mode.", 7, false));
                return;
            }
            try {
                send(exchange, 200, page(path.equals("/") ? "" : render(run(seed, plausible), seed, plausible),
                        seed, plausible));
            } catch (RuntimeException exception) {
                exception.printStackTrace(System.err);
                send(exchange, 500, page("<h2>Experiment failed</h2><pre>" + escape(exception.toString()) + "</pre>",
                        seed, plausible));
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String page(String result, long seed, boolean plausible) {
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
                <button type="submit">Run comparison</button></form>
                <p><small>Actual adjudication and winter SoS rewards. Retreats are omitted.
                Controlled mode checks solver mechanics; generated mode checks a broader proposal pool.
                This is a tactical experiment, not a full-game strength benchmark.</small></p>
                """.replace("%SEED%", Long.toString(seed))
                .replace("%CONTROLLED%", plausible ? "" : "selected")
                .replace("%PLAUSIBLE%", plausible ? "selected" : "")
                + result + "</body></html>";
    }

    public static String render(Report report, long seed, boolean plausible) {
        StringBuilder html = new StringBuilder("<h2>Results</h2><p>Seed: ").append(seed)
                .append("; proposals: ").append(plausible ? "PlausibleActionGenerator" : "controlled")
                .append("; stop: ").append(report.search().reason()).append("</p>");
        html.append("<h3>French winter reward: named plans</h3><table><tr><th>French plan</th>")
                .append("<th>Defend Belgium</th><th>Defend Munich</th></tr>");
        for (int row = 0; row < 2; row++)
            html.append("<tr><th>").append(row == 0 ? "Attack Belgium" : "Attack Munich")
                    .append("</th><td>").append(number(report.matrix().get(row).get(0)))
                    .append("</td><td>").append(number(report.matrix().get(row).get(1))).append("</td></tr>");
        html.append("</table><h3>Known-menu deviation gain</h3><p>Best tested unilateral payoff minus current ")
                .append("policy payoff. Lower is better against these probes; this is not an equilibrium certificate.</p>")
                .append("<table><tr><th>Solve</th><th>French pool</th><th>German pool</th>")
                .append("<th>France gain</th><th>Germany gain</th></tr>");
        for (int index = 0; index < report.search().solves().size(); index++) {
            JointSearchResult solve = report.search().solves().get(index);
            html.append("<tr><td>").append(index).append(index == 0 ? " (restricted baseline)" : "")
                    .append("</td><td>").append(solve.results().get(Nation.FRANCE).meanValues().size())
                    .append("</td><td>").append(solve.results().get(Nation.GERMANY).meanValues().size())
                    .append("</td><td>").append(number(report.gains().get(index).get(Nation.FRANCE)))
                    .append("</td><td>").append(number(report.gains().get(index).get(Nation.GERMANY))).append("</td></tr>");
        }
        html.append("</table>");
        for (int index = 0; index < report.search().solves().size(); index++) {
            JointSearchResult solve = report.search().solves().get(index);
            html.append("<details open><summary>Solve ").append(index).append(": policies and orders</summary>");
            for (Nation nation : List.of(Nation.FRANCE, Nation.GERMANY)) {
                ActionDistribution policy = solve.populationPolicies().get(nation);
                html.append("<h4>").append(nation).append("</h4><table><tr><th>Population probability</th>")
                        .append("<th>Orders</th></tr>");
                for (int action = 0; action < policy.candidates().size(); action++)
                    html.append("<tr><td>").append(number(policy.probabilities().get(action)))
                            .append("</td><td>").append(escape(orders(report.root(), policy.candidates().get(action))))
                            .append("</td></tr>");
                html.append("</table>");
            }
            html.append("</details>");
        }
        return html.toString();
    }

    private static String orders(GameState root, ActionCandidate candidate) {
        List<String> descriptions = new ArrayList<>();
        for (OrderForm form : candidate.orders()) {
            Order order = (Order) form;
            String prefix = order.unitType().name().charAt(0) + " " + root.board().locationOf(order.unit());
            descriptions.add(prefix + switch (order.orderType()) {
                case HOLD -> " H";
                case MOVE -> " - " + order.target();
                case SUPPORT -> " S " + order.target()
                        + (order.auxiliaryTarget() == null ? " H" : " - " + order.auxiliaryTarget());
                default -> throw new IllegalStateException("Unexpected demonstration order");
            });
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