package testing;

import analysis.*;
import parsing.diplobn.JsonReader;

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
        System.out.println("StrategyBrowser JSON contract checks passed.");
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
