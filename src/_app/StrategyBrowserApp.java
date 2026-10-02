package _app;

import analysis.*;
import analysis.openings.Classifier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import contracts.OrderForm;
import domain.Constants;
import domain.Geography;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import ui.BoardSnapshotJsonWriter;
import ui.BoardSnapshotMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static io.json.JsonEscaper.appendString;


public final class StrategyBrowserApp {

    private static final int MAX_REQUEST_BYTES = 32_768;

    private static final Set<String> FORM_FIELDS = Set.of(
            "nation", "year", "phase", "policy", "coordinationMode",
            "units", "centers", "objectives",
            "centerWeight", "dislodgementPenalty", "caution",
            "minimum", "choices", "plans", "scenarios");

    private StrategyBrowserApp() { }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: StrategyBrowserApp [database-path]");

        Path database = (args.length == 1
                ? Path.of(args[0])
                : Path.of("data", "diplobn-catalog.sqlite"))
                .toAbsolutePath().normalize();

        Path ui = Path.of("src", "resources", "ui");

        // Load only these specific assets; never accept filesystem paths from HTTP.
        Map<String, Asset> assets = Map.of(
                "/", asset(ui.resolve("strategy.html"), "text/html"),
                "/ui/strategy.js",
                asset(ui.resolve("strategy.js"), "text/javascript"),
                "/ui/board-map.js",
                asset(ui.resolve("board-map.js"), "text/javascript"),
                "/ui/openings.css",
                asset(ui.resolve("openings.css"), "text/css"),
                "/ui/browser-controls.css",
                asset(ui.resolve("browser-controls.css"), "text/css"),
                "/ui/maps/standard.svg",
                asset(ui.resolve("maps/standard.svg"), "image/svg+xml"));

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        var training = corpus.games(CorpusPartition.TRAINING);

        if (training.isEmpty())
            throw new IllegalArgumentException("No accepted training games");

        Set<String> rulesets = new TreeSet<>();
        for (var game : training)
            rulesets.add(game.rulesetId());

        if (rulesets.size() != 1)
            throw new IllegalArgumentException(
                    "This browser requires one training ruleset, found: " + rulesets);

        String ruleset = rulesets.iterator().next();

        // Explicit browser configuration; existing engine defaults are unchanged.
        RoutePreferences preferences = new RoutePreferences(null, 4, true, true);

        for (int index = 0; index < training.size(); index++) {
            if (!preferences.add(training.get(index)))
                throw new IllegalStateException("Training game was not added");

            if ((index + 1) % 100 == 0 || index + 1 == training.size())
                System.out.printf(
                        "Training: %d/%d%n", index + 1, training.size());
        }

