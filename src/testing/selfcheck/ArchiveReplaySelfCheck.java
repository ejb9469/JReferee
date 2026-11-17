package testing.selfcheck;

import _app.PolicyMatchApp;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.*;
import game.*;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;
import phase.movement.MovementProcessor;
import ui.ArchiveReplayServer;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real model-free archives exercise the viewer's read-only HTTP boundary. */
public final class ArchiveReplaySelfCheck {

    private ArchiveReplaySelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: ArchiveReplaySelfCheck (from project root)");
        Path temporary = Files.createTempDirectory("jreferee-archive-viewer-");
        Path assets = Path.of("src/resources/ui");
        try {
            Path single = temporary.resolve("single");
            GameState initial = new GameState_Movement(new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                    new BoardState(Map.of(
                            new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par), Province.Par,
                            new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Bre), Province.Bur,
                            new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Lon), Province.Lon),
                            Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND,
                                    Province.Edi, Nation.ENGLAND)));
            SelfPlayRecorder.run(single, new UUID(0, 901), initial,
                    options(1902, AgentSettings.Method.PRIOR), decision -> { });
            String original = SelfPlayFiles.snapshot(single).fingerprint();
            try (ArchiveReplayServer server = new ArchiveReplayServer(single, 0, assets, message -> { });
                 HttpClient client = HttpClient.newHttpClient()) {
                server.start();
                URI root = URI.create("http://127.0.0.1:" + server.address().getPort());
                require(get(client, root, "/").statusCode() == 200, "Page unavailable");
                require(get(client, root, "/ui/movement-map.js").statusCode() == 200, "Existing renderer unavailable");
                require(get(client, root, "/api/catalog").body().contains(ArchiveReplayServer.SCHEMA), "Wrong catalog");
                var response = get(client, root, "/api/replay?id=0");
                require(response.statusCode() == 200, "Archive load failed");
                var payload = ValueDatasetJson.parse(response.body());
                var game = ValueDatasetJson.object(list(payload.get("games")).getFirst());
                List<?> frames = list(game.get("frames"));
                var last = ValueDatasetJson.object(frames.getLast());
                require(Boolean.TRUE.equals(last.get("final")) && list(last.get("orders")).isEmpty(),
                        "Final position acquired invented orders");
                require(frames.stream().map(ValueDatasetJson::object).anyMatch(frame ->
                                list(frame.get("orders")).stream().map(ValueDatasetJson::object).anyMatch(order ->
                                        "DESTROY".equals(order.get("type")) && Boolean.FALSE.equals(order.get("implicit")))),
                        "Winter disband was not displayed");
                require(frames.stream().map(ValueDatasetJson::object).anyMatch(frame ->
                                list(frame.get("orders")).stream().map(ValueDatasetJson::object).anyMatch(order ->
                                        "WAIVE".equals(order.get("type")) && Boolean.TRUE.equals(order.get("implicit")))),
                        "Unused builds were not distinguished from submitted orders");
                require(get(client, root, "/api/replay?id=0").body().equals(response.body()), "Cached view changed");
                require(get(client, root, "/api/replay?id=99").statusCode() == 404, "Unknown entry accepted");
                require(get(client, root, "/api/replay?id=0&file=run.json").statusCode() == 400, "Unknown query accepted");
                require(get(client, root, "/ui/not-allowed.txt").statusCode() == 404, "Unlisted asset accepted");
                require(client.send(HttpRequest.newBuilder(root.resolve("/api/catalog"))
                                .header("Origin", "https://example.invalid").GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "Foreign origin accepted");
                require(client.send(HttpRequest.newBuilder(root.resolve("/api/catalog"))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 405, "Write method accepted");
            }
            require(SelfPlayFiles.snapshot(single).fingerprint().equals(original), "Viewer changed archive bytes");
            Path match = temporary.resolve("match");
            var options = options(1901, AgentSettings.Method.DIL_PIKL);
            PolicyMatchApp.run(match, options, options.agents().get(Nation.ENGLAND), 1, message -> { });
            try (ArchiveReplayServer server = new ArchiveReplayServer(match, 0, assets, message -> { });
                 HttpClient client = HttpClient.newHttpClient()) {
                server.start();
                URI root = URI.create("http://127.0.0.1:" + server.address().getPort());
                var catalog = ValueDatasetJson.parse(get(client, root, "/api/catalog").body());
                require(list(catalog.get("entries")).size() == 7, "Missing power pairs");
                var payload = ValueDatasetJson.parse(get(client, root, "/api/replay?id=0").body());
                List<?> games = list(payload.get("games"));
                require(games.size() == 2 && "REFERENCE".equals(ValueDatasetJson.object(games.getFirst()).get("label"))
                        && "CANDIDATE".equals(ValueDatasetJson.object(games.getLast()).get("label")), "Sides swapped");
                require(ValueDatasetJson.object(games.getFirst()).get("frames")
                        .equals(ValueDatasetJson.object(games.getLast()).get("frames")), "A/A replay boards differ");
                Files.writeString(match.resolve("archives/pair-00001-reference/outcome.json"),
                        "\n", StandardOpenOption.APPEND);
                require(get(client, root, "/api/replay?id=1").statusCode() == 500,
                        "Changed archive was silently accepted");
            }
            require(!Files.exists(match.resolve("cache")), "Viewer created training data");
            System.out.println("ArchiveReplaySelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameRunner.Options options(int year, AgentSettings.Method method) {
        var settings = new AgentSettings(method, 1, 1, 1, 1, PolicyOutput.AVERAGE);
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values()) seats.put(power, settings);
        return new GameRunner.Options(year, 501, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static HttpResponse<String> get(HttpClient client, URI root, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw new AssertionError("Expected array");
        return list;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}