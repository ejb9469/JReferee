package testing.selfcheck;

import analysis.tactics.*;
import domain.*;
import game.*;
import io.catalog.*;
import io.persistence.SQLiteCatalogStore;
import parsing.diplobn.*;
import phase.Order;
import phase.UnitId;
import ui.GameInvestigationJsonWriter;
import ui.GameBrowserServer;

import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Public DiploBN parser -> structural browser JSON integration checks.
 * Enum and default-registration assertions grow with the constructor catalogue.
 * Historical DBN outcomes never become structural adjudication evidence.
 */
public final class GameInvestigationIntegrationSelfCheck {
    private static int checks;
    private static final Set<EvidenceCapability> AVAILABLE = EnumSet.of(
            EvidenceCapability.MOVEMENT_POSITION, EvidenceCapability.KNOWN_MOVEMENT_ORDERS,
            EvidenceCapability.MAP_ADJACENCY);

    private GameInvestigationIntegrationSelfCheck() { }

    public static void main(String[] args) throws Exception {
        checks = 0;
        parsedMovement();
        nonmovement();
        malformedSubmissions();
        capabilityGating();
        readOnlyHttp();
        System.out.println("Game investigation integration self-check passed: " + checks
                + " checks; " + TacticKind.values().length + " enum kinds; "
                + new DetectiveAgency().kinds().size() + " default registered kinds.");
    }

    private static String movement(String orders) {
        return """
                {"GamePhases":[{"Phase":19021,"Status":"AwaitingOrders",
                "Units":{"France":[["A","Par"],["A","Gas"],["A","Mar"]],
                "Germany":[["A","Mun"]]},"Orders":{"France":%s}}]}
                """.formatted(orders);
    }

