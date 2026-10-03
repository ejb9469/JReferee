package testing;

import _app.StrategyBrowserApp;
import analysis.RoutePreferences;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import parsing.diplobn.JsonReader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Corpus-free HTTP checks using the real private browser handler. */
public final class StrategyBrowserHttpSelfCheck {
    private StrategyBrowserHttpSelfCheck() { }

    public static void main(String[] args) throws Exception {
        Method bootstrap = method("bootstrapJson", String.class, String.class, int.class);
        String initial = (String) bootstrap.invoke(null, "fixture-token", "synthetic", 0);
        Map<?, ?> defaults = object(object(JsonReader.read(initial)).get("defaults"));
        Map<String, String> form = new LinkedHashMap<>();
        defaults.forEach((key, value) -> form.put((String) key, (String) value));
        require(form.get("tacticalBiasWeight").equals("5"), "HTTP bootstrap lost bias default");
        form.put("units", "FRANCE ARMY Par");
        form.put("centers", "Bel FRANCE");
        Method handle = method("handle", HttpExchange.class, String.class, String.class,
                String.class, byte[].class, Map.class, Semaphore.class,
                RoutePreferences.class, String.class);
        Method error = method("sendError", HttpExchange.class, int.class, String.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        String authority = "127.0.0.1:" + server.getAddress().getPort();
        String origin = "http://" + authority;
        Semaphore generation = new Semaphore(1);
        RoutePreferences preferences = new RoutePreferences(null, 4, true, true);
        server.createContext("/", exchange -> {
            try {
                handle.invoke(null, exchange, authority, origin, "fixture-token",
                        initial.getBytes(StandardCharsets.UTF_8), Map.of(), generation,
                        preferences, "synthetic");
            } catch (InvocationTargetException exception) {
                try {
                    Throwable cause = exception.getCause();
                    error.invoke(null, exchange, cause instanceof IllegalArgumentException ? 400 : 500,
                            cause.getMessage());
                } catch (ReflectiveOperationException failure) {
                    throw new java.io.IOException(failure);
                }
            } catch (ReflectiveOperationException exception) {
                throw new java.io.IOException(exception);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String headers = "Origin: " + origin + "\r\nX-Strategy-Token: fixture-token\r\n"
                    + "Content-Type: application/x-www-form-urlencoded\r\n";
            String body = encoded(form);
            require(request(server, "GET", "/api/bootstrap", authority, "", "").status() == 200,
                    "Bootstrap unavailable");
            require(request(server, "POST", "/api/generate", "foreign.invalid", headers, body).status() == 403,
                    "Foreign Host accepted");
            require(request(server, "POST", "/api/generate", authority,
                    headers.replace(origin, "http://foreign.invalid"), body).status() == 403,
                    "Foreign Origin accepted");
            require(request(server, "POST", "/api/generate", authority,
                    headers.replace("fixture-token", "wrong-token"), body).status() == 403,
                    "Missing request authorization");
            require(request(server, "GET", "/api/generate", authority, "", "").status() == 405,
                    "Generation GET accepted");
            require(request(server, "POST", "/api/generate", authority,
                    headers.replace("application/x-www-form-urlencoded", "text/plain"), body).status() == 415,
                    "Wrong content type accepted");
            require(request(server, "POST", "/api/generate", authority, headers,
                    body + "&nation=ENGLAND").status() == 400, "Duplicate field accepted");
            require(request(server, "POST", "/api/generate", authority, headers,
                    body + "&unknown=true").status() == 400, "Unknown field accepted");
            require(request(server, "POST", "/api/generate", authority, headers,
                    "x".repeat(32_769)).status() == 413, "Oversized request accepted");
            generation.acquire();
            try {
                require(request(server, "POST", "/api/generate", authority, headers, body).status() == 429,
                        "Concurrent generation accepted");
            } finally {
                generation.release();
            }
            for (String invalid : new String[]{"-1", "NaN", "Infinity", "1001", ""}) {
                form.put("tacticalBiasWeight", invalid);
                require(request(server, "POST", "/api/generate", authority, headers, encoded(form)).status() == 400,
                        "HTTP accepted invalid bias: " + invalid);
            }
            form.remove("tacticalBiasWeight");
            require(request(server, "POST", "/api/generate", authority, headers, encoded(form)).status() == 400,
                    "Missing required tactical bias accepted");
            form.put("tacticalBiasWeight", "5");
            for (var entry : Map.of("humanWeight", "NaN", "globalValues", "SpaNC=1",
                    "regions.GERMANY", "north Bel 0 ARMY 4 0.5",
                    "adjustments.UNKNOWN", "Pru=1").entrySet()) {
                Map<String, String> bad = new LinkedHashMap<>(form);
                bad.put(entry.getKey(), entry.getValue());
                require(request(server, "POST", "/api/generate", authority, headers, encoded(bad)).status() == 400,
                        "HTTP accepted invalid profile: " + entry.getKey());
            }
            form.put("humanWeight", "2");
            form.put("globalValues", "MAO=1/FLEET\nION=1/FLEET");
            for (domain.Nation nation : domain.Nation.values()) {
                form.put("adjustments." + nation, "Pru=-1/ARMY");
                form.put("regions." + nation, "north Bel,Hol 2 ARMY 4 0.5");
            }
            form.put("regions.ENGLAND", "coast SpaNC 2 FLEET 4 0.5");
            Map<String, String> excessiveWork = new LinkedHashMap<>(form);
            excessiveWork.put("plans", "1");
            excessiveWork.put("scenarios", "1");
            excessiveWork.put("choices", "8");
            excessiveWork.put("units", String.join("\n", java.util.Arrays.stream(domain.Province.values())
                    .filter(province -> province != domain.Province.Swi
                            && domain.Province.canonical(province) == province)
                    .limit(75).map(province -> "ENGLAND "
                            + (province.geography == domain.Geography.WATER ? "FLEET" : "ARMY")
                            + " " + province).toList()));
            excessiveWork.put("regions.ENGLAND", String.join("\n", java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> "goal" + index + " Bel 1 ARMY,FLEET 4 0.5").toList()));
            Response budgetResponse = request(server, "POST", "/api/generate", authority, headers, encoded(excessiveWork));
            require(budgetResponse.status() == 400 && budgetResponse.body().contains("Geographic search work budget"),
                    "HTTP did not reject excessive geographic search before generation");
            form.put("objectives", "SpaNC 1");
            Map<String, String> duplicateAliases = new LinkedHashMap<>(form);
            duplicateAliases.put("objectives", "SpaNC 1\nSpaSC 2");
            require(request(server, "POST", "/api/generate", authority, headers, encoded(duplicateAliases)).status() == 400,
                    "HTTP accepted duplicate legacy snapshot coast aliases");
            for (String comparison : new String[]{"false", "true"}) {
                form.put("compareAlternatives", comparison);
                form.put("tacticalBiasWeight", comparison.equals("true") ? "0" : "5");
                Response response = request(server, "POST", "/api/generate", authority, headers, encoded(form));
                require(response.status() == 200, "Corpus-free generation failed: " + response.body());
                Map<?, ?> result = object(JsonReader.read(response.body()));
                Map<?, ?> board = object(result.get("board"));
                Map<?, ?> snapshotObjectives = object(object(result.get("context")).get("objectives"));
                require(snapshotObjectives.size() == 1 && snapshotObjectives.get("Spa") instanceof Number value
                                && value.doubleValue() == 1,
                        "HTTP did not preserve canonical legacy single-coast snapshot objective");
                Map<?, ?> scoring = object(object(result.get("context")).get("scoringConfiguration"));
                require(((Number) scoring.get("humanWeight")).doubleValue() == 2
                                && object(scoring.get("nationProfiles")).size() == 7
                                && object(scoring.get("globalValues")).size() == 2,
                        "HTTP lost frozen all-country profiles on abstention");
                Map<?, ?> english = object(object(scoring.get("nationProfiles")).get("ENGLAND"));
                require(object(((java.util.List<?>) english.get("objectives")).getFirst())
                                .get("targets").equals(java.util.List.of("SpaNC")),
                        "HTTP collapsed explicit regional coast targets");
                require(((Number) object(result.get("context")).get("tacticalBiasWeight")).doubleValue()
                                == Double.parseDouble(form.get("tacticalBiasWeight")),
                        "HTTP response lost result-time tactical bias weight");
                require(result.get("status").equals("NATION_ABSENT")
                                && ((java.util.List<?>) result.get("plans")).isEmpty()
                                && ((java.util.List<?>) board.get("units")).size() == 1
                                && object(board.get("supplyCenterOwners")).get("Bel").equals("FRANCE"),
                        "Abstention lost foreign units or vacant recorded center");
                require(response.headers().contains("content-security-policy:")
                                && response.headers().contains("cache-control: no-store"),
                        "Response security headers lost");
            }
            form.put("compareAlternatives", "unbounded");
            require(request(server, "POST", "/api/generate", authority, headers, encoded(form)).status() == 400,
                    "Invalid comparison option accepted");
            require(generation.availablePermits() == 1, "Generation permit leaked");
        } finally {
            server.stop(0);
        }
        System.out.println("StrategyBrowser HTTP boundary checks passed.");
    }

    private static Response request(HttpServer server, String method, String path,
                                    String host, String headers, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\n" + headers + "Content-Length: " + bytes.length
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int separator = response.indexOf("\r\n\r\n");
            return new Response(Integer.parseInt(response.split(" ", 3)[1]),
                    response.substring(0, separator).toLowerCase(java.util.Locale.ROOT),
                    response.substring(separator + 4));
        }
    }

    private static String encoded(Map<String, String> values) {
        return String.join("&", values.entrySet().stream().map(entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList());
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = StrategyBrowserApp.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map)
            return map;
        throw new AssertionError("Expected JSON object");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private record Response(int status, String headers, String body) { }
}
