package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GameMoment;
import game.record.GameRecord;
import game.record.ResolvedPhaseRecord;
import phase.UnitId;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;


/**
 * Loopback-only HTTP server for the JReferee board viewer.
 *
 * <p>Routes:</p>
 * <ul>
 *     <li>{@code GET /api/board} — current board in the supplied Game</li>
 *     <li>{@code GET /api/history} — replay positions from an optional GameRecord</li>
 *     <li>{@code GET /} — board-viewer HTML</li>
 *     <li>{@code GET /ui/...} — CSS, JavaScript, and SVG viewer assets</li>
 * </ul>
 *
 * <p>The server binds only to {@code 127.0.0.1}; it is never exposed to the
 * local network.</p>
 */
public final class BoardViewerServer implements AutoCloseable {


    public static final Path DEFAULT_STATIC_ROOT =
            Path.of("src", "resources", "ui");

    private static final Comparator<Map.Entry<UnitId, Province>> UNIT_ORDER =
            Comparator.<Map.Entry<UnitId, Province>, String>comparing(
                            entry -> entry.getValue().name())
                    .thenComparing(entry -> entry.getKey().owner().name())
                    .thenComparing(entry -> entry.getKey().unitType().name());

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "css", "text/css; charset=utf-8",
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml; charset=utf-8"
    );


    private final Game game;
    private final Optional<GameRecord> gameRecord;
    private final HttpServer server;
    private final Path staticRoot;


    /**
     * Creates a viewer server without archived history.
     *
     * <p>{@code /api/history} remains available, but contains one entry for
     * the current board only.</p>
     */
    public BoardViewerServer(Game game, int port) {
        this(game, null, port, DEFAULT_STATIC_ROOT);
    }

    /**
     * Creates a viewer server with archived replay history.
     *
     * @param game live current game for {@code /api/board}
     * @param gameRecord immutable game history for {@code /api/history}
     * @param port port from 0 through 65535; 0 chooses an ephemeral port
     */
    public BoardViewerServer(Game game, GameRecord gameRecord, int port) {
        this(game, gameRecord, port, DEFAULT_STATIC_ROOT);
    }

    /**
     * Creates a viewer server using a caller-supplied static-resource root.
     *
     * @param game live current game for {@code /api/board}
     * @param gameRecord optional immutable game history for {@code /api/history}
     * @param port port from 0 through 65535; 0 chooses an ephemeral port
     * @param staticRoot directory containing index.html and viewer assets
     */
    public BoardViewerServer(
            Game game,
            GameRecord gameRecord,
            int port,
            Path staticRoot
    ) {

        this.game = Objects.requireNonNull(game, "game");
        this.gameRecord = Optional.ofNullable(gameRecord);

        this.staticRoot = Objects.requireNonNull(
                staticRoot,
                "staticRoot"
        ).toAbsolutePath().normalize();

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("port out of range: " + port);

        if (!Files.isDirectory(this.staticRoot))
            throw new IllegalArgumentException("Static resource directory does not exist: " + this.staticRoot);

        try {
            InetSocketAddress bindAddress = new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    port);
            this.server = HttpServer.create(bindAddress, 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create board viewer server", exception);
        }

        server.createContext("/api/board", new BoardHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/", new StaticAssetHandler());
        server.setExecutor(null);

    }


    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException(
                    "delaySeconds must not be negative"
            );
        }

        server.stop(delaySeconds);
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop(0);
    }


    // private helper class
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
                        exchange,
                        200,
                        "application/json; charset=utf-8",
                        BoardSnapshotJsonWriter.toJsonBytes(snapshot)
                );
            } catch (RuntimeException exception) {
                internalServerError(exchange);
            }
        }
    }

    // private helper class
    private final class HistoryHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }
            try {
                String json = historyJson();
                writeResponse(
                        exchange,
                        200,
                        "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8)
                );
            } catch (RuntimeException exception) {
                internalServerError(exchange);
            }
        }

    }

    // private helper class
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
                        exchange,
                        404,
                        "text/plain; charset=utf-8",
                        "Not Found\n".getBytes(StandardCharsets.UTF_8)
                );
                return;
            }

            try {
                byte[] payload = Files.readAllBytes(requestedFile);

                writeResponse(
                        exchange,
                        200,
                        contentTypeOf(requestedFile),
                        payload
                );
            } catch (IOException exception) {
                writeResponse(
                        exchange,
                        500,
                        "text/plain; charset=utf-8",
                        "Unable to read viewer asset\n"
                                .getBytes(StandardCharsets.UTF_8)
                );
            }
        }

        private Path requestedStaticFile(URI requestUri) {

            String requestPath = requestUri.getPath();

            if (requestPath == null
                    || requestPath.equals("/")
                    || requestPath.equals("/index.html")) {
                return staticRoot.resolve("index.html");
            }

            if (!requestPath.startsWith("/ui/"))
                return null;

            String relativePath = requestPath.substring("/ui/".length());

            if (relativePath.isBlank())
                return staticRoot.resolve("index.html");

            Path candidate = staticRoot
                    .resolve(relativePath)
                    .normalize();

            if (!candidate.startsWith(staticRoot))
                return null;

            return candidate;

        }

    }


    private String historyJson() {

        List<HistoryEntry> entries = gameRecord.map(this::historyEntries)
                .orElseGet(this::currentBoardOnlyHistory);

        StringBuilder out = new StringBuilder(1024);

        out.append("{\"snapshots\":[");

        for (int i = 0; i < entries.size(); i++) {

            if (i > 0)
                out.append(',');

            HistoryEntry entry = entries.get(i);

            out.append('{');
            out.append("\"label\":");
            appendJsonString(out, entry.label());
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

        return List.of(
                new HistoryEntry(
                        "Current "
                                + displayPhase(game.phase().name())
                                + " "
                                + game.year(),
                        snapshot
                ));

    }

    /**
     * Creates one replay entry for the initial board, then one boardAfter entry
     * per resolved phase. boardBefore positions are deliberately omitted
     * because each is normally identical to the prior boardAfter.
     */
    private List<HistoryEntry> historyEntries(GameRecord record) {

        List<HistoryEntry> entries = new ArrayList<>();

        entries.add(
                new HistoryEntry(
                        "Start of "
                                + displayPhase(
                                record.initialMoment().gamePhase().name()
                        )
                                + " "
                                + record.initialMoment().year(),
                        snapshotOf(
                                record.initialMoment(),
                                record.initialBoard()
                        )
                )
        );

        for (ResolvedPhaseRecord resolvedPhase
                : record.resolvedPhases()) {

            entries.add(
                    new HistoryEntry(
                            "After "
                                    + displayPhase(
                                    resolvedPhase.gameMoment()
                                            .gamePhase()
                                            .name()
                            )
                                    + " "
                                    + resolvedPhase.gameMoment().year(),
                            snapshotOf(
                                    resolvedPhase.gameMoment(),
                                    resolvedPhase.boardAfter()
                            )
                    )
            );
        }

        return List.copyOf(entries);

    }

    private static BoardSnapshot snapshotOf(GameMoment moment, BoardState board) {

        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");

        List<BoardSnapshot.Unit> units = board.locations()
                .entrySet()
                .stream()
                .sorted(UNIT_ORDER)
                .map(entry -> new BoardSnapshot.Unit(
                        entry.getKey().owner().name(),
                        entry.getKey().unitType().name(),
                        entry.getValue().name()
                ))
                .toList();

        TreeMap<String, String> sortedOwners = new TreeMap<>();

        for (Map.Entry<Province, Nation> entry
                : board.owners().entrySet()) {
            sortedOwners.put(
                    Province.canonical(entry.getKey()).name(),
                    entry.getValue().name());
        }

        Map<String, String> owners = new LinkedHashMap<>();
        sortedOwners.forEach(owners::put);

        return new BoardSnapshot(
                moment.year(),
                moment.gamePhase().name(),
                units,
                owners);

    }

    private static String displayPhase(String phase) {
        return phase.replace('_', ' ');
    }

    private static void methodNotAllowed(HttpExchange exchange)
            throws IOException {

        exchange.getResponseHeaders().set("Allow", "GET");

        writeResponse(
                exchange,
                405,
                "application/json; charset=utf-8",
                "{\"error\":\"Method Not Allowed\"}"
                        .getBytes(StandardCharsets.UTF_8)
        );

    }

    private static void internalServerError(HttpExchange exchange)
            throws IOException {

        writeResponse(
                exchange,
                500,
                "application/json; charset=utf-8",
                "{\"error\":\"Internal Server Error\"}"
                        .getBytes(StandardCharsets.UTF_8)
        );

    }

    private static void writeResponse(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] payload
    ) throws IOException {

        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType);

        exchange.sendResponseHeaders(status, payload.length);

        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }

    }

    private static String contentTypeOf(Path file) {

        String filename = file.getFileName().toString();
        int extensionStart = filename.lastIndexOf('.');

        if (extensionStart < 0
                || extensionStart == filename.length() - 1) {
            return "application/octet-stream";
        }

        String extension = ( filename.substring(
                extensionStart + 1).toLowerCase() );

        return CONTENT_TYPES.getOrDefault(
                extension,
                "application/octet-stream");

    }

    private static void appendJsonString(StringBuilder out, String value) {

        Objects.requireNonNull(value, "value");

        out.append('"');

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");

                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        out.append("0".repeat(4 - hex.length()));
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }

        }

        out.append('"');

    }

    // private helper class
    private record HistoryEntry(String label, BoardSnapshot snapshot) {
        private HistoryEntry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

}