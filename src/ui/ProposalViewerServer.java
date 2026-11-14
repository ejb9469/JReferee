package ui;

import agents.execution.ProposalInspector;
import agents.execution.ProposalInspector.*;
import com.sun.net.httpserver.*;
import domain.Nation;
import game.GameState_Movement;
import io.json.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static domain.Constants.nationHex;
import static io.json.JsonEscaper.appendString;

/** Read-only loopback viewer with one inference request in flight. */
public final class ProposalViewerServer implements AutoCloseable {

    public record Position(String id, String label, String communication, GameState_Movement state) {
        public Position {
            if (Objects.requireNonNull(id).isBlank() || Objects.requireNonNull(label).isBlank())
                throw new IllegalArgumentException("Position id and label are required");
            if (Objects.requireNonNull(state).aliveNations().isEmpty())
                throw new IllegalArgumentException("Position has no living power");
        }
    }

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private static final Set<String> FIELDS = Set.of(
            "position", "power", "method", "candidates", "iterations",
            "rollouts", "rolloutCandidates", "poolSeed", "searchSeed", "rolloutSeed", "search");

    private static final Map<String, String> ASSETS = Map.of(
            "proposals.html", "text/html",
            "proposals.js", "text/javascript",
            "movement-map.js", "text/javascript",
            "proposals.css", "text/css",
            "openings.css", "text/css",
            "maps/standard.svg", "image/svg+xml");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inference = new Semaphore(1);
    private final ProposalInspector inspector;
    private final List<Position> positions;
    private final Map<String, byte[]> assets = new HashMap<>();
    private final String catalog;
    private boolean closed;

    public ProposalViewerServer(ProposalInspector inspector, List<Position> positions,
                                int port, Path staticRoot) throws IOException {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.positions = List.copyOf(positions);
        if (this.positions.isEmpty()
                || this.positions.stream().map(Position::id).distinct().count() != this.positions.size())
            throw new IllegalArgumentException("Positions require distinct ids");
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Port must be 0..65535");

        Path root = staticRoot.toRealPath();
        for (String name : ASSETS.keySet()) {
            Path file = root.resolve(name).toRealPath();
            if (!file.startsWith(root))
                throw new IOException("Asset is outside static root: " + name);
            assets.put(name, Files.readAllBytes(file));
        }

        catalog = catalogJson();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

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
            String path = exchange.getRequestURI().getPath();
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String expected = "http://127.0.0.1:" + address().getPort();

            if (!expected.substring("http://".length()).equals(exchange.getRequestHeaders().getFirst("Host"))
                    || origin != null && !origin.equals(expected)) {
                error(exchange, 403, "Use the printed loopback URL");
                return;
            }

            if (path.equals("/api/inspect")) {
                inspect(exchange);
                return;
            }

            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                error(exchange, 405, "GET required");
                return;
            }

            if (path.equals("/api/positions")) {
                json(exchange, catalog);
                return;
            }

            String name = path.equals("/") ? "proposals.html"
                    : path.startsWith("/ui/") ? path.substring(4) : "";
            if (!assets.containsKey(name)) {
                error(exchange, 404, "Not found");
                return;
            }

            send(exchange, 200, ASSETS.get(name) + "; charset=utf-8", assets.get(name));

        } catch (RuntimeException exception) {
            exception.printStackTrace(System.err);
            error(exchange, 500, "Inspection failed: " + exception.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void inspect(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            error(exchange, 405, "POST required");
            return;
        }

        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-JReferee-Inspect"))
                || !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            error(exchange, 415, "application/json and X-JReferee-Inspect: 1 required");
            return;
        }

        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            error(exchange, 413, "Request exceeds 4096 bytes");
            return;
        }

        Position position;
        Nation power;
        Method method;
        Options options;

        try {
            var data = JSON.object(JsonReader.read(new String(bytes, StandardCharsets.UTF_8)), "$");
            if (!data.keySet().equals(FIELDS))
                throw new IllegalArgumentException("Missing or unknown request fields");

            String id = text(data, "position");
            position = positions.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown position id"));
            power = Nation.valueOf(text(data, "power"));
            method = Method.valueOf(text(data, "method"));

            if (!position.state().aliveNations().contains(power))
                throw new IllegalArgumentException("Requested power is eliminated");
            if (!(data.get("search") instanceof Boolean search))
                throw new IllegalArgumentException("search must be boolean");

            options = new Options(
                    integer(data, "candidates"),
                    integer(data, "iterations"),
                    integer(data, "rollouts"),
                    integer(data, "rolloutCandidates"),
                    Long.parseLong(text(data, "poolSeed")),
                    Long.parseLong(text(data, "searchSeed")),
                    Long.parseLong(text(data, "rolloutSeed")),
                    search);

            if (search && (long) options.candidates() * options.iterations()
                    * options.rollouts() * options.rolloutCandidates() > 32768)
                throw new IllegalArgumentException(
                        "Viewer work budget exceeds 32768 candidate x iteration x rollout x rollout-candidate units");

        } catch (IllegalArgumentException exception) {
            error(exchange, 400, exception.getMessage());
            return;
        }

        if (!inference.tryAcquire()) {
            error(exchange, 429, "Another inspection is still running");
            return;
        }

        String response;
        try {
            StringBuilder out = new StringBuilder("{\"position\":");
            appendPosition(out, position);
            out.append(",\"inspection\":").append(ProposalInspectionJsonWriter.toJson(
                    inspector.inspect(position.state(), power, method, options))).append('}');
            response = out.toString();
        } finally {
            inference.release();
        }
        json(exchange, response);
    }

    private String catalogJson() {
        Map<String, String> colors = new TreeMap<>();
        for (Nation nation : Nation.values())
            colors.put(nation.name(), nationHex(nation));

        StringBuilder out = new StringBuilder("{\"colors\":")
                .append(ProposalInspectionJsonWriter.strings(colors))
                .append(",\"models\":")
                .append(ProposalInspectionJsonWriter.strings(inspector.models()))
                .append(",\"positions\":[");

        for (int index = 0; index < positions.size(); index++) {
            if (index > 0) out.append(',');
            appendPosition(out, positions.get(index));
        }
        return out.append("]}").toString();
    }

    private static void appendPosition(StringBuilder out, Position position) {
        out.append("{\"id\":");
        appendString(out, position.id());
        out.append(",\"label\":");
        appendString(out, position.label());
        out.append(",\"communication\":");
        ProposalInspectionJsonWriter.nullable(out, position.communication());
        out.append(",\"powers\":[");

        boolean first = true;
        for (Nation nation : Nation.values()) {
            if (!position.state().aliveNations().contains(nation)) continue;
            if (!first) out.append(',');
            appendString(out, nation.name());
            first = false;
        }

        out.append("],\"board\":").append(BoardSnapshotJsonWriter.toJson(
                BoardSnapshotMapper.from(position.state().moment(), position.state().board()))).append('}');
    }

    private static String text(Map<String, Object> data, String key) {
        return JSON.string(data.get(key), key);
    }

    private static int integer(Map<String, Object> data, String key) {
        return JSON.integer(data.get(key), key);
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        StringBuilder out = new StringBuilder("{\"error\":");
        appendString(out, message);
        send(exchange, status, "application/json; charset=utf-8",
                out.append('}').toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}