        String token = UUID.randomUUID().toString();
        byte[] bootstrap = bootstrapJson(token, ruleset, training.size())
                .getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 16);

        var executor = Executors.newFixedThreadPool(2);
        Semaphore generation = new Semaphore(1);

        String authority = "127.0.0.1:" + server.getAddress().getPort();
        String origin = "http://" + authority;

        server.setExecutor(executor);

        server.createContext("/", exchange -> {
            try {
                handle(exchange, authority, origin, token, bootstrap,
                        assets, generation, preferences, ruleset);
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, exception.getMessage());
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                sendError(exchange, 500,
                        "Generation failed internally. See the Java console.");
            } finally {
                exchange.close();
            }
        });

        Thread shutdown = new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
        }, "strategy-browser-shutdown");

        Runtime.getRuntime().addShutdownHook(shutdown);

        try {
            server.start();
            System.out.println();
            System.out.println("Strategy browser: " + origin);
            System.out.println("Training model ready. Edit a position and press Generate.");
            System.out.println("No database writes or order execution. Ctrl+C to stop.");
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            server.stop(0);
            executor.shutdownNow();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already started.
            }
        }

    }


    private static void handle(
            HttpExchange exchange,
            String authority,
            String origin,
            String token,
            byte[] bootstrap,
            Map<String, Asset> assets,
            Semaphore generation,
            RoutePreferences preferences,
            String ruleset) throws IOException {

        if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))) {
            sendError(exchange, 403, "Use the printed 127.0.0.1 address.");
            return;
        }

        String suppliedOrigin = exchange.getRequestHeaders().getFirst("Origin");

        if (suppliedOrigin != null && !origin.equals(suppliedOrigin)) {
            sendError(exchange, 403, "Cross-origin requests are not allowed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.equals("/api/generate")) {

            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendError(exchange, 405, "Use POST.");
                return;
            }

            if (!token.equals(
                    exchange.getRequestHeaders().getFirst("X-Strategy-Token"))) {
                sendError(exchange, 403, "Invalid request token; reload the page.");
                return;
            }

            String contentType =
                    exchange.getRequestHeaders().getFirst("Content-Type");

            if (contentType == null ||
                    !contentType.split(";", 2)[0].trim()
                            .equalsIgnoreCase("application/x-www-form-urlencoded")) {
                sendError(exchange, 415, "Expected a URL-encoded form.");
                return;
            }

            if (!generation.tryAcquire()) {
                sendError(exchange, 429,
                        "Another generation is running. Try again when it finishes.");
                return;
            }

            try {
                byte[] body = exchange.getRequestBody()
                        .readNBytes(MAX_REQUEST_BYTES + 1);

                if (body.length > MAX_REQUEST_BYTES) {
                    sendError(exchange, 413, "Position form is too large.");
                    return;
                }

                Query query = parseQuery(parseForm(body));

                var recommendation = MovementStrategy.recommend(
                        preferences,
                        ruleset,
                        query.moment(),
                        query.board(),
                        query.nation(),
                        Map.of(), // Position-only: no invented route histories.
                        query.objectives(),
                        query.configuration(),
                        new OutcomeEvaluator(
                                query.centerWeight(),
                                query.dislodgementPenalty(),
                                query.caution()),
                        new MovementProcessor());

                send(exchange, 200, "application/json; charset=utf-8",
                        resultJson(query, recommendation, ruleset)
                                .getBytes(StandardCharsets.UTF_8));

            } finally {
                generation.release();
            }

            return;
        }

        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "Use GET.");
            return;
        }

        if (path.equals("/api/bootstrap")) {
            send(exchange, 200, "application/json; charset=utf-8", bootstrap);
            return;
        }

        Asset asset = assets.get(path);

        if (asset == null) {
            sendError(exchange, 404, "Not found.");
            return;
        }

        send(exchange, 200, asset.type(), asset.bytes());

    }


    private static Map<String, String> parseForm(byte[] body) {

        Map<String, String> values = new LinkedHashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);

        for (String pair : text.split("&")) {
            int separator = pair.indexOf('=');

            if (separator < 0)
                throw new IllegalArgumentException("Malformed form.");

            String key = URLDecoder.decode(
                    pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(
                    pair.substring(separator + 1), StandardCharsets.UTF_8);

            if (!FORM_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown form field: " + key);

            if (values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Duplicate form field: " + key);
        }

        if (!values.keySet().equals(FORM_FIELDS))
            throw new IllegalArgumentException("Form is missing required fields.");

        return values;

    }

    private static Query parseQuery(Map<String, String> values) {

        Nation nation = enumValue(Nation.class, values.get("nation"));
        GamePhase phase = enumValue(GamePhase.class, values.get("phase"));

        if (!phase.isMovement())
            throw new IllegalArgumentException("Only movement phases are supported.");

        GameMoment moment = new GameMoment(
                integer(values, "year", 1901, 9999), phase);

        int choices = integer(values, "choices", 1, 8);
        int plans = integer(values, "plans", 1, 32);
        int scenarios = integer(values, "scenarios", 1, 64);

        if (plans * scenarios > 512)
            throw new IllegalArgumentException(
                    "National plans × opponent scenarios must not exceed 512.");

        var configuration = new MovementStrategy.Configuration(
                enumValue(PredictionPolicy.class, values.get("policy")),
                integer(values, "minimum", 1, 10000),
                choices, plans, scenarios,
                enumValue(CoordinationMode.class, values.get("coordinationMode")));

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        Set<Province> occupied = EnumSet.noneOf(Province.class);

        for (String row : rows(values.get("units"))) {
            String[] fields = fields(row, 3, "NATION TYPE PROVINCE");

            Nation owner = enumValue(Nation.class, fields[0]);
            UnitType type = enumValue(UnitType.class, fields[1]);
            Province location = enumValue(Province.class, fields[2]);

            if (location == Province.Swi)
                throw new IllegalArgumentException("Switzerland cannot contain a unit.");

            if (type == UnitType.ARMY &&
                    (location.geography == Geography.WATER ||
                            Province.canonical(location) != location))
                throw new IllegalArgumentException(
                        "Army requires a land province without a coast suffix: " + row);

            if (type == UnitType.FLEET &&
                    location.geography == Geography.INLAND)
                throw new IllegalArgumentException(
                        "Fleet requires sea/coast; specify split coasts explicitly: " + row);

            if (!occupied.add(Province.canonical(location)))
                throw new IllegalArgumentException(
                        "More than one unit occupies " + Province.canonical(location));

            UUID id = UUID.nameUUIDFromBytes(
                    ("strategy|" + owner.name() + "|" + type.name() + "|" + location.name())
                            .getBytes(StandardCharsets.UTF_8));

            locations.put(new UnitId(id, owner, type, location), location);
        }

        Map<Province, Nation> centers = new EnumMap<>(Province.class);

        for (String row : rows(values.get("centers"))) {
            String[] fields = fields(row, 2, "PROVINCE NATION");
            Province province = Province.canonical(
                    enumValue(Province.class, fields[0]));
            Nation owner = enumValue(Nation.class, fields[1]);

            if (!province.supplyCenter)
                throw new IllegalArgumentException("Not a supply center: " + province);

            if (centers.putIfAbsent(province, owner) != null)
                throw new IllegalArgumentException("Repeated supply center: " + province);
        }

        Map<Province, Double> objectives = new EnumMap<>(Province.class);

        for (String row : rows(values.get("objectives"))) {
            String[] fields = fields(row, 2, "PROVINCE VALUE");
            Province province = Province.canonical(
                    enumValue(Province.class, fields[0]));
            double value = decimal(fields[1], "Objective", -1000, 1000);

            if (province == Province.Swi)
                throw new IllegalArgumentException("Switzerland is not an objective.");

            if (objectives.putIfAbsent(province, value) != null)
                throw new IllegalArgumentException("Repeated objective: " + province);
        }

        return new Query(
                new BoardState(locations, centers),
                moment, nation, Map.copyOf(objectives), configuration,
                decimal(values.get("centerWeight"), "Center weight", 0, 1000),
                decimal(values.get("dislodgementPenalty"), "Dislodgement penalty", 0, 1000),
                decimal(values.get("caution"), "Caution", 0, 1));

    }


    private static String bootstrapJson(String token, String ruleset, int games) {

        BoardState board = StandardGameFactory.create1901().board();

        String units = String.join("\n", board.locations().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().name()))
                .map(entry -> entry.getKey().owner().name() + " "
                        + entry.getKey().unitType().name() + " "
                        + entry.getValue().name())
                .toList());

        String centers = String.join("\n", board.owners().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .map(entry -> entry.getKey().name() + " " + entry.getValue().name())
                .toList());

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("nation", "ENGLAND");
        defaults.put("year", "1901");
        defaults.put("phase", "SPRING_MOVEMENT");
        defaults.put("policy", "REFERENCE_FILTERED");
        defaults.put("coordinationMode", "STRICT");
        defaults.put("units", units);
        defaults.put("centers", centers);
        defaults.put("objectives", "");
        defaults.put("centerWeight", "1");
        defaults.put("dislodgementPenalty", "3");
        defaults.put("caution", "0.5");
        defaults.put("minimum", "3");
        defaults.put("choices", "4");
        defaults.put("plans", "8");
        defaults.put("scenarios", "32");

        StringJoiner colors = new StringJoiner(",");
        for (Nation nation : Nation.values())
            colors.add(quote(nation.name()) + ":" + quote(Constants.nationHex(nation)));

        StringJoiner values = new StringJoiner(",");
        defaults.forEach((key, value) ->
                values.add(quote(key) + ":" + quote(value)));

        return "{\"token\":" + quote(token)
                + ",\"ruleset\":" + quote(ruleset)
                + ",\"trainingGames\":" + games
                + ",\"colors\":{" + colors + "}"
                + ",\"defaults\":{" + values + "}"
                + ",\"board\":" + BoardSnapshotJsonWriter.toJson(
                BoardSnapshotMapper.from(
                        new GameMoment(1901, GamePhase.SPRING_MOVEMENT), board))
                + "}";

    }

    private static String resultJson(
            Query query,
            MovementStrategy.Recommendation recommendation,
            String ruleset) {

        var config = query.configuration();

        String settings = String.format(Locale.ROOT,
                "%s %d | %s | %s | coordination=%s | position-only | minimum=%d choices=%d "
                        + "plans=%d scenarios=%d | center=%.3f dislodgement=%.3f "
                        + "caution=%.3f | objectives=%s | ruleset=%s",
                query.moment().gamePhase(), query.moment().year(),
                query.nation(), config.policy(), config.coordinationMode(),
                config.minimumObservations(), config.choicesPerUnit(),
                config.nationalPlanLimit(), config.opponentScenarioLimit(),
                query.centerWeight(), query.dislodgementPenalty(), query.caution(),
                query.objectives(), ruleset);

        StringBuilder out = new StringBuilder("{\"settings\":")
                .append(quote(settings))
                .append(",\"nation\":").append(quote(query.nation().name()))
                .append(",\"status\":").append(quote(recommendation.status().name()))
                .append(",\"diagnostic\":").append(quote(recommendation.diagnostic()))
                .append(",\"candidateCount\":").append(recommendation.candidatePlanCount())
                .append(",\"scenarioCount\":").append(recommendation.opponentScenarioCount())
                .append(",\"board\":").append(BoardSnapshotJsonWriter.toJson(
                        BoardSnapshotMapper.from(query.moment(), query.board())));

        var coordination = recommendation.coordination();
        out.append(",\"coordination\":{\"mode\":")
                .append(quote(coordination.mode().name()))
                .append(",\"expandedCandidates\":").append(coordination.expandedCandidates())
                .append(",\"rejectedCandidates\":").append(coordination.rejectedCandidates())
                .append(",\"omittedChoices\":").append(coordination.omittedChoices())
                .append(",\"beamTruncated\":").append(coordination.beamTruncated())
                .append(",\"reasons\":").append(stringsJson(coordination.reasons()))
                .append(",\"conditionalPlans\":{");

        boolean firstConditional = true;
        for (var entry : coordination.conditionalPlans().entrySet()) {
            if (!firstConditional)
                out.append(',');
            firstConditional = false;
            out.append(quote(entry.getKey())).append(':')
                    .append(stringsJson(entry.getValue()));
        }
        out.append("}}");

        List<String> diagnostics = new ArrayList<>();

        for (UnitId unit : recommendation.insufficientUnits())
            diagnostics.add("Insufficient: " + unit.owner() + " "
                    + unit.unitType() + " " + query.board().locationOf(unit));

        for (var entry : recommendation.evidence().entrySet()) {
            UnitId unit = entry.getKey();
            var evidence = entry.getValue();

            diagnostics.add(unit.owner() + " " + unit.unitType() + " "
                    + query.board().locationOf(unit) + ": "
                    + evidence.originalBasis() + " -> "
                    + evidence.selectedBasisBeforeFiltering()
                    + "; original observations=" + evidence.originalObservations()
                    + "; candidates=" + evidence.candidatesBeforeFiltering()
                    + " -> " + evidence.candidatesAfterPolicy());
        }

        out.append(",\"diagnostics\":[")
                .append(String.join(",", diagnostics.stream()
                        .map(StrategyBrowserApp::quote).toList()))
                .append("],\"plans\":[");

        int rank = 0;

        for (PlanEvaluation evaluation : recommendation.rankedPlans()) {
            if (rank > 0)
                out.append(',');

            String signature = Classifier.signature(query.board(), evaluation.plan().orders());
            out.append("{\"rank\":").append(++rank)
                    .append(",\"nation\":").append(quote(query.nation().name()))
                    .append(",\"signature\":").append(quote(signature))
                    .append(",\"dependencies\":").append(stringsJson(
                            coordination.conditionalPlans().getOrDefault(signature, List.of())))
                    .append(",\"score\":").append(evaluation.score())
                    .append(",\"mean\":").append(evaluation.mean())
                    .append(",\"worst\":").append(evaluation.worst())
                    .append(",\"logPreference\":").append(evaluation.plan().logPreference())
                    .append(",\"orders\":[");

            boolean first = true;
            for (Order order : evaluation.plan().orders()) {
                if (!first)
                    out.append(',');
                first = false;

                out.append("{\"text\":").append(quote(
                                OrderForm.format(order, query.board().locationOf(order.unit()))))
                        .append(",\"origin\":").append(
                                quote(query.board().locationOf(order.unit()).name()))
                        .append(",\"type\":").append(quote(order.orderType().name()))
                        .append(",\"target\":").append(provinceJson(order.target()))
                        .append(",\"auxiliaryTarget\":")
                        .append(provinceJson(order.auxiliaryTarget()))
                        .append('}');
            }

            out.append("]}");
        }

        return out.append("]}").toString();

    }


    private static List<String> rows(String text) {
        return Arrays.stream(text.split("[;\\r\\n]+"))
                .map(String::trim)
                .filter(row -> !row.isEmpty())
                .toList();
    }

    private static String[] fields(String row, int count, String expected) {
        String[] fields = row.split("\\s+");
        if (fields.length != count)
            throw new IllegalArgumentException("Expected " + expected + ", found: " + row);
        return fields;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text) {
        for (E value : type.getEnumConstants())
            if (value.name().equalsIgnoreCase(text.trim()))
                return value;
        throw new IllegalArgumentException(
                "Unknown " + type.getSimpleName() + ": " + text);
    }

    private static int integer(
            Map<String, String> values, String key, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(values.get(key).trim());
            if (value >= minimum && value <= maximum)
                return value;
        } catch (NumberFormatException ignored) {
            // Produce a field-specific message below.
        }
        throw new IllegalArgumentException(
                key + " must be an integer between " + minimum + " and " + maximum);
    }

    private static double decimal(
            String text, String name, double minimum, double maximum) {
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isFinite(value) && value >= minimum && value <= maximum)
                return value;
        } catch (NumberFormatException ignored) {
            // Produce a field-specific message below.
        }
        throw new IllegalArgumentException(
                name + " must be finite and between " + minimum + " and " + maximum);
    }

    private static String provinceJson(Province province) {
        return quote(province == null ? null : province.name());
    }

    private static String stringsJson(List<String> values) {
        return "[" + String.join(",", values.stream()
                .map(StrategyBrowserApp::quote).toList()) + "]";
    }

    private static String quote(String value) {
        if (value == null)
            return "null";
        StringBuilder out = new StringBuilder();
        appendString(out, value);
        return out.toString();
    }

    private static Asset asset(Path path, String type) throws IOException {
        return new Asset(type + "; charset=utf-8", Files.readAllBytes(path));
    }

    private static void sendError(
            HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                ("{\"error\":" + quote(message == null ? "Request failed." : message) + "}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void send(
            HttpExchange exchange, int status, String type, byte[] bytes)
            throws IOException {

        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "object-src 'none'; frame-ancestors 'none'; form-action 'self'");

        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);

    }

    private record Asset(String type, byte[] bytes) { }

    private record Query(
            BoardState board,
            GameMoment moment,
            Nation nation,
            Map<Province, Double> objectives,
            MovementStrategy.Configuration configuration,
            double centerWeight,
            double dislodgementPenalty,
            double caution) { }

}