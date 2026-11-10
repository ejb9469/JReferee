package testing.selfcheck;

import _app.AgentGameApp;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import domain.*;
import game.*;
import game.record.GameRecordBuilder;
import io.catalog.*;
import io.persistence.SQLiteCatalogStore;
import misc.scoring.ScoringSystem;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.JsonReader;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;
import ui.BoardSnapshotJsonWriter;
import ui.BoardSnapshotMapper;
import ui.GameBrowserServer;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Recorded games and database snapshots share the same read-only browser.
 * Pass --catalog to include the SQLite check with the existing JDBC dependency.
 */
public final class AgentGameBrowserSelfCheck {

    private static final Path ASSETS = Path.of("src", "resources", "ui");
    private static int checks;

    private AgentGameBrowserSelfCheck() {  }

    public static void main(String[] args) throws Exception {
        recordedGame(false);
        recordedGame(true);
        terminalInput();
        invalidArguments();
        if (args.length == 1 && args[0].equals("--catalog"))
            catalog();
        else if (args.length != 0)
            throw new IllegalArgumentException("Usage: AgentGameBrowserSelfCheck [--catalog]");
        System.out.println("AgentGameBrowserSelfCheck passed: " + checks + " checks");
    }

    private static GameRunner.Options options(int lastYear) {
        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            agents.put(nation, AgentSettings.paper(1, 1));
        return new GameRunner.Options(lastYear, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, agents);
    }

