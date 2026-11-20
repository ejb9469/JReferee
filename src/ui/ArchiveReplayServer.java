package ui;

import _app.PolicyMatchApp;
import com.sun.net.httpserver.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.AdjustmentInput;
import phase.adjustments.*;
import phase.retreats.RetreatOrder;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Read-only, model-free viewing of completed self-play archives and matches.
 * A loaded pair contains display snapshots, not models or search state.
 */
public final class ArchiveReplayServer implements AutoCloseable {


    // Sources and assets \\

    public static final String SCHEMA = "jreferee-archive-replay-v1";
    private static final Map<String, String> ASSETS = Map.of(
            "archive-replay.html", "text/html",
            "archive-replay.css", "text/css",
            "archive-replay.js", "text/javascript",
            "archive-replay-model.mjs", "text/javascript",
            "openings.css", "text/css",
            "games.css", "text/css",
            "movement-map.js", "text/javascript",
            "maps/standard.svg", "image/svg+xml");

    private record Archive(Path directory, String fingerprint, String label) { }
    private record Entry(Map<String, Object> description, List<Archive> archives) { }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, byte[]> assets = new HashMap<>();
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] catalog;
    private int cachedId = -1;
    private byte[] cachedReplay;
    private boolean closed;

    public ArchiveReplayServer(
            Path source, int port, Path assetRoot, Consumer<String> progress
    ) throws IOException {

        Objects.requireNonNull(progress, "progress");
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Port must be 0..65535");
        Path root = source.toRealPath();
        Map<String, Object> manifest = document(root.resolve("manifest.json"));
        Map<String, Object> overview;
        if (PolicyMatchApp.SCHEMA.equals(manifest.get("schema"))) {
            progress.accept("Verifying the completed match before serving it.");
            manifest = ValueDatasetJson.parse(PolicyMatchApp.verify(root, progress));
            var receipts = ValueDatasetJson.object(manifest.get("files"));
            var plan = ValueDatasetJson.parse(checked(root.resolve("plan.json"), receipts.get("plan.json")));
            List<Map<String, Object>> rows = checked(root.resolve("games.jsonl"), receipts.get("games.jsonl"))
                    .lines().map(ValueDatasetJson::parse).toList();
            for (int index = 0; index < rows.size(); index += 2) {
                var first = rows.get(index);
                var second = rows.get(index + 1);
                var reference = "REFERENCE".equals(first.get("side")) ? first : second;
                var candidate = reference == first ? second : first;
                entries.add(new Entry(object("id", entries.size(), "power", reference.get("power"),
                        "seed", reference.get("game_seed"), "referenceScore", reference.get("focal_sos"),
                        "candidateScore", candidate.get("focal_sos"),
                        "referenceEnding", reference.get("termination"), "candidateEnding", candidate.get("termination")),
                        List.of(archive(root, reference), archive(root, candidate))));
            }
            overview = object("mode", "MATCH", "lastYear", plan.get("last_year"),
                    "rotations", manifest.get("rotations"), "overall", manifest.get("overall"),
                    "endings", manifest.get("endings"),
                    "referenceSettings", plan.get("reference_seat"), "candidateSettings", plan.get("candidate_seat"));
        } else if (SelfPlayFiles.SCHEMA.equals(manifest.get("schema"))) {
            progress.accept("Verifying the completed game.");
            var game = SelfPlayDataset.open(root);
            entries.add(new Entry(object("id", 0, "power", Nation.ENGLAND.name(),
                    "seed", Long.toString(game.configuration().seed())),
                    List.of(new Archive(root, game.fingerprint(), "GAME"))));
            cachedReplay = json(object("entry", entries.getFirst().description(),
                    "games", List.of(display(game, "GAME"))));
            cachedId = 0;
            overview = object("mode", "GAME", "lastYear", game.configuration().lastYear(),
                    "termination", game.outcome().termination().name());
        } else {
            throw new IllegalArgumentException("Expected a completed self-play game or policy match");
        }
        Map<String, String> colors = new LinkedHashMap<>();
        for (Nation power : Nation.values())
            colors.put(power.name(), Constants.nationHex(power));
        catalog = json(object("schema", SCHEMA, "title", root.getFileName().toString(),
                "colors", colors, "overview", overview,
                "entries", entries.stream().map(Entry::description).toList()));
        Path staticRoot = assetRoot.toRealPath();
        for (String name : ASSETS.keySet()) {
            Path path = staticRoot.resolve(name).toRealPath();
            if (!path.startsWith(staticRoot))
                throw new IOException("Asset is outside the static root: " + name);
            assets.put(name, Files.readAllBytes(path));
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/", this::handle);

    }

    private static Archive archive(Path root, Map<String, Object> row) throws IOException {
        Path path = root.resolve(ValueDatasetJson.text(row, "directory")).toRealPath();
        if (!path.startsWith(root))
            throw new IOException("Archive is outside the match directory");
        return new Archive(path, ValueDatasetJson.text(row, "archive_sha256"), ValueDatasetJson.text(row, "side"));
    }

    private synchronized byte[] replay(int id) throws IOException {
        if (cachedId != id) {
            Entry entry = entries.get(id);
            List<Map<String, Object>> games = new ArrayList<>();
            for (Archive archive : entry.archives()) {
                var game = SelfPlayDataset.open(archive.directory());
                if (!game.fingerprint().equals(archive.fingerprint()))
                    throw new IOException("Archive differs from the verified collection: " + archive.directory());
                games.add(display(game, archive.label()));
            }
            cachedReplay = json(object("entry", entry.description(), "games", games));
            cachedId = id;
        }
        return cachedReplay;
    }


    // Recorded positions and submissions \\

    public static Map<String, Object> display(SelfPlayDataset.GameExample game, String label) {

        List<Map<String, Object>> frames = new ArrayList<>();
        for (var phase : game.phases()) {
            List<OrderForm> orders = new ArrayList<>();
            List<String> empty = new ArrayList<>();
            for (var decision : phase.decisions()) {
                orders.addAll(decision.selected().orders());
                if (decision.selected().orders().isEmpty())
                    empty.add(decision.power().name());
            }
            Map<String, Object> frame = frame(phase.state(), false);
            List<Map<String, Object>> shown = new ArrayList<>();
            for (OrderForm order : orders) {
                var raw = SelfPlayRecorder.order(phase.state(), order);
                Province origin = raw.get("origin") == null ? null : Province.valueOf((String) raw.get("origin"));
                shown.add(object("nation", order.owner().name(), "type", order.orderType().name(),
                        "unitType", raw.get("unit_type"), "origin", raw.get("origin"),
                        "target", raw.get("target"), "auxiliaryTarget", raw.get("auxiliary_target"),
                        "text", OrderForm.format(order, origin), "implicit", false));
            }
            if (phase.state() instanceof GameState_Retreats retreats) {
                for (var unit : retreats.movementResult().dislodgements().values())
                    if (orders.stream().noneMatch(order -> order instanceof RetreatOrder retreat
                            && retreat.unit().equals(unit.unit())))
                        shown.add(object("nation", unit.unit().owner().name(), "type", "DESTROY",
                                "unitType", unit.unit().unitType().name(), "origin", unit.displacedFrom().name(),
                                "target", null, "auxiliaryTarget", null, "implicit", true,
                                "text", unit.unit().unitType() + " " + unit.displacedFrom() + ": omitted retreat; disbanded"));
            }
            if (phase.state() instanceof GameState_Adjustments adjustments) {
                var balances = new BalanceCalculator().calculate(new AdjustmentInput(
                        adjustments.moment().year(), adjustments.fallRetreatResult(), adjustments.board().owners(), List.of()));
                for (Nation power : Nation.values()) {
                    long used = orders.stream().filter(order -> order.owner() == power
                            && (order instanceof BuildOrder || order instanceof WaiveOrder)).count();
                    long unused = balances.get(power).availableBuilds() - used;
                    if (unused > 0)
                        shown.add(object("nation", power.name(), "type", "WAIVE", "unitType", null,
                                "origin", null, "target", null, "auxiliaryTarget", null, "implicit", true,
                                "text", unused + " unused build(s), omitted"));
                }
            }
            frame.put("orders", shown);
            frame.put("emptyActions", empty);
            frames.add(frame);
        }
        frames.add(frame(game.finalState(), true));
        Map<String, Object> models = new LinkedHashMap<>();
        game.configuration().seats().forEach((power, seat) -> models.put(power.name(), object(
                "movement", seat.movementModels(),
                "value", seat.valueModel().map(value -> object("model", value.model(),
                        "checkpoint", value.checkpoint(), "dataset", value.dataset())).orElse(null))));
        return object("label", label, "id", game.id().toString(), "archiveSha256", game.fingerprint(),
                "seed", Long.toString(game.configuration().seed()), "lastYear", game.configuration().lastYear(),
                "termination", game.outcome().termination().name(),
                "winner", game.outcome().centerCounts().entrySet().stream().filter(entry -> entry.getValue() >= 18)
                        .map(entry -> entry.getKey().name()).findFirst().orElse(null),
                "models", models, "frames", frames);

    }

    private static Map<String, Object> frame(GameState state, boolean terminal) {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            counts.put(power, 0);
        state.board().owners().values().forEach(power -> counts.merge(power, 1, Integer::sum));
        Map<String, Object> centers = new LinkedHashMap<>(), scores = new LinkedHashMap<>();
        var rewards = ScoringSystem.SUM_OF_SQUARES.rewards(counts);
        for (Nation power : Nation.values()) {
            centers.put(power.name(), counts.get(power));
            scores.put(power.name(), rewards.get(power));
        }
        List<Map<String, Object>> dislodged = new ArrayList<>();
        if (state instanceof GameState_Retreats retreats)
            retreats.movementResult().dislodgements().values().forEach(unit -> dislodged.add(object(
                    "nation", unit.unit().owner().name(), "type", unit.unit().unitType().name(),
                    "province", unit.displacedFrom().name())));
        return object("year", state.moment().year(), "phase", state.moment().gamePhase().name(), "final", terminal,
                "board", ValueDatasetJson.parse(BoardSnapshotJsonWriter.toJson(
                        BoardSnapshotMapper.from(state.moment(), state.board()))),
                "centers", centers, "scores", scores, "dislodged", dislodged,
                "orders", List.of(), "emptyActions", List.of());
    }


    // GET-only loopback service \\

    public void start() { server.start(); }
    public InetSocketAddress address() { return server.getAddress(); }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = "127.0.0.1:" + address().getPort();
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!host.equals(exchange.getRequestHeaders().getFirst("Host"))
                    || origin != null && !origin.equals("http://" + host)) {
                error(exchange, 403, "Use the printed loopback URL");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                error(exchange, 405, "GET required");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (path.equals("/api/replay")) {
                if (query == null || !query.matches("id=[0-9]{1,3}")) {
                    error(exchange, 400, "Expected one numeric entry id");
                    return;
                }
                int id = Integer.parseInt(query.substring(3));
                if (id >= entries.size()) {
                    error(exchange, 404, "Unknown replay entry");
                    return;
                }
                send(exchange, 200, "application/json", replay(id));
                return;
            }
            if (query != null) {
                error(exchange, 400, "Unexpected query");
                return;
            }
            if (path.equals("/api/catalog")) {
                send(exchange, 200, "application/json", catalog);
                return;
            }
            String name = path.equals("/") ? "archive-replay.html"
                    : path.startsWith("/ui/") ? path.substring(4) : "";
            if (!assets.containsKey(name)) {
                error(exchange, 404, "Not found");
                return;
            }
            send(exchange, 200, ASSETS.get(name), assets.get(name));
        } catch (IOException | RuntimeException exception) {
            exception.printStackTrace(System.err);
            error(exchange, 500, "Unable to load verified replay: " + exception.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json", json(object("error", message)));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
                + "base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }


    // Exact metadata readback \\

    private static byte[] json(Object value) {
        return SelfPlayFiles.json(value).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024)
                throw new IOException("Metadata exceeds 4 MiB: " + path);
            return bytes;
        }
    }

    private static String text(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Map<String, Object> document(Path path) throws IOException {
        return ValueDatasetJson.parse(text(read(path)));
    }

    private static String checked(Path path, Object receipt) throws IOException {
        byte[] bytes = read(path);
        var expected = ValueDatasetJson.object(receipt);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != ValueDatasetJson.count(expected, "bytes")
                    || !hash.equals(ValueDatasetJson.text(expected, "sha256")))
                throw new IOException("Metadata changed after verification: " + path);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        return text(bytes);
    }

}