    private static DiploBNPhase parse(String source) {
        return new DiploBNParser().parse(source).phases().getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> serialize(DiploBNPhase phase) {
        return object(JsonReader.read(GameInvestigationJsonWriter.toJson(phase)));
    }

    private static TacticalContext context(DiploBNPhase phase) {
        Map<UnitId, TacticalContext.KnownOrder> evidence = new LinkedHashMap<>();
        for (Order order : phase.movementOrders())
            evidence.put(order.unit(), new TacticalContext.KnownOrder(
                    order, TacticalContext.Provenance.SUBMITTED));
        return new TacticalContext("diplobn-standard-browser-structural-v1",
                new GameMoment(phase.sourcePhase() / 10, phase.gamePhase()), phase.board(), evidence);
    }

    private static void parsedMovement() {
        String orders = """
                [["Par",["m","Bur"],"s"],["Gas",["sm","Par","Bur"],"s"],
                ["Mar",["sm","Par","Bur"],"s"]]
                """;
        DiploBNPhase phase = parse(movement(orders));
        require(phase.gamePhase() == GamePhase.SPRING_MOVEMENT, "Public parser movement phase");
        require(phase.movementOrders().size() == 3 && phase.resolutions().size() == 3,
                "Submissions and outcomes parsed separately");
        String json = GameInvestigationJsonWriter.toJson(phase);
        DiploBNPhase failed = parse(movement(orders.replace("\"s\"", "[\"f\",\"Bounced\"]")));
        require(failed.resolutions().size() == 3, "Failure annotations actually parsed");
        require(GameInvestigationJsonWriter.toJson(failed).equals(json),
                "DBN outcomes leaked into structural results");
        DiploBNPhase unannotated = parse(movement("""
                [["Par",["m","Bur"]],["Gas",["sm","Par","Bur"]],["Mar",["sm","Par","Bur"]]]
                """));
        require(unannotated.resolutions().isEmpty(), "Unannotated parser fixture");
        require(GameInvestigationJsonWriter.toJson(unannotated).equals(json),
                "Missing outcome annotations changed structural results");
        checkReport(phase);

        DiploBNPhase partial = parse(movement("""
                [["Gas",["sm","Par","Bur"]],["Mar",["sm","Par","Bur"]]]
                """));
        Map<String, Object> partialJson = checkReport(partial);
        require(number(partialJson.get("knownOrders")) == 2
                        && number(partialJson.get("activeUnits")) == 4
                        && array(partialJson.get("unknownUnits")).size() == 2,
                "Missing submissions must remain unknown, never HOLD");
        List<Map<String, Object>> supports = array(partialJson.get("findings")).stream()
                .map(GameInvestigationIntegrationSelfCheck::object)
                .filter(finding -> finding.get("kind").equals(TacticKind.SUPPORT_TO_MOVE.name())).toList();
        require(supports.size() == 2, "Partial parser fixture lost support relationships");
        for (Map<String, Object> support : supports) {
            require(Boolean.FALSE.equals(support.get("complete"))
                            && array(support.get("missingOrders")).size() == 1, "Missing local prerequisite");
            Map<String, Object> recipient = array(support.get("participants")).stream()
                    .map(GameInvestigationIntegrationSelfCheck::object)
                    .filter(participant -> participant.get("role").equals("SUPPORTED_UNIT"))
                    .findFirst().orElseThrow();
            require(recipient.get("order") == null && recipient.get("provenance") == null,
                    "Unknown participant must serialize null order/provenance");
        }
        DiploBNPhase zero = parse(movement("[]"));
        Map<String, Object> empty = checkReport(zero);
        require(array(empty.get("findings")).isEmpty(), "Unknown-only board invented findings");
        require(number(empty.get("knownOrders")) == 0 && array(empty.get("unknownUnits")).size() == 4,
                "Unknown-only context changed submission counts");
        require(array(empty.get("coverage")).stream().map(GameInvestigationIntegrationSelfCheck::object)
                        .filter(entry -> Boolean.TRUE.equals(entry.get("implemented")))
                        .allMatch(entry -> number(entry.get("findingCount")) == 0),
                "Registered zero-finding kinds must remain in browser selection coverage");

        DiploBNPhase coasts = parse("""
                {"GamePhases":[{"Phase":19021,"Status":"AwaitingOrders",
                "Units":{"France":[["F",["Spa","nc"]],["F","Por"]]},
                "Orders":{"France":[["Spa",["m","Por"]],["Por",["m",["Spa","sc"]]]]}}]}
                """);
        Map<String, Object> coastJson = checkReport(coasts);
        require(coasts.board().locations().containsValue(Province.SpaNC)
                        && coasts.movementOrders().stream().anyMatch(order -> order.target() == Province.SpaSC),
                "Parser discarded exact source coasts");
        require(array(coastJson.get("findings")).stream().map(GameInvestigationIntegrationSelfCheck::object)
                        .anyMatch(finding -> finding.get("kind").equals("HEAD_TO_HEAD_CANDIDATE")),
                "Canonical coast relation lost through parser/serializer");
    }

    private static Map<String, Object> checkReport(DiploBNPhase phase) {
        Map<String, Object> json = serialize(phase);
        require(json.get("status").equals("EVALUATED"), "Movement report not evaluated: " + json);
        require(json.get("coverageScope").equals("COMPLETE_INVESTIGATION"), "Wrong browser coverage scope");
        DetectiveAgency agency = new DetectiveAgency();
        InvestigationReport report = agency.investigateReport(context(phase));
        List<Object> coverage = array(json.get("coverage"));
        require(coverage.size() == TacticKind.values().length, "Not all enum kinds serialized");
        Set<TacticKind> seen = EnumSet.noneOf(TacticKind.class);
        Set<TacticKind> selectable = EnumSet.noneOf(TacticKind.class);
        for (int index = 0; index < coverage.size(); index++) {
            Map<String, Object> entry = object(coverage.get(index));
            TacticKind kind = TacticKind.valueOf((String) entry.get("kind"));
            require(seen.add(kind) && kind == TacticKind.values()[index],
                    "Coverage duplicated/reordered kind");
            InvestigationCoverage.KindCoverage expected = report.coverage().byKind().get(index);
            require(entry.get("status").equals(expected.status().name())
                            && entry.get("implemented").equals(expected.implemented())
                            && entry.get("applicable").equals(expected.applicable())
                            && number(entry.get("findingCount")) == expected.findingCount(),
                    "Serialized coverage differs for " + kind);
            Set<String> missing = new HashSet<>();
            for (Object capability : array(entry.get("missingEvidence"))) missing.add((String) capability);
            require(missing.equals(names(expected.missingEvidence())), "Missing capability serialization");
            if (Boolean.TRUE.equals(entry.get("implemented"))) selectable.add(kind);
        }
        require(seen.equals(EnumSet.allOf(TacticKind.class)), "Enum coverage incomplete");
        require(selectable.equals(new HashSet<>(agency.kinds())),
                "Browser's implemented-kind selection must exactly match default constructor catalogue");
        List<Object> findings = array(json.get("findings"));
        require(findings.size() == report.size(), "Serializer dropped findings");
        for (int index = 0; index < findings.size(); index++) {
            Map<String, Object> finding = object(findings.get(index));
            TacticMatch match = report.findings().get(index);
            TacticDefinition definition = TacticDefinitionRegistry.require(match.kind());
            require(number(finding.get("index")) == index
                            && finding.get("kind").equals(match.kind().name())
                            && finding.get("focus").equals(match.focus().name())
                            && finding.get("complete").equals(match.completePattern()), "Finding identity");
            require(finding.get("detectiveVersion").equals(match.detectorVersion())
                            && finding.get("definitionVersion").equals(definition.semanticVersion())
                            && finding.get("interpretation").equals(definition.interpretation().name())
                            && finding.get("semantics").equals(definition.semantics()),
                    "Definition/version contract differs from registry");
            List<Object> participants = array(finding.get("participants"));
            require(participants.size() == match.participants().size(), "Dropped participant role");
            for (int p = 0; p < participants.size(); p++) {
                Map<String, Object> participant = object(participants.get(p));
                TacticMatch.Participant expected = match.participants().get(p);
                require(participant.get("role").equals(expected.role().name())
                                && participant.get("nation").equals(expected.unit().owner().name())
                                && participant.get("province").equals(
                                phase.board().locationOf(expected.unit()).name()),
                        "Participant role/nation/current-location serialization");
            }
            require(array(finding.get("missingOrders")).size() == match.missingOrders().size(),
                    "Dropped missing orders");
        }
        return json;
    }

    private static void nonmovement() throws Exception {
        DiploBNGame parsed = new DiploBNParser().parse(Files.readString(
                Path.of("src/resources/diplobn/valid-complete-game.json")));
        require(parsed.phases().stream().anyMatch(phase -> phase.gamePhase().isRetreat()),
                "Actual parser fixture lacks retreat");
        require(parsed.phases().stream().anyMatch(phase -> phase.gamePhase().isAdjustment()),
                "Actual parser fixture lacks adjustment");
        for (DiploBNPhase phase : parsed.phases()) {
            if (phase.gamePhase().isMovement()) continue;
            Map<String, Object> json = serialize(phase);
            require(json.get("status").equals("NOT_APPLICABLE"), "Nonmovement phase coerced to movement");
            require(!json.containsKey("coverage") && !json.containsKey("findings"),
                    "Nonmovement phase presented as evaluated zero findings");
            try {
                context(phase);
                throw new AssertionError("Nonmovement context accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    private static void malformedSubmissions() {
        DiploBNPhase duplicate = parse(movement("""
                [["Par","h"],["Par",["m","Bur"]]]
                """));
        require(duplicate.movementOrders().size() == 2, "Parser fixture must retain duplicate submissions");
        Map<String, Object> json = serialize(duplicate);
        require(json.get("status").equals("INVALID_INPUT"), "Duplicate submissions silently overwritten");
        require(!json.containsKey("coverage") && !json.containsKey("findings"),
                "Rejected submissions presented as an evaluated report");
        try {
            GameInvestigationJsonWriter.toJson(null);
            throw new AssertionError("Null phase accepted");
        } catch (NullPointerException expected) {
            checks++;
        }
    }

    /**
     * Register a sentinel for every enum kind: movement-only definitions must
     * execute exactly once, evidence-gated definitions must never execute.
     * Compare to an empty registry to distinguish skipped from unsupported.
     */
    private static void capabilityGating() {
        Map<TacticKind, Integer> calls = new EnumMap<>(TacticKind.class);
        List<TacticDetector> sentinels = new ArrayList<>();
        for (TacticKind kind : TacticKind.values()) {
            TacticDefinition definition = TacticDefinitionRegistry.require(kind);
            require(definition.kind() == kind && !definition.semanticVersion().isBlank()
                            && !definition.semantics().isBlank() && !definition.requiredEvidence().isEmpty(),
                    "Incomplete enum definition");
            sentinels.add(new TacticDetector() {
                public TacticKind kind() { return kind; }
                public String version() { return "capability-sentinel-v1"; }
                public List<TacticMatch> detect(TacticalContext context) {
                    calls.merge(kind, 1, Integer::sum);
                    return List.of();
                }
            });
        }
        TacticalContext context = context(parse(movement("[]")));
        InvestigationReport gated = new DetectiveAgency(sentinels).investigateReport(context);
        InvestigationReport unsupported = new DetectiveAgency(List.of()).investigateReport(context);
        int skipped = 0;
        int evaluated = 0;
        for (var coverage : gated.coverage().byKind()) {
            Set<EvidenceCapability> missing = EnumSet.noneOf(EvidenceCapability.class);
            missing.addAll(TacticDefinitionRegistry.require(coverage.kind()).requiredEvidence());
            missing.removeAll(AVAILABLE);
            require(coverage.implemented() && coverage.findingCount() == 0
                            && coverage.missingEvidence().equals(missing), "Registered gating coverage");
            if (missing.isEmpty()) {
                evaluated++;
                require(coverage.applicable()
                                && coverage.status() == InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS
                                && calls.getOrDefault(coverage.kind(), 0) == 1,
                        "Applicable zero-finding sentinel did not run");
            } else {
                skipped++;
                require(!coverage.applicable()
                                && coverage.status() == InvestigationCoverage.Status.SKIPPED_MISSING_EVIDENCE
                                && !calls.containsKey(coverage.kind()), "Evidence-gated detective invoked");
            }
            var absent = unsupported.coverage().byKind().get(coverage.kind().ordinal());
            require(!absent.implemented() && !absent.applicable()
                            && absent.status() == InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED
                            && absent.findingCount() == 0 && absent.missingEvidence().isEmpty(),
                    "Unregistered kind conflated with skipped/evaluated");
        }
        require(skipped > 0 && evaluated > 0, "Gating domain exercised no distinction");
    }

    private static Set<String> names(Collection<? extends Enum<?>> values) {
        Set<String> names = new HashSet<>();
        for (Enum<?> value : values) names.add(value.name());
        return names;
    }

    private static void readOnlyHttp() throws Exception {
        String payload = Files.readString(Path.of("src/resources/diplobn/valid-complete-game.json"));
        DiploBNGame parsed = new DiploBNParser().parse(payload);
        Path database = Files.createTempFile(Path.of("."), "jreferee-game-investigation-", ".sqlite");
        try {
            CatalogGame stored;
            try (SQLiteCatalogStore store = new SQLiteCatalogStore(database)) {
                stored = new GameCatalog(store).catalog(new GameSourceReference(
                                GameSource.DIPLOBN, "http-selfcheck",
                                URI.create("https://diplobn.com/game/?GameID=http-selfcheck"),
                                URI.create("https://diplobn.com/game/?GameID=http-selfcheck")),
                        "HTTP fixture", "Integration", payload, SourceFingerprints.sha256Hex(payload),
                        parsed.phases().size());
            }
            byte[] before = Files.readAllBytes(database);
            try (GameBrowserServer server = new GameBrowserServer(database, 0, Path.of("src/resources/ui"));
                 HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                server.start();
                require(server.address().getAddress().isLoopbackAddress(), "HTTP server not loopback-bound");
                String base = "http://127.0.0.1:" + server.address().getPort();
                String game = "/api/game?id=" + stored.id().value();
                HttpResponse<String> response = request(client, base, game, "GET", null);
                require(response.statusCode() == 200, "Stored game HTTP endpoint not responsive");
                require(response.headers().firstValue("Cache-Control").orElse("").equals("no-store")
                                && response.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff")
                                && response.headers().firstValue("X-Frame-Options").orElse("").equals("DENY"),
                        "Read-only browser response safeguards missing");
                Map<String, Object> body = object(JsonReader.read(response.body()));
                List<Object> phases = array(body.get("phases"));
                require(phases.size() == parsed.phases().size(), "HTTP dropped source snapshots");
                for (int index = 0; index < phases.size(); index++)
                    require(object(phases.get(index)).get("investigation").equals(serialize(parsed.phases().get(index))),
                            "HTTP altered movement-only investigation or historical-evidence boundary");
                require(request(client, base, "/api/games", "GET", null).statusCode() == 200,
                        "HTTP listing unavailable");
                require(request(client, base, "/", "GET", null).statusCode() == 200,
                        "Browser asset unavailable");
                require(request(client, base, game, "GET", base).statusCode() == 200,
                        "Same-origin GET rejected");
                require(request(client, base, game, "GET", "https://example.invalid").statusCode() == 403,
                        "Foreign-origin request accepted");
                for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
                    HttpResponse<String> rejected = request(client, base, game, method, null);
                    require(rejected.statusCode() == 405
                                    && rejected.headers().firstValue("Allow").orElse("").equals("GET"),
                            "Browser accepted mutating HTTP method " + method);
                }
                for (String path : List.of("/api/game", "/api/game?id=x&id=y", "/api/game?id=x&unknown=1",
                        "/api/games?offset=-1", "/api/games?offset=not-an-integer",
                        "/api/games?q=" + "a".repeat(201)))
                    require(request(client, base, path, "GET", null).statusCode() == 400,
                            "Invalid query accepted: " + path);
                require(request(client, base, "/api/game?id=missing", "GET", null).statusCode() == 404,
                        "Missing game presented as an evaluated report");
                require(request(client, base, "/api/game?id=%27%20OR%201%3D1--", "GET", null).statusCode() == 404,
                        "Catalog ID not treated as a bound literal");
                int port = server.address().getPort();
                require(rawStatus(port, "attacker.invalid:" + port, null) == 403,
                        "Untrusted Host accepted");
                require(rawStatus(port, "localhost:" + port, "http://localhost:" + port) == 200,
                        "Trusted localhost Host/Origin rejected");
                require(rawStatus(port, "127.0.0.1:" + port, "http://localhost:" + port) == 403,
                        "Mismatched loopback Origin accepted");
            }
            require(Arrays.equals(before, Files.readAllBytes(database)),
                    "HTTP browsing or rejected requests modified catalog database");
        } finally {
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-journal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
            Files.deleteIfExists(database);
        }
    }

    private static HttpResponse<String> request(HttpClient client, String base, String path,
                                                 String method, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null) request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int rawStatus(int port, String host, String origin) throws Exception {
        // HttpClient intentionally disallows overriding Host; use a bounded loopback request.
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "GET /api/games HTTP/1.1\r\nHost: " + host + "\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Integer.parseInt(response.split(" ", 3)[1]);
        }
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
