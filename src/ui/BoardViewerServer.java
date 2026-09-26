package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import game.Game;
import game.record.GameRecord;
import game.record.ResolvedPhaseRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.json.JsonEscaper.appendString;


/**
 * Loopback-only HTTP server for the JReferee board viewer.
 *
 * <p>Routes: /api/board, /api/history, /, and /ui/...</p>
 */
public final class BoardViewerServer implements AutoCloseable {


    // Constants \\

    public static final Path DEFAULT_STATIC_ROOT =
            Path.of("src", "resources", "ui");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "css", "text/css; charset=utf-8",
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml; charset=utf-8"
    );


    // Core state \\

    private final Game game;
    private final Optional<GameRecord> gameRecord;
    private final HttpServer server;
    private final Path staticRoot;


    // Constructors \\

    public BoardViewerServer(Game game, int port) {
        this(game, null, port, DEFAULT_STATIC_ROOT);
    }

    public BoardViewerServer(Game game, GameRecord gameRecord, int port) {
        this(game, gameRecord, port, DEFAULT_STATIC_ROOT);
    }

    public BoardViewerServer(
            Game game,
            GameRecord gameRecord,
            int port,
            Path staticRoot
    ) {

        this.game = Objects.requireNonNull(game, "game");
        this.gameRecord = Optional.ofNullable(gameRecord);
        this.staticRoot = Objects.requireNonNull(staticRoot, "staticRoot")
                .toAbsolutePath().normalize();

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("port out of range: " + port);

        if (!Files.isDirectory(this.staticRoot))
            throw new IllegalArgumentException(
                    "Static resource directory does not exist: " + this.staticRoot);

        try {
            InetSocketAddress bindAddress =
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);

            this.server = HttpServer.create(bindAddress, 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create board viewer server", exception);
        }

        server.createContext("/api/board", new BoardHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/", new StaticAssetHandler());
        server.setExecutor(null);

    }


    // Lifecycle \\

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {

        if (delaySeconds < 0)
            throw new IllegalArgumentException("delaySeconds must not be negative");

        server.stop(delaySeconds);

    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop(0);
    }


    // Request handlers \\

    private final class BoardHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!"GET".equals(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }

            try {
                BoardSnapshot snapshot = BoardSnapshotMapper.from(game);

                writeResponse(
                        exchange, 200, "application/json; charset=utf-8",
                        BoardSnapshotJsonWriter.toJsonBytes(snapshot));
            } catch (RuntimeException exception) {
                internalServerError(exchange);
            }

        }

    }

    private final class HistoryHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!"GET".equals(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }

            try {
                writeResponse(
                        exchange, 200, "application/json; charset=utf-8",
                        historyJson().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException exception) {
                internalServerError(exchange);
            }

        }

    }

    private final class StaticAssetHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!"GET".equals(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }

            Path requestedFile = requestedStaticFile(exchange.getRequestURI());

            if (requestedFile == null || !Files.isRegularFile(requestedFile)) {
                writeResponse(
                        exchange, 404, "text/plain; charset=utf-8",
                        "Not Found\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            try {
                byte[] payload = Files.readAllBytes(requestedFile);
                writeResponse(exchange, 200, contentTypeOf(requestedFile), payload);
            } catch (IOException exception) {
                writeResponse(
                        exchange, 500, "text/plain; charset=utf-8",
                        "Unable to read viewer asset\n".getBytes(StandardCharsets.UTF_8));
            }

        }

        private Path requestedStaticFile(URI requestUri) {

            String requestPath = requestUri.getPath();

            if (requestPath == null
                    || requestPath.equals("/")
                    || requestPath.equals("/index.html"))
                return staticRoot.resolve("index.html");

            if (!requestPath.startsWith("/ui/"))
                return null;

            String relativePath = requestPath.substring("/ui/".length());

            if (relativePath.isBlank())
                return staticRoot.resolve("index.html");

            Path candidate = staticRoot.resolve(relativePath).normalize();

            if (!candidate.startsWith(staticRoot))
                return null;

            return candidate;

        }

    }


    // History mapping \\

    private String historyJson() {

        List<HistoryEntry> entries = gameRecord.map(this::historyEntries)
                .orElseGet(this::currentBoardOnlyHistory);

        StringBuilder out = new StringBuilder(1024);
        out.append("{\"snapshots\":[");

        for (int index = 0; index < entries.size(); index++) {

            if (index > 0)
                out.append(',');

            HistoryEntry entry = entries.get(index);

            out.append('{');
            out.append("\"label\":");
            appendString(out, entry.label());
            out.append(',');
            out.append("\"snapshot\":");
            out.append(BoardSnapshotJsonWriter.toJson(entry.snapshot()));
            out.append('}');

        }

        out.append("]}");

        return out.toString();

    }

    private List<HistoryEntry> currentBoardOnlyHistory() {

        BoardSnapshot snapshot = BoardSnapshotMapper.from(game);

        return List.of(new HistoryEntry(
                "Current " + displayPhase(game.phase().name()) + " " + game.year(),
                snapshot));

    }

    /**
     * Emits the initial board, then boardAfter for each resolved phase.
     */
    private List<HistoryEntry> historyEntries(GameRecord record) {

        List<HistoryEntry> entries = new ArrayList<>();

        entries.add(new HistoryEntry(
                "Start of " + displayPhase(record.initialMoment().gamePhase().name())
                        + " " + record.initialMoment().year(),
                BoardSnapshotMapper.from(record.initialMoment(), record.initialBoard())));

        for (ResolvedPhaseRecord resolvedPhase : record.resolvedPhases())
            entries.add(new HistoryEntry(
                    "After " + displayPhase(resolvedPhase.gameMoment().gamePhase().name())
                            + " " + resolvedPhase.gameMoment().year(),
                    BoardSnapshotMapper.from(
                            resolvedPhase.gameMoment(), resolvedPhase.boardAfter())));

        return List.copyOf(entries);

    }

    private static String displayPhase(String phase) {
        return phase.replace('_', ' ');
    }


    // HTTP response helpers \\

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {

        exchange.getResponseHeaders().set("Allow", "GET");

        writeResponse(
                exchange, 405, "application/json; charset=utf-8",
                "{\"error\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8));

    }

    private static void internalServerError(HttpExchange exchange) throws IOException {

        writeResponse(
                exchange, 500, "application/json; charset=utf-8",
                "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8));

    }

    private static void writeResponse(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] payload
    ) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);

        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }

    }

    private static String contentTypeOf(Path file) {

        String filename = file.getFileName().toString();
        int extensionStart = filename.lastIndexOf('.');

        if (extensionStart < 0 || extensionStart == filename.length() - 1)
            return "application/octet-stream";

        String extension = filename.substring(extensionStart + 1).toLowerCase();

        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");

    }


    private record HistoryEntry(String label, BoardSnapshot snapshot) {

        private HistoryEntry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(snapshot, "snapshot");
        }

    }


}