package testing;

import analysis.*;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import parsing.diplobn.JsonReader;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Corpus-free checks for the browser's private adapter, without starting HTTP
 * or loading a database. Keep the adapter private rather than widening its API.
 */
public final class StrategyBrowserSelfCheck {

    private StrategyBrowserSelfCheck() { }

    public static void main(String[] args) throws Exception {
        Class<?> app = Class.forName("_app.StrategyBrowserApp");
        Method bootstrap = method(app, "bootstrapJson", String.class, String.class, int.class);
        Map<?, ?> initial = object(JsonReader.read(
                (String) bootstrap.invoke(null, "self-check", "synthetic", 0)));
        Map<String, String> form = new LinkedHashMap<>();
        object(initial.get("defaults")).forEach((key, value) ->
                form.put((String) key, (String) value));
        require(form.get("coordinationMode").equals("STRICT"), "Browser default is not strict");
        Method parse = method(app, "parseQuery", Map.class);
        Object query = parse.invoke(null, form);
        Method json = method(app, "resultJson", query.getClass(),
                MovementStrategy.Recommendation.class, String.class);
        Method config = method(query.getClass(), "configuration");
        require(((MovementStrategy.Configuration) config.invoke(query)).coordinationMode()
                        == CoordinationMode.STRICT,
                "Browser mode not passed to strategy configuration");

        String reason = "Fleet/army destination mismatch: \"Bel\" versus Nwy.\nNo fallback.";
        var diagnostics = new CoordinatedOrders.Diagnostics(
                CoordinationMode.STRICT, 5, 3, 1, true, List.of(reason), Map.of());
        var abstention = new MovementStrategy.Recommendation(
                PredictionPolicy.REFERENCE_FILTERED,
                MovementStrategy.Status.OWN_COORDINATION_LIMIT_REACHED,
                List.of(), Map.of(), 0, 0, List.of(),
                "No coherent plan within current limits.", diagnostics);
        Map<?, ?> response = object(JsonReader.read(
                (String) json.invoke(null, query, abstention, "synthetic")));
        require(response.get("status").equals("OWN_COORDINATION_LIMIT_REACHED")
                        && ((List<?>) response.get("plans")).isEmpty(),
                "JSON abstention contains a stale/fallback plan");
        Map<?, ?> coordination = object(response.get("coordination"));
        require(coordination.get("mode").equals("STRICT")
                        && coordination.get("beamTruncated").equals(true)
                        && ((Number) coordination.get("omittedChoices")).longValue() == 1
                        && coordination.get("reasons").equals(List.of(reason)),
                "JSON diagnostics lost counts, mode, or escaped rejection reason");
        Map<?, ?> context = object(response.get("context"));
        require(context.get("positionOnly").equals(true)
                        && context.get("equalScenarioWeights").equals(true)
                        && context.get("coordinationMode").equals("STRICT")
                        && object(context.get("objectives")).isEmpty()
                        && ((Number) context.get("year")).intValue() == 1901
                        && ((List<?>) object(response.get("board")).get("units")).size() == 22,
                "Abstention lost immutable settings, empty objectives or full validated board");
        form.put("year", "1902");
        form.put("centerWeight", "7");
        require(((Number) context.get("year")).intValue() == 1901
                        && ((Number) context.get("centerWeight")).doubleValue() == 1,
                "Edited controls changed result-time context");
        form.put("compareAlternatives", "on");
        Object comparisonQuery = parse.invoke(null, form);
        Map<?, ?> comparisonResponse = object(JsonReader.read(
                (String) json.invoke(null, comparisonQuery, abstention, "synthetic")));
        require(object(comparisonResponse.get("context")).get("compareAlternatives").equals(true),
                "Opt-in comparison not recorded");
        form.put("compareAlternatives", "unbounded");
        try {
            parse.invoke(null, form);
            throw new AssertionError("Invalid comparison option accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalArgumentException,
                    "Unexpected invalid comparison error");
        }
        form.remove("compareAlternatives");

        form.put("coordinationMode", "CONDITIONAL");
        query = parse.invoke(null, form);
        require(((MovementStrategy.Configuration) config.invoke(query)).coordinationMode()
                        == CoordinationMode.CONDITIONAL,
                "Conditional browser selection ignored");
        form.put("coordinationMode", "RAW");
        query = parse.invoke(null, form);
        require(((MovementStrategy.Configuration) config.invoke(query)).coordinationMode()
                        == CoordinationMode.RAW,
                "Legacy browser selection ignored");
        detailedResults(parse, json, form);
        System.out.println("StrategyBrowser JSON contract checks passed.");
    }