    private static GameRunner.Result fixture(boolean retreat) {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE,
                                Province.Mun, Nation.GERMANY, Province.Stp, Nation.RUSSIA,
                                Province.Mos, Nation.RUSSIA)));
        GameRunner.Options settings = options(1908);
        Game game = settings.createGame(root);
        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.fromString("12345678-1234-5678-1234-567812345678"), settings.rulesetId(), game);
        archive.resolveMovement(List.of(Order.hold(french), Order.move(german, Province.Bur),
                Order.supportMove(support, Province.Mun, Province.Bur)), moment(game));
        archive.resolveRetreats(retreat ? List.of(new RetreatOrder(french, Province.Gas)) : List.of(),
                moment(game));
        archive.resolveAdjustments(List.of(new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre),
                        new DisbandOrder(Nation.GERMANY, support),
                        new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC), new WaiveOrder(Nation.RUSSIA)),
                moment(game));
        for (int step = 0; step < 2; step++)
            archive.resolveMovement(game.board().locations().keySet().stream().map(Order::hold).toList(),
                    moment(game));
        return new GameRunner.Result(settings, root, game.snapshot(), archive.build(), GameRunner.EndReason.YEAR_LIMIT);
    }

    private static Moment moment(Game game) {
        return new Moment(game.year(), game.phase(), Instant.EPOCH);
    }

    private static void recordedGame(boolean retreat) throws Exception {
        GameRunner.Result result = fixture(retreat);
        try (GameBrowserServer server = new GameBrowserServer(result, 0, ASSETS);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.address().getPort();
            String path = "/api/game?id=" + result.record().id();
            Map<?, ?> listing = object(JsonReader.read(get(client, base, "/api/games")));
            require(number(listing.get("total")) == 1 && listing.get("autoSelect").equals(result.record().id().toString()),
                    "Recorded game must be listed and selected automatically");
            require(number(object(array(listing.get("games")).getFirst()).get("phaseCount")) == 6,
                    "Listing must include the final position");
            require(number(object(JsonReader.read(get(client, base, "/api/games?q=SEED"))).get("total")) == 1,
                    "Search must ignore case");
            require(array(object(JsonReader.read(get(client, base, "/api/games?q=absent"))).get("games")).isEmpty(),
                    "Unmatched search returned a game");
            require(array(object(JsonReader.read(get(client, base, "/api/games?offset=50"))).get("games")).isEmpty(),
                    "Pagination repeated the recorded game");

            String json = get(client, base, path);
            Map<?, ?> game = object(JsonReader.read(json));
            require(game.get("source").equals("JREFEREE") && object(game.get("scores")).size() == 7,
                    "Recorded game must retain source and scores");
            List<?> phases = array(game.get("phases"));
            require(phases.size() == result.record().resolvedPhases().size() + 1, "Missing recorded positions");
            for (int index = 0; index < result.record().resolvedPhases().size(); index++) {
                var phase = result.record().resolvedPhases().get(index);
                Map<?, ?> displayed = object(phases.get(index));
                Object expected = JsonReader.read(BoardSnapshotJsonWriter.toJson(
                        BoardSnapshotMapper.from(phase.gameMoment(), phase.boardBefore())));
                require(displayed.get("board").equals(expected), "Orders must use the pre-resolution board");
                require(displayed.get("sourcePhase") == null, "Agent phase invented a DBN source code");
            }
            Map<?, ?> movement = object(phases.getFirst());
            require(array(movement.get("orders")).stream().map(AgentGameBrowserSelfCheck::object)
                            .anyMatch(order -> order.get("nation").equals("FRANCE") && order.get("origin").equals("Bur")),
                    "Movement origin must use current location, not birth location");
            Map<?, ?> investigation = object(movement.get("investigation"));
            require(investigation.get("status").equals("EVALUATED")
                            && investigation.get("contextId").equals(result.record().rulesetId()),
                    "Recorded movement must retain structural investigations and its ruleset");
            require(object(investigation.get("resolvedEvents")).get("status").equals("DISABLED"),
                    "Viewing the record must not run a different adjudicator");
            Map<?, ?> retreats = object(phases.get(1));
            require(object(array(retreats.get("dislodged")).getFirst()).get("province").equals("Bur"),
                    "Dislodged unit origin lost");
            require(array(retreats.get("orders")).size() == (retreat ? 1 : 0), "Retreat submission changed");
            require(object(array(retreats.get("annotations")).getFirst()).get("reason")
                    .equals(retreat ? "RETREATED" : "DESTROYED_NO_ORDER"), "Recorded retreat outcome changed");
            if (retreat)
                require(object(array(retreats.get("orders")).getFirst()).get("origin").equals("Bur"),
                        "A displaced unit's retreat must not have a null origin");
            Map<?, ?> winter = object(phases.get(2));
            require(array(winter.get("orders")).stream().map(AgentGameBrowserSelfCheck::object)
                    .anyMatch(order -> "StpSC".equals(order.get("origin"))), "Build coast was lost");
            require(array(winter.get("orders")).stream().map(AgentGameBrowserSelfCheck::object)
                            .anyMatch(order -> order.get("type").equals("DESTROY") && order.get("origin").equals("Ruh")),
                    "Disband origin must be its current location");
            Map<?, ?> last = object(phases.getLast());
            require(last.get("status").equals("FINAL - YEAR_LIMIT")
                            && array(last.get("orders")).isEmpty() && array(last.get("annotations")).isEmpty(),
                    "Final board must not invent another resolved phase");
            require(last.get("board").equals(JsonReader.read(BoardSnapshotJsonWriter.toJson(
                    BoardSnapshotMapper.from(result.state().moment(), result.state().board())))), "Wrong final board");
            require(get(client, base, path).equals(json), "Repeated browsing changed the archived payload");
            for (String asset : List.of("/", "/ui/games.js", "/ui/games.css", "/ui/games-detectives.js",
                    "/ui/games-detectives.css", "/ui/openings.css", "/ui/maps/standard.svg"))
                get(client, base, asset);
            require(get(client, base, "/").contains("/ui/games.js"), "Recorded games must use the shared browser");
            for (String bad : List.of("/api/game", "/api/game?id=x&id=y", "/api/games?offset=-1",
                    "/api/games?offset=x", "/api/games?unknown=1", "/api/games?q=" + "a".repeat(201)))
                require(request(client, base, bad, "GET", null).statusCode() == 400, "Invalid query accepted");
            require(request(client, base, "/api/game?id=missing", "GET", null).statusCode() == 404, "Missing game accepted");
            require(request(client, base, path, "POST", null).statusCode() == 405, "Mutation request accepted");
            require(request(client, base, path, "GET", "https://example.invalid").statusCode() == 403,
                    "Foreign-origin request accepted");
        }
    }

    private static void terminalInput() throws Exception {
        GameRunner.Result played = fixture(false);
        GameRunner.Result terminal = new GameRunner().run(played.record().id(), played.state(), played.options());
        try (GameBrowserServer server = new GameBrowserServer(terminal, 0, ASSETS);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            Map<?, ?> json = object(JsonReader.read(get(client,
                    "http://127.0.0.1:" + server.address().getPort(), "/api/game?id=" + terminal.record().id())));
            require(array(json.get("phases")).size() == 1, "A terminal input still needs one visible board");
        }
    }

    private static void invalidArguments() throws Exception {
        for (String[] args : List.of(new String[]{"--port"}, new String[]{"--port", "-1"},
                new String[]{"--port", "65536"}, new String[]{"--unknown", "1"},
                new String[]{"7", "1903"}, new String[]{"--candidates", "0"},
                new String[]{"--iterations", "0"}, new String[]{"--movement-policy", "INVALID"})) {
            try {
                AgentGameApp.main(args);
                throw new AssertionError("Invalid launcher arguments accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    private static void catalog() throws Exception {
        String payload = Files.readString(Path.of("src/resources/diplobn/valid-complete-game.json"));
        var parsed = new DiploBNParser().parse(payload);
        Path database = Files.createTempFile(Path.of("."), "agent-browser-catalog-", ".sqlite");
        try {
            CatalogGame stored;
            try (SQLiteCatalogStore store = new SQLiteCatalogStore(database)) {
                URI url = URI.create("https://diplobn.com/game/?GameID=browser-selfcheck");
                stored = new GameCatalog(store).catalog(new GameSourceReference(
                                GameSource.DIPLOBN, "browser-selfcheck", url, url),
                        "Catalog fixture", "Browser", payload, SourceFingerprints.sha256Hex(payload), parsed.phases().size());
            }
            byte[] before = Files.readAllBytes(database);
            try (GameBrowserServer server = new GameBrowserServer(database, 0, ASSETS);
                 HttpClient client = HttpClient.newHttpClient()) {
                server.start();
                String base = "http://127.0.0.1:" + server.address().getPort();
                Map<?, ?> listing = object(JsonReader.read(get(client, base, "/api/games")));
                require(number(listing.get("total")) == 1 && !listing.containsKey("autoSelect"),
                        "Catalog listing behavior changed");
                Map<?, ?> game = object(JsonReader.read(get(client, base, "/api/game?id=" + stored.id().value())));
                require(game.get("source").equals("DIPLOBN") && !game.containsKey("scores"),
                        "Catalog snapshots mislabeled as agent play");
                require(array(game.get("phases")).size() == parsed.phases().size(), "Catalog gained a fabricated final phase");
                for (int index = 0; index < parsed.phases().size(); index++) {
                    var source = parsed.phases().get(index);
                    Map<?, ?> phase = object(array(game.get("phases")).get(index));
                    require(number(phase.get("sourcePhase")) == source.sourcePhase(), "Source phase changed");
                    require(phase.get("board").equals(JsonReader.read(BoardSnapshotJsonWriter.toJson(
                                    BoardSnapshotMapper.from(source.sourcePhase() / 10, source.gamePhase().name(), source.board())))),
                            "Source board changed");
                    require(array(phase.get("annotations")).size()
                            == source.resolutions().size() + source.retreatResolutions().size(), "Source outcomes changed");
                }
            }
            require(Arrays.equals(before, Files.readAllBytes(database)), "Browsing modified the catalog");
        } finally {
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-journal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
            Files.deleteIfExists(database);
        }
    }

    private static HttpResponse<String> request(HttpClient client, String base, String path,
                                                String method, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null)
            request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String get(HttpClient client, String base, String path) throws Exception {
        var response = request(client, base, path, "GET", null);
        require(response.statusCode() == 200, "Request failed: " + path + " " + response.body());
        return response.body();
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map)
            return map;
        throw new AssertionError("Expected object: " + value);
    }

    private static List<?> array(Object value) {
        if (value instanceof List<?> list)
            return list;
        throw new AssertionError("Expected array: " + value);
    }

    private static int number(Object value) {
        if (value instanceof Number number)
            return number.intValue();
        throw new AssertionError("Expected number: " + value);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}