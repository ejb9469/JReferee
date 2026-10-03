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
            "centerWeight", "dislodgementPenalty", "caution", "tacticalBiasWeight",
            "minimum", "choices", "plans", "scenarios");
    private static final Set<String> OPTIONAL_FORM_FIELDS = optionalFields();

    private static Set<String> optionalFields() {
        Set<String> fields = new HashSet<>(Set.of("compareAlternatives", "humanWeight", "globalValues"));
        for (Nation nation : Nation.values()) {
            fields.add("adjustments." + nation.name());
            fields.add("regions." + nation.name());
        }
        return Set.copyOf(fields);
    }

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
                "/ui/strategy.css",
                asset(ui.resolve("strategy.css"), "text/css"),
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
                                query.caution(), query.configuration().tacticalBiasWeight(),
                                query.configuration().scoringConfiguration()),
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

            if (!FORM_FIELDS.contains(key) && !OPTIONAL_FORM_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown form field: " + key);

            if (values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Duplicate form field: " + key);
        }

        if (!values.keySet().containsAll(FORM_FIELDS))
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
                enumValue(CoordinationMode.class, values.get("coordinationMode")),
                decimal(values.get("tacticalBiasWeight"), "Tactical bias weight", 0, 1000),
                parseScoring(values));

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
            if (locations.size() > 75)
                throw new IllegalArgumentException("At most 75 active units are supported.");
        }
        long ownUnits = locations.keySet().stream().filter(unit -> unit.owner() == nation).count();
        long geographyWork = (long) plans * scenarios * ownUnits
                * configuration.scoringConfiguration().profile(nation).objectives().size();
        if (geographyWork > 65_536)
            throw new IllegalArgumentException("Regional work budget exceeded (plans × scenarios × own units × goals ≤65536).");

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
            Province province = scoringProvince(fields[0]);
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
                decimal(values.get("caution"), "Caution", 0, 1),
                booleanValue(values.getOrDefault("compareAlternatives", "false")));

    }


    private static ScoringConfiguration parseScoring(Map<String, String> values) {
        Map<Nation, ScoringConfiguration.NationProfile> profiles = new EnumMap<>(Nation.class);
        int goals = 0, targets = 0;
        for (Nation nation : Nation.values()) {
            var adjustments = provinceValues(values.getOrDefault("adjustments." + nation.name(), ""));
            List<ScoringConfiguration.RegionalObjective> objectives = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (String row : rows(values.getOrDefault("regions." + nation.name(), ""))) {
                String[] parts = fields(row, 6, "NAME TARGETS PRIORITY TYPES HORIZON DECAY");
                if (!parts[0].matches("[A-Za-z0-9_-]{1,40}") || !names.add(parts[0]))
                    throw new IllegalArgumentException("Regional objective names must be unique, 1–40 letters/digits/_/-.");
                Set<Province> provinces = EnumSet.noneOf(Province.class);
                for (String target : parts[1].split(",", -1)) {
                    Province province = regionTarget(target);
                    Province canonical = Province.canonical(province);
                    if (provinces.contains(province)
                            || (canonical != province && provinces.contains(canonical))
                            || (canonical == province && provinces.stream()
                            .anyMatch(existing -> Province.canonical(existing) == canonical)))
                        throw new IllegalArgumentException("Repeated regional target: " + target);
                    provinces.add(province);
                }
                goals++;
                targets += provinces.size();
                if (objectives.size() >= 8 || provinces.size() > 16 || goals > 32 || targets > 128)
                    throw new IllegalArgumentException("Profile budget: at most 8 goals/nation, 32 total goals, 16 targets/goal, 128 total targets.");
                double priority = decimal(parts[2], "Regional priority", 0, 1000);
                if (priority == 0)
                    throw new IllegalArgumentException("Regional priority must be positive.");
                objectives.add(new ScoringConfiguration.RegionalObjective(
                        parts[0], provinces, priority, unitTypes(parts[3]),
                        integer(Map.of("horizon", parts[4]), "horizon", 1, 12),
                        decimal(parts[5], "Regional decay", 0, 1)));
            }
            profiles.put(nation, new ScoringConfiguration.NationProfile(adjustments, objectives));
        }
        return new ScoringConfiguration(
                decimal(values.getOrDefault("humanWeight", "0"), "Human preference weight", 0, 1000),
                provinceValues(values.getOrDefault("globalValues", "")), profiles);
    }

    private static Map<Province, ScoringConfiguration.ProvinceValue> provinceValues(String text) {
        Map<Province, ScoringConfiguration.ProvinceValue> values = new EnumMap<>(Province.class);
        for (String row : rows(text)) {
            String[] parts = row.replace('=', ' ').replace('/', ' ').split("\\s+");
            if (parts.length < 2 || parts.length > 3)
                throw new IllegalArgumentException("Expected PROVINCE=VALUE[/ARMY,FLEET]: " + row);
            Province province = scoringProvince(parts[0]);
            var value = new ScoringConfiguration.ProvinceValue(
                    decimal(parts[1], "Province value", -1000, 1000),
                    unitTypes(parts.length == 3 ? parts[2] : "ARMY,FLEET"));
            if (values.putIfAbsent(province, value) != null)
                throw new IllegalArgumentException("Repeated province value: " + province);
        }
        return Map.copyOf(values);
    }

    private static Province scoringProvince(String text) {
        Province province = enumValue(Province.class, text);
        if (province == Province.Swi || Province.canonical(province) != province)
            throw new IllegalArgumentException("Scoring requires a playable canonical province, not a coast alias: " + text);
        return province;
    }

    private static Province regionTarget(String text) {
        Province province = enumValue(Province.class, text);
        if (province == Province.Swi)
            throw new IllegalArgumentException("Switzerland is not a regional target.");
        return province;
    }

    private static Set<UnitType> unitTypes(String text) {
        Set<UnitType> types = EnumSet.noneOf(UnitType.class);
        for (String value : text.split(",", -1))
            if (!types.add(enumValue(UnitType.class, value)))
                throw new IllegalArgumentException("Repeated unit type: " + text);
        return Set.copyOf(types);
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
        defaults.put("tacticalBiasWeight", "5");
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
                        + "caution=%.3f tactical-bias=%.3f | objectives=%s | ruleset=%s",
                query.moment().gamePhase(), query.moment().year(),
                query.nation(), config.policy(), config.coordinationMode(),
                config.minimumObservations(), config.choicesPerUnit(),
                config.nationalPlanLimit(), config.opponentScenarioLimit(),
                query.centerWeight(), query.dislodgementPenalty(), query.caution(),
                config.tacticalBiasWeight(),
                query.objectives(), ruleset);

        StringBuilder out = new StringBuilder("{\"settings\":")
                .append(quote(settings))
                .append(",\"context\":").append(contextJson(query, ruleset))
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
                .append(",\"geographyGuided\":").append(coordination.geographyGuided())
                .append(",\"replenishedChoices\":").append(coordination.replenishedChoices())
                .append(",\"guidanceChoicesUnexamined\":").append(coordination.guidanceChoicesUnexamined())
                .append(",\"geographicChoiceLimit\":").append(CoordinatedOrders.GEOGRAPHIC_CHOICE_LIMIT)
                .append(",\"beamTruncated\":").append(coordination.beamTruncated())
                .append(",\"dependencyAssignmentsExamined\":")
                .append(coordination.dependencyAssignmentsExamined())
                .append(",\"dependencySearchTruncated\":")
                .append(coordination.dependencySearchTruncated())
                .append(",\"foreignAssignmentLimit\":")
                .append(CoordinatedOrders.FOREIGN_ASSIGNMENT_LIMIT)
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
        List<PlanEvaluation> baseRanking = recommendation.rankedPlans().stream()
                .sorted(Comparator.comparingDouble(PlanEvaluation::baseScore).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (PlanEvaluation value) -> value.plan().logPreference()).reversed())
                        .thenComparing(value -> Classifier.signature(
                                query.board(), value.plan().orders())))
                .toList();
        int comparisonsRemaining = TacticalAnalysis.MAX_COMPARISONS;
        int comparisonWorkRemaining = TacticalAnalysis.MAX_SCENARIO_EVALUATIONS;

        for (PlanEvaluation evaluation : recommendation.rankedPlans()) {
            if (rank > 0)
                out.append(',');

            String signature = Classifier.signature(query.board(), evaluation.plan().orders());
            var tactical = TacticalAnalysis.analyze(
                    query.board(), query.moment(), evaluation.plan(),
                    recommendation.selectedPredictions(), recommendation.scenarioOrders(),
                    query.objectives(), new OutcomeEvaluator(query.centerWeight(),
                            query.dislodgementPenalty(), query.caution(), config.tacticalBiasWeight(),
                            config.scoringConfiguration()),
                    new MovementProcessor(), query.compareAlternatives(),
                    comparisonsRemaining, comparisonWorkRemaining);
            comparisonsRemaining -= tactical.comparisonsEvaluated();
            comparisonWorkRemaining -= tactical.scenarioEvaluations();
            out.append("{\"rank\":").append(++rank)
                    .append(",\"nation\":").append(quote(query.nation().name()))
                    .append(",\"signature\":").append(quote(signature))
                    .append(",\"dependencies\":").append(stringsJson(
                            coordination.conditionalPlans().getOrDefault(signature, List.of())))
                    .append(",\"score\":").append(evaluation.score())
                    .append(",\"baseScore\":").append(evaluation.baseScore())
                    .append(",\"baseRank\":").append(baseRanking.indexOf(evaluation) + 1)
                    .append(",\"penaltyWeight\":").append(evaluation.penaltyWeight())
                    .append(",\"offendingMoveCount\":").append(evaluation.offendingMoveCount())
                    .append(",\"penaltyTotal\":").append(evaluation.penaltyTotal())
                    .append(",\"mean\":").append(evaluation.mean())
                    .append(",\"worst\":").append(evaluation.worst())
                    .append(",\"shapedMean\":").append(evaluation.shapedMean())
                    .append(",\"shapedWorst\":").append(evaluation.shapedWorst())
                    .append(",\"shapedScore\":").append(evaluation.shapedScore())
                    .append(",\"humanWeight\":").append(evaluation.humanWeight())
                    .append(",\"humanContribution\":").append(evaluation.humanContribution())
                    .append(",\"humanPreference\":").append(humanJson(query.board(), evaluation.humanPreference()))
                    .append(",\"scoringConfiguration\":").append(scoringJson(evaluation.scoringConfiguration()))
                    .append(",\"logPreference\":").append(evaluation.plan().logPreference())
                    .append(",\"orders\":").append(ordersJson(query.board(), evaluation.plan().orders()))
                    .append(",\"scenarios\":").append(scenariosJson(query.board(), evaluation))
                    .append(",\"dependencySets\":").append(dependenciesJson(query, recommendation, signature))
                    .append(",\"findings\":").append(findingsJson(query.board(), tactical))
                    .append(",\"comparisons\":").append(comparisonsJson(query.board(), tactical))
                    .append('}');
        }

        return out.append("]}").toString();

    }


    private static String ordersJson(BoardState board, List<Order> orders) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Order order : orders)
            out.add(orderJson(board, order));
        return out.toString();
    }

    private static String findingsJson(BoardState board, TacticalAnalysis.Result result) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (var comparison : result.comparisons()) {
            var warning = comparison.warning();
            out.add("{\"id\":" + quote(warning.id())
                    + ",\"principleId\":" + quote(warning.principleId())
                    + ",\"category\":" + quote(warning.category().name())
                    + ",\"severity\":" + quote(warning.severity().name())
                    + ",\"orders\":" + ordersJson(board, warning.orders())
                    + ",\"explanation\":" + quote(warning.explanation())
                    + ",\"rejected\":" + warning.rejected()
                    + ",\"penalty\":" + warning.penalty()
                    + ",\"suggestedAlternative\":" + (warning.suggestedAlternative() == null
                    ? "null" : orderJson(board, warning.suggestedAlternative()))
                    + ",\"evaluatedStatus\":" + quote(warning.evaluatedStatus().name()) + "}");
        }
        return out.toString();
    }

    private static String comparisonsJson(BoardState board, TacticalAnalysis.Result result) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (var comparison : result.comparisons()) {
            var provenance = comparison.provenance();
            StringJoiner deltas = new StringJoiner(",", "[", "]");
            comparison.scenarioDeltas().forEach((name, delta) ->
                    deltas.add("{\"name\":" + quote(name) + ",\"delta\":" + delta
                            + ",\"relation\":" + quote(delta > 0 ? "BETTER" : delta < 0 ? "WORSE" : "EQUAL")
                            + ",\"original\":" + comparison.baselineEvaluation().scenarioScores().get(name)
                            + ",\"alternative\":" + comparison.alternativeEvaluation().scenarioScores().get(name) + "}"));
            out.add("{\"findingId\":" + quote(comparison.warning().id())
                    + ",\"status\":" + quote(comparison.warning().evaluatedStatus().name())
                    + ",\"diagnostic\":" + quote(comparison.diagnostic())
                    + ",\"alternativeOrders\":" + (comparison.alternative() == null ? "null"
                    : ordersJson(board, comparison.alternative().orders()))
                    + ",\"logPreference\":" + (comparison.alternative() == null ? "null"
                    : comparison.alternative().logPreference())
                    + ",\"provenance\":" + (provenance == null ? "null"
                    : "{\"basis\":" + quote(provenance.basis())
                    + ",\"observations\":" + provenance.observations()
                    + ",\"orderCount\":" + provenance.orderCount() + "}")
                    + ",\"scenarioDeltas\":" + deltas
                    + ",\"meanDelta\":" + comparison.meanDelta()
                    + ",\"worstDelta\":" + comparison.worstDelta()
                    + ",\"scoreDelta\":" + comparison.scoreDelta()
                    + ",\"adjustedScoreDelta\":" + comparison.adjustedScoreDelta()
                    + ",\"positionalDelta\":" + comparison.positionalDelta()
                    + ",\"humanDelta\":" + comparison.humanDelta()
                    + ",\"penaltyDelta\":" + comparison.penaltyDelta() + "}");
        }
        return out.toString();
    }

    private static String orderJson(BoardState board, Order order) {
        return "{\"text\":" + quote(OrderForm.format(order, board.locationOf(order.unit())))
                + ",\"unit\":" + quote(order.unit().value().toString())
                + ",\"nation\":" + quote(order.unit().owner().name())
                + ",\"unitType\":" + quote(order.unitType().name())
                + ",\"origin\":" + quote(board.locationOf(order.unit()).name())
                + ",\"type\":" + quote(order.orderType().name())
                + ",\"target\":" + provinceJson(order.target())
                + ",\"auxiliaryTarget\":" + provinceJson(order.auxiliaryTarget()) + "}";
    }

    private static String scenariosJson(BoardState board, PlanEvaluation evaluation) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (ScenarioEvaluation scenario : evaluation.scenarioDetails().values())
            out.add("{\"name\":" + quote(scenario.name())
                    + ",\"opponentOrders\":" + ordersJson(board, scenario.opponentOrders())
                    + ",\"objectiveDelta\":" + scenario.objectiveDelta()
                    + ",\"centerPositionDelta\":" + scenario.centerPositionDelta()
                    + ",\"dislodgementPenalty\":" + scenario.dislodgementPenalty()
                    + ",\"dislodgedUnits\":" + scenario.dislodgedUnits()
                    + ",\"score\":" + scenario.score()
                    + ",\"provinceContribution\":" + scenario.provinceContribution()
                    + ",\"regionalContribution\":" + scenario.regionalContribution()
                    + ",\"augmentedScore\":" + scenario.augmentedScore()
                    + ",\"shaping\":" + shapingJson(board, scenario.shaping()) + "}");
        return out.toString();
    }

    private static String dependenciesJson(
            Query query, MovementStrategy.Recommendation recommendation, String signature) {
        ForeignDependencies dependencies = recommendation.coordination()
                .foreignDependencies().get(signature);
        if (dependencies == null)
            return "{\"checked\":false,\"alternatives\":[],\"confirmed\":false}";
        var coverage = dependencies.coverage(recommendation.scenarioOrders());
        StringJoiner alternatives = new StringJoiner(",", "[", "]");
        for (var conjunction : dependencies.alternatives()) {
            StringJoiner requirements = new StringJoiner(",", "[", "]");
            for (var requirement : conjunction)
                requirements.add("{\"unit\":" + quote(requirement.unit().value().toString())
                        + ",\"nation\":" + quote(requirement.unit().owner().name())
                        + ",\"unitType\":" + quote(requirement.unit().unitType().name())
                        + ",\"origin\":" + quote(requirement.origin().name())
                        + ",\"constraint\":" + quote(requirement.constraint().name())
                        + ",\"type\":" + (requirement.orderType() == null ? "null"
                        : quote(requirement.orderType().name()))
                        + ",\"target\":" + provinceJson(requirement.target())
                        + ",\"auxiliaryTarget\":" + provinceJson(requirement.auxiliaryTarget())
                        + ",\"affectedFriendlyOrders\":" + ordersJson(query.board(), requirement.affectedFriendlyOrders())
                        + ",\"phase\":" + quote(query.moment().gamePhase().name()) + "}");
            List<String> satisfying = new TreeMap<>(recommendation.scenarioOrders()).entrySet().stream()
                    .filter(entry -> conjunction.stream().allMatch(
                            requirement -> requirement.satisfiedBy(entry.getValue())))
                    .map(Map.Entry::getKey).toList();
            alternatives.add("{\"requirements\":" + requirements
                    + ",\"satisfyingScenarios\":" + stringsJson(satisfying) + "}");
        }
        return "{\"checked\":true,\"alternatives\":" + alternatives
                + ",\"truncated\":" + dependencies.truncated()
                + ",\"satisfyingScenarios\":" + stringsJson(coverage.satisfyingScenarios())
                + ",\"scenarioCount\":" + coverage.scenarioCount()
                + ",\"satisfiedCount\":" + coverage.satisfiedCount()
                + ",\"confirmed\":false}";
    }

    private static String contextJson(Query query, String ruleset) {
        var config = query.configuration();
        StringJoiner objectives = new StringJoiner(",", "{", "}");
        query.objectives().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> objectives.add(quote(entry.getKey().name()) + ":" + entry.getValue()));
        return "{\"year\":" + query.moment().year()
                + ",\"phase\":" + quote(query.moment().gamePhase().name())
                + ",\"nation\":" + quote(query.nation().name())
                + ",\"ruleset\":" + quote(ruleset)
                + ",\"positionOnly\":true"
                + ",\"policy\":" + quote(config.policy().name())
                + ",\"coordinationMode\":" + quote(config.coordinationMode().name())
                + ",\"objectives\":" + objectives
                + ",\"centerWeight\":" + query.centerWeight()
                + ",\"dislodgementPenalty\":" + query.dislodgementPenalty()
                + ",\"caution\":" + query.caution()
                + ",\"tacticalBiasWeight\":" + config.tacticalBiasWeight()
                + ",\"scoringConfiguration\":" + scoringJson(config.scoringConfiguration())
                + ",\"equalScenarioWeights\":true"
                + ",\"formula\":\"raw=(1-caution)*mean+caution*worst; shaped=(1-caution)*shapedMean+caution*shapedWorst; final=shaped-tacticalWeight*offendingMoveCount+humanWeight*humanPreference\""
                + ",\"limits\":{\"minimumObservations\":" + config.minimumObservations()
                + ",\"choicesPerUnit\":" + config.choicesPerUnit()
                + ",\"nationalPlanLimit\":" + config.nationalPlanLimit()
                + ",\"opponentScenarioLimit\":" + config.opponentScenarioLimit()
                + ",\"evaluationBudget\":512"
                + ",\"regionalWorkBudget\":65536"
                + ",\"geographicChoiceLimit\":" + CoordinatedOrders.GEOGRAPHIC_CHOICE_LIMIT
                + ",\"comparisonLimit\":" + TacticalAnalysis.MAX_COMPARISONS
                + ",\"comparisonScenarioEvaluationBudget\":" + TacticalAnalysis.MAX_SCENARIO_EVALUATIONS
                + ",\"foreignAssignmentLimit\":" + CoordinatedOrders.FOREIGN_ASSIGNMENT_LIMIT + "}"
                + ",\"compareAlternatives\":" + query.compareAlternatives() + "}";
    }

    private static String scoringJson(ScoringConfiguration scoring) {
        StringJoiner profiles = new StringJoiner(",", "{", "}");
        for (Nation nation : Nation.values()) {
            var profile = scoring.profile(nation);
            StringJoiner objectives = new StringJoiner(",", "[", "]");
            for (var objective : profile.objectives())
                objectives.add("{\"name\":" + quote(objective.name())
                        + ",\"targets\":" + enumNames(objective.targets())
                        + ",\"priority\":" + objective.priority()
                        + ",\"unitTypes\":" + enumNames(objective.unitTypes())
                        + ",\"horizon\":" + objective.horizon()
                        + ",\"decay\":" + objective.decay() + "}");
            profiles.add(quote(nation.name()) + ":{\"adjustments\":"
                    + valuesJson(profile.adjustments()) + ",\"objectives\":" + objectives + "}");
        }
        return "{\"humanWeight\":" + scoring.humanWeight()
                + ",\"globalValues\":" + valuesJson(scoring.globalValues())
                + ",\"nationProfiles\":" + profiles + "}";
    }

    private static String valuesJson(Map<Province, ScoringConfiguration.ProvinceValue> values) {
        StringJoiner out = new StringJoiner(",", "{", "}");
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                out.add(quote(entry.getKey().name()) + ":{\"value\":" + entry.getValue().value()
                        + ",\"unitTypes\":" + enumNames(entry.getValue().unitTypes()) + "}"));
        return out.toString();
    }

    private static String enumNames(Collection<? extends Enum<?>> values) {
        return stringsJson(values.stream().map(Enum::name).sorted().toList());
    }

    private static String unitJson(BoardState board, UnitId unit) {
        return "{\"unit\":" + quote(unit.value().toString()) + ",\"nation\":" + quote(unit.owner().name())
                + ",\"unitType\":" + quote(unit.unitType().name())
                + ",\"origin\":" + provinceJson(board.locationOf(unit)) + "}";
    }

    private static String humanJson(BoardState board, HumanPreference preference) {
        StringJoiner units = new StringJoiner(",", "[", "]");
        for (var unit : preference.units())
            units.add("{\"identity\":" + unitJson(board, unit.unit())
                    + ",\"order\":" + (unit.order() == null ? "null" : orderJson(board, unit.order()))
                    + ",\"selectedCount\":" + unit.selectedCount()
                    + ",\"denominator\":" + unit.denominator()
                    + ",\"basis\":" + quote(unit.basis())
                    + ",\"logFrequency\":" + unit.logFrequency()
                    + ",\"available\":" + unit.available()
                    + ",\"diagnostic\":" + quote(unit.diagnostic()) + "}");
        return "{\"available\":" + preference.available() + ",\"score\":" + preference.score()
                + ",\"diagnostic\":" + quote(preference.available() ? "" : "Complete selected evidence unavailable; no inferred frequencies.")
                + ",\"units\":" + units + "}";
    }

    private static String shapingJson(BoardState board, PositionShaping.Breakdown shaping) {
        StringJoiner units = new StringJoiner(",", "[", "]");
        for (var unit : shaping.units())
            units.add("{\"identity\":" + unitJson(board, unit.unit())
                    + ",\"before\":" + provinceJson(unit.before()) + ",\"after\":" + provinceJson(unit.after())
                    + ",\"beforeValue\":" + unit.beforeValue() + ",\"afterValue\":" + unit.afterValue()
                    + ",\"contribution\":" + unit.contribution() + ",\"surviving\":" + unit.surviving() + "}");
        StringJoiner objectives = new StringJoiner(",", "[", "]");
        for (var objective : shaping.objectives()) {
            StringJoiner potentials = new StringJoiner(",", "[", "]");
            for (var unit : objective.units())
                potentials.add("{\"identity\":" + unitJson(board, unit.unit())
                        + ",\"beforeDistance\":" + unit.beforeDistance() + ",\"afterDistance\":" + unit.afterDistance()
                        + ",\"beforePotential\":" + unit.beforePotential() + ",\"afterPotential\":" + unit.afterPotential() + "}");
            objectives.add("{\"name\":" + quote(objective.name())
                    + ",\"beforePotential\":" + objective.beforePotential()
                    + ",\"afterPotential\":" + objective.afterPotential()
                    + ",\"contribution\":" + objective.contribution() + ",\"units\":" + potentials + "}");
        }
        return "{\"provinceContribution\":" + shaping.provinceContribution()
                + ",\"regionalContribution\":" + shaping.regionalContribution()
                + ",\"units\":" + units + ",\"objectives\":" + objectives + "}";
    }

    private static boolean booleanValue(String value) {
        return switch (value) {
            case "true", "on" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Expected a boolean comparison option.");
        };
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
            double caution,
            boolean compareAlternatives) { }

}