    private static void detailedResults(Method parse, Method json, Map<String, String> defaults)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>(defaults);
        form.put("units", "ENGLAND FLEET Edi\nENGLAND FLEET NTH\n"
                + "ENGLAND ARMY Yor\nFRANCE FLEET ENG");
        form.put("centers", "Bel FRANCE");
        form.put("objectives", "Bel 5");
        form.put("coordinationMode", "STRICT");
        form.put("compareAlternatives", "true");
        Object query = parse.invoke(null, form);
        BoardState board = (BoardState) method(query.getClass(), "board").invoke(query);
        GameMoment moment = (GameMoment) method(query.getClass(), "moment").invoke(query);
        UnitId edi = unit(board, Province.Edi), nth = unit(board, Province.NTH);
        UnitId yor = unit(board, Province.Yor), eng = unit(board, Province.ENG);
        var orders = List.of(Order.move(edi, Province.NTH),
                Order.convoy(nth, Province.Yor, Province.Bel), Order.move(yor, Province.Bel));
        Map<UnitId, RoutePrediction> predictions = Map.of(
                edi, new RoutePrediction("synthetic-fixture", 0,
                        Map.of(orders.get(0), 4L, Order.supportHold(edi, Province.NTH), 2L)),
                nth, new RoutePrediction("synthetic-fixture", 0, Map.of(orders.get(1), 4L)),
                yor, new RoutePrediction("synthetic-fixture", 0, Map.of(orders.get(2), 4L)));
        var generation = new CoordinatedOrders(4, 8)
                .generate(board, Nation.ENGLAND, predictions, CoordinationMode.STRICT);
        Map<String, List<Order>> scenarios = Map.of(
                "hold", List.of(Order.hold(eng)), "attack", List.of(Order.move(eng, Province.NTH)));
        var evaluations = new OutcomeEvaluator(7, 3, 0.5).rank(
                board, moment, generation.plans(), scenarios, Map.of(Province.Bel, 5.0),
                new MovementProcessor());
        var recommendation = new MovementStrategy.Recommendation(
                PredictionPolicy.REFERENCE_FILTERED, MovementStrategy.Status.RANKED,
                evaluations, Map.of(), generation.plans().size(), scenarios.size(),
                List.of(), "Synthetic fixture", generation.diagnostics(), scenarios, predictions);
        String serialized = (String) json.invoke(null, query, recommendation, "synthetic");
        require(serialized.equals(json.invoke(null, query, recommendation, "synthetic")),
                "Adapter result is not deterministic");
        Map<?, ?> response = object(JsonReader.read(serialized));
        boolean warningFound = false;
        for (Object item : (List<?>) response.get("plans")) {
            Map<?, ?> plan = object(item);
            List<?> details = (List<?>) plan.get("scenarios");
            require(details.size() == 2, "Explicit scenario set lost");
            double total = 0, worst = Double.POSITIVE_INFINITY;
            for (Object detail : details) {
                Map<?, ?> scenario = object(detail);
                double score = number(scenario.get("score"));
                require(Math.abs(score - (number(scenario.get("objectiveDelta"))
                                + number(scenario.get("centerPositionDelta"))
                                - number(scenario.get("dislodgementPenalty")))) < 1e-9,
                        "Scenario components do not reconcile");
                require(((List<?>) scenario.get("opponentOrders")).size() == 1,
                        "Explicit opponent orders omitted");
                total += score;
                worst = Math.min(worst, score);
            }
            require(number(plan.get("mean")) == total / 2
                            && number(plan.get("worst")) == worst
                            && number(plan.get("score")) == (total / 2 + worst) / 2,
                    "Equal-weight aggregate formula changed");
            require(object(plan.get("dependencySets")).get("checked").equals(true),
                    "Strict dependency check omitted");
            for (Object finding : (List<?>) plan.get("findings")) {
                warningFound = true;
                Map<?, ?> warning = object(finding);
                require(warning.get("rejected").equals(false) && number(warning.get("penalty")) == 0
                                && warning.get("evaluatedStatus").equals("EVALUATED"),
                        "Warning rejected/penalized a plan or comparison not evaluated");
                Map<?, ?> comparison = object(((List<?>) plan.get("comparisons")).getFirst());
                require(object(comparison.get("provenance")).get("basis").equals("synthetic-fixture")
                                && ((List<?>) comparison.get("scenarioDeltas")).size() == 2,
                        "Comparison lost evidence provenance or paired scenarios");
            }
        }
        require(warningFound, "Convoy-fleet tactical warning omitted from JSON");
    }

    private static UnitId unit(BoardState board, Province province) {
        return board.locations().entrySet().stream().filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map))
            throw new AssertionError("Expected JSON object");
        return map;
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
