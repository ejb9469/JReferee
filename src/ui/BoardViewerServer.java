package ui;

import adjudication.Order;
import analysis.openings.Catalog;
import contracts.OrderForm;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import domain.Nation;
import domain.Province;
import game.Game;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.ResolvedPhaseRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static domain.Constants.nationHex;
import static io.json.JsonEscaper.appendString;


public final class BoardViewerServer implements AutoCloseable {

    public static final Path DEFAULT_STATIC_ROOT =
            Path.of("src", "resources", "ui");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "css", "text/css; charset=utf-8",
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml; charset=utf-8"
    );

    private final Game game;
    private final GameRecord gameRecord;
    private final HttpServer server;
    private final Path staticRoot;
    private final byte[] openingPayload;

    private boolean closed;


    public BoardViewerServer(Game game, int port) {
        this(game, null, null, port, DEFAULT_STATIC_ROOT);
    }

    public BoardViewerServer(Game game, GameRecord gameRecord, int port) {
        this(game, gameRecord, null, port, DEFAULT_STATIC_ROOT);
    }

    public BoardViewerServer(Game game, GameRecord gameRecord, int port, Path staticRoot) {
        this(game, gameRecord, null, port, staticRoot);
    }

    public BoardViewerServer(Catalog catalog, int port) {
        this(StandardGameFactory.create1901(), null,
                Objects.requireNonNull(catalog, "catalog"), port, DEFAULT_STATIC_ROOT);
    }

    public BoardViewerServer(Catalog catalog, int port, Path staticRoot) {
        this(StandardGameFactory.create1901(), null,
                Objects.requireNonNull(catalog, "catalog"), port, staticRoot);
    }

    private BoardViewerServer(Game game, GameRecord gameRecord, Catalog catalog,
                              int port, Path staticRoot) {

        this.game = Objects.requireNonNull(game, "game");
        this.gameRecord = gameRecord;

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("port out of range: " + port);

        try {
            this.staticRoot = Objects.requireNonNull(staticRoot, "staticRoot").toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to open static resource directory", exception);
        }

        if (!Files.isDirectory(this.staticRoot))
            throw new IllegalArgumentException("Static root must be a directory");

        this.openingPayload = catalog == null ? null
                : openingsJson(catalog).getBytes(StandardCharsets.UTF_8);

        try {
            this.server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create viewer server", exception);
        }

        server.createContext("/", this::handle);
        server.setExecutor(null);

    }


    // Lifecycle \\

    public void start() {
        server.start();
    }

    public synchronized void stop(int delaySeconds) {

        if (delaySeconds < 0)
            throw new IllegalArgumentException("delaySeconds must not be negative");

        if (!closed) {
            server.stop(delaySeconds);
            closed = true;
        }

    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop(0);
    }


    // HTTP \\

    private void handle(HttpExchange exchange) throws IOException {

        try {

            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                text(exchange, 405, "Method Not Allowed\n");
                return;
            }

            String path = exchange.getRequestURI().getPath();

            switch (path) {

                case "/api/board" -> json(exchange,
                        BoardSnapshotJsonWriter.toJsonBytes(BoardSnapshotMapper.from(game)));

                case "/api/history" -> json(exchange,
                        historyJson().getBytes(StandardCharsets.UTF_8));

                case "/api/openings" -> {
                    if (openingPayload == null)
                        text(exchange, 404, "Openings are not loaded\n");
                    else
                        json(exchange, openingPayload);
                }

                default -> serveAsset(exchange, path);

            }

        } catch (RuntimeException exception) {
            exception.printStackTrace(System.err);
            text(exchange, 500, "Internal Server Error\n");
        } finally {
            exchange.close();
        }

    }

    private void serveAsset(HttpExchange exchange, String requestPath) throws IOException {

        String relativePath;

        if (requestPath.equals("/") || requestPath.equals("/index.html"))
            relativePath = openingPayload == null ? "index.html" : "openings.html";
        else if (requestPath.startsWith("/ui/"))
            relativePath = requestPath.substring("/ui/".length());
        else {
            text(exchange, 404, "Not Found\n");
            return;
        }

        Path candidate = staticRoot.resolve(relativePath).normalize();

        if (!candidate.startsWith(staticRoot) || !Files.isRegularFile(candidate)) {
            text(exchange, 404, "Not Found\n");
            return;
        }

        Path file = candidate.toRealPath();

        if (!file.startsWith(staticRoot)) {
            text(exchange, 404, "Not Found\n");
            return;
        }

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);

        writeResponse(exchange, 200,
                CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"),
                Files.readAllBytes(file));

    }

    private static void json(HttpExchange exchange, byte[] payload) throws IOException {
        writeResponse(exchange, 200, "application/json; charset=utf-8", payload);
    }

    private static void text(HttpExchange exchange, int status, String text) throws IOException {
        writeResponse(exchange, status, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(HttpExchange exchange, int status,
                                      String contentType, byte[] payload) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);

        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }

    }


    // Openings JSON \\

    private String openingsJson(Catalog catalog) {

        StringBuilder out = new StringBuilder();

        out.append("{\"scanned\":").append(catalog.scanned());
        out.append(",\"represented\":").append(catalog.represented());
        out.append(",\"skipped\":").append(catalog.skipped());
        out.append(",\"nationalOpenings\":").append(catalog.nationalOpenings());
        out.append(",\"board\":")
                .append(BoardSnapshotJsonWriter.toJson(BoardSnapshotMapper.from(game)));
        out.append(",\"colors\":{");

        boolean first = true;

        for (Nation nation : Nation.values()) {

            if (!first)
                out.append(',');

            appendString(out, nation.name());
            out.append(':');
            appendString(out, nationHex(nation));
            first = false;

        }

        out.append("},\"diagnostics\":[");

        for (int index = 0; index < catalog.diagnostics().size(); index++) {
            if (index > 0)
                out.append(',');
            appendString(out, catalog.diagnostics().get(index));
        }

        out.append("],\"openings\":[");
        first = true;

        for (Catalog.Entry entry : catalog.entries()) {

            if (!first)
                out.append(',');

            out.append("{\"nation\":");
            appendString(out, entry.nation().name());
            out.append(",\"signature\":");
            appendString(out, entry.signature());
            out.append(",\"count\":").append(entry.count());
            out.append(",\"orders\":[");

            List<Order> orders = entry.displayOrders();

            for (int index = 0; index < orders.size(); index++) {

                if (index > 0)
                    out.append(',');

                Order order = orders.get(index);
                appendOrder(out, order, order.origin());

            }

            out.append("]}");
            first = false;

        }

        return out.append("]}").toString();

    }

    private static void appendOrder(StringBuilder out, OrderForm order, Province location) {

        out.append("{\"text\":");
        appendString(out, OrderForm.format(order, location));
        out.append(",\"type\":");

        if (order.orderType() == null)
            out.append("null");
        else
            appendString(out, order.orderType().name());

        out.append(",\"origin\":");
        appendProvince(out, location);
        out.append(",\"target\":");
        appendProvince(out, order.target());
        out.append(",\"auxiliaryTarget\":");
        appendProvince(out, order.auxiliaryTarget());
        out.append('}');

    }

    private static void appendProvince(StringBuilder out, Province province) {
        if (province == null)
            out.append("null");
        else
            appendString(out, province.name());
    }


    // Original board-history API \\

    private String historyJson() {

        StringBuilder out = new StringBuilder("{\"snapshots\":[");

        if (gameRecord == null) {
            appendSnapshot(out,
                    "Current " + game.phase().name().replace('_', ' ') + " " + game.year(),
                    BoardSnapshotMapper.from(game));
        } else {

            appendSnapshot(out,
                    "Start of " + gameRecord.initialMoment().gamePhase().name().replace('_', ' ')
                            + " " + gameRecord.initialMoment().year(),
                    BoardSnapshotMapper.from(gameRecord.initialMoment(), gameRecord.initialBoard()));

            for (ResolvedPhaseRecord phase : gameRecord.resolvedPhases()) {

                out.append(',');

                appendSnapshot(out,
                        "After " + phase.gameMoment().gamePhase().name().replace('_', ' ')
                                + " " + phase.gameMoment().year(),
                        BoardSnapshotMapper.from(phase.gameMoment(), phase.boardAfter()));

            }

        }

        return out.append("]}").toString();

    }

    private static void appendSnapshot(StringBuilder out, String label, BoardSnapshot snapshot) {

        out.append("{\"label\":");
        appendString(out, label);
        out.append(",\"snapshot\":").append(BoardSnapshotJsonWriter.toJson(snapshot));
        out.append('}');

    }

}