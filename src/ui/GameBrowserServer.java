package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import contracts.OrderForm;
import domain.Constants;
import domain.Nation;
import domain.Province;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNOrderResolution;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.json.JsonEscaper.appendString;


/**
 * Read-only HTTP browser for cataloged game snapshots.<br><br>
 *
 * Database connections are opened per request. No schema initialization,
 * importing, adjudication, or database mutation is performed.
 *
 * <p>Game metadata is paginated. A selected game's stored payload is parsed
 * only when requested. Source snapshots retain their original ordering.</p>
 *
 * <p>Movement and retreat submissions may coexist in a source snapshot.
 * Both are serialized when supplied; no intermediate board is invented.</p>
 */
public final class GameBrowserServer implements AutoCloseable {


    // Constants \\

    private static final int PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 200;

    private static final String GAME_COLUMNS = """
            catalog_id, source_type, external_key, title,
            competition, source_phase_count
            """;

    private static final String SEARCH = """
            WHERE (
                lower(coalesce(title, '')) LIKE ? ESCAPE '\\'
                OR lower(coalesce(competition, '')) LIKE ? ESCAPE '\\'
                OR lower(external_key) LIKE ? ESCAPE '\\'
                OR lower(catalog_id) LIKE ? ESCAPE '\\'
            )
            """;


    // Core state \\

    private final String databaseUrl;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Asset> assets;

    private boolean closed;


    // Construction \\

    public GameBrowserServer(
            Path database,
            int port,
            Path assetRoot
    ) throws IOException, SQLException, ClassNotFoundException {

        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(assetRoot, "assetRoot");

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException(
                    "Port must be between 0 and 65535");

        Path databasePath = database.toAbsolutePath().normalize();

        if (!Files.isRegularFile(databasePath))
            throw new IllegalArgumentException(
                    "Database not found: " + databasePath);

        Class.forName("org.sqlite.JDBC");

        this.databaseUrl =
                "jdbc:sqlite:" + databasePath.toUri().toASCIIString() + "?mode=ro";

        // Verify the existing schema without creating or changing anything.
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery(
                     "SELECT " + GAME_COLUMNS
                             + ", source_payload FROM catalog_game LIMIT 0")) {

            // Opening the result verifies the columns used by this browser.

        }

        /*
         * Only these application assets are served. Request paths are never
         * converted into arbitrary filesystem paths.
         */
        this.assets = Map.of(
                "/", asset(
                        assetRoot.resolve("games.html"),
                        "text/html"),
                "/ui/games.js", asset(
                        assetRoot.resolve("games.js"),
                        "text/javascript"),
                "/ui/games.css", asset(
                        assetRoot.resolve("games.css"),
                        "text/css"),
                "/ui/openings.css", asset(
                        assetRoot.resolve("openings.css"),
                        "text/css"),
                "/ui/maps/standard.svg", asset(
                        assetRoot.resolve("maps/standard.svg"),
                        "image/svg+xml"));

        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                0);

        this.executor = Executors.newFixedThreadPool(2);

        server.setExecutor(executor);
        server.createContext("/", this::handle);

    }


    // Lifecycle \\

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public synchronized void close() {

        if (closed)
            return;

        closed = true;
        server.stop(0);
        executor.shutdownNow();

    }


    // HTTP routing \\

    private void handle(HttpExchange exchange) throws IOException {

        try {

            /*
             * Loopback binding alone does not reject requests addressed
             * through a DNS name resolving to loopback.
             */
            if (!trustedRequest(exchange)) {
                send(exchange, 403, "text/plain", bytes("Forbidden"));
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "text/plain", bytes("Method Not Allowed"));
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if (path.equals("/api/games")) {

                Map<String, String> query =
                        query(exchange, Set.of("q", "offset"));

                String search = query.getOrDefault("q", "").strip();

                if (search.length() > MAX_QUERY_LENGTH)
                    throw new RequestFailure(400, "Search text is too long");

                int offset;

                try {
                    offset = Integer.parseInt(
                            query.getOrDefault("offset", "0"));
                } catch (NumberFormatException exception) {
                    throw new RequestFailure(400, "Invalid offset");
                }

                if (offset < 0)
                    throw new RequestFailure(
                            400, "Offset must not be negative");

                sendJson(exchange, gamesJson(search, offset));
                return;

            }

            if (path.equals("/api/game")) {

                Map<String, String> query = query(exchange, Set.of("id"));
                String id = query.get("id");

                if (id == null || id.isBlank() || id.length() > 128)
                    throw new RequestFailure(
                            400, "A catalog ID is required");

                sendJson(exchange, gameJson(id));
                return;

            }

            Asset asset = assets.get(path);

            if (asset == null) {
                send(exchange, 404, "text/plain", bytes("Not Found"));
                return;
            }

            send(exchange, 200, asset.contentType(), asset.content());

        } catch (RequestFailure exception) {

            sendError(exchange, exception.status, exception.getMessage());

        } catch (SQLException exception) {

            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Unable to read the game database");

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Unable to prepare the requested game");

        } finally {

            exchange.close();

        }

    }

    private boolean trustedRequest(HttpExchange exchange) {

        String host = exchange.getRequestHeaders().getFirst("Host");
        String port = ":" + address().getPort();

        if (!("127.0.0.1" + port).equals(host)
                && !("localhost" + port).equals(host))
            return false;

        String origin = exchange.getRequestHeaders().getFirst("Origin");

        return origin == null || origin.equals("http://" + host);

    }

    private static Map<String, String> query(
            HttpExchange exchange,
            Set<String> allowed
    ) {

        String raw = exchange.getRequestURI().getRawQuery();

        if (raw == null || raw.isEmpty())
            return Map.of();

        if (raw.length() > 4096)
            throw new RequestFailure(400, "Query is too long");

        Map<String, String> result = new LinkedHashMap<>();

        try {

            for (String parameter : raw.split("&")) {

                String[] parts = parameter.split("=", 2);

                String key = URLDecoder.decode(
                        parts[0], StandardCharsets.UTF_8);

                String value = parts.length == 2
                        ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        : "";

                if (!allowed.contains(key)
                        || result.putIfAbsent(key, value) != null)
                    throw new RequestFailure(
                            400, "Unknown or repeated parameter");

            }

        } catch (IllegalArgumentException exception) {

            throw new RequestFailure(400, "Invalid query encoding");

        }

        return result;

    }


    // Database access \\

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    private String gamesJson(String search, int offset) throws SQLException {

        String pattern = "%" + search.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";

        StringBuilder out = new StringBuilder();
        long total;

        try (Connection connection = connect()) {

            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM catalog_game " + SEARCH)) {

                bindSearch(statement, pattern);

                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    total = rows.getLong(1);
                }

            }

            out.append("{\"total\":").append(total);
            out.append(",\"offset\":").append(offset);
            out.append(",\"limit\":").append(PAGE_SIZE);
            out.append(",\"games\":[");

            String sql = "SELECT " + GAME_COLUMNS
                    + " FROM catalog_game " + SEARCH
                    + " ORDER BY lower(coalesce(title, '')), catalog_id"
                    + " LIMIT ? OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {

                bindSearch(statement, pattern);
                statement.setInt(5, PAGE_SIZE);
                statement.setInt(6, offset);

                try (ResultSet rows = statement.executeQuery()) {

                    boolean first = true;

                    while (rows.next()) {

                        if (!first)
                            out.append(',');

                        out.append('{');
                        field(out, "id", rows.getString("catalog_id"));
                        out.append(',');
                        field(out, "title", rows.getString("title"));
                        out.append(',');
                        field(out, "competition", rows.getString("competition"));
                        out.append(',');
                        field(out, "source", rows.getString("source_type"));
                        out.append(',');
                        field(out, "externalKey", rows.getString("external_key"));
                        out.append(",\"phaseCount\":")
                                .append(rows.getInt("source_phase_count"));
                        out.append('}');

                        first = false;

                    }

                }

            }

            connection.commit();

        }

        return out.append("]}").toString();

    }

    private static void bindSearch(
            PreparedStatement statement,
            String pattern
    ) throws SQLException {

        for (int index = 1; index <= 4; index++)
            statement.setString(index, pattern);

    }

    private String gameJson(String id) throws SQLException {

        String payload;
        String title;
        String competition;
        String source;
        String externalKey;

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT source_type, external_key, title, competition,
                            source_payload
                     FROM catalog_game
                     WHERE catalog_id = ?
                     """)) {

            statement.setString(1, id);

            try (ResultSet rows = statement.executeQuery()) {

                if (!rows.next())
                    throw new RequestFailure(404, "Game no longer exists");

                source = rows.getString("source_type");
                externalKey = rows.getString("external_key");
                title = rows.getString("title");
                competition = rows.getString("competition");
                payload = rows.getString("source_payload");

            }

        }

        if (!"DIPLOBN".equals(source))
            throw new RequestFailure(
                    422, "This browser currently decodes DIPLOBN games only");

        DiploBNGame game;

        try {

            game = new DiploBNParser().parse(payload);

        } catch (IllegalArgumentException exception) {

            throw new RequestFailure(
                    422, "Stored game payload could not be decoded: "
                    + exception.getMessage());

        }

        StringBuilder out = new StringBuilder();

        out.append('{');
        field(out, "id", id);
        out.append(',');
        field(out, "title", title == null ? game.gameLabel() : title);
        out.append(',');
        field(out, "competition",
                competition == null ? game.competition() : competition);
        out.append(',');
        field(out, "externalKey", externalKey);

        out.append(",\"colors\":{");

        boolean first = true;

        for (Nation nation : Nation.values()) {

            if (!first)
                out.append(',');

            field(out, nation.name(), Constants.nationHex(nation));
            first = false;

        }

        out.append("},\"phases\":[");

        for (int index = 0; index < game.phases().size(); index++) {

            if (index > 0)
                out.append(',');

            appendPhase(out, game.phases().get(index), index);

        }

        return out.append("]}").toString();

    }


    // Snapshot serialization \\

    private static void appendPhase(
            StringBuilder out,
            DiploBNPhase phase,
            int index
    ) {

        out.append("{\"sourceIndex\":").append(index);
        out.append(",\"sourcePhase\":").append(phase.sourcePhase());
        out.append(',');
        field(out, "status", phase.sourceStatus());

        out.append(",\"board\":").append(
                BoardSnapshotJsonWriter.toJson(
                        BoardSnapshotMapper.from(
                                phase.sourcePhase() / 10,
                                phase.gamePhase().name(),
                                phase.board())));

        List<DisplayedOrder> orders = new ArrayList<>();

        if (phase.gamePhase().isAdjustment()) {

            for (AdjustmentOrder order : phase.adjustmentOrders()) {

                Province origin = order instanceof DisbandOrder disband
                        ? phase.board().locationOf(disband.unit())
                        : order.origin();

                orders.add(new DisplayedOrder(order, origin));

            }

        } else {

            /*
             * Source status does not decide whether stored movement
             * submissions should be discarded from the browser.
             */
            for (Order order : phase.movementOrders())
                orders.add(new DisplayedOrder(
                        order,
                        phase.board().locationOf(order.unit())));

        }

        /*
         * RetreatOrders may coexist with movement submissions in a source
         * snapshot. Include them whenever present.
         */
        for (RetreatOrder order : phase.retreatOrders())
            orders.add(new DisplayedOrder(
                    order,
                    phase.board().locationOf(order.unit())));

        out.append(",\"orders\":[");

        for (int orderIndex = 0; orderIndex < orders.size(); orderIndex++) {

            if (orderIndex > 0)
                out.append(',');

            DisplayedOrder displayed = orders.get(orderIndex);
            OrderForm order = displayed.order();

            out.append('{');
            field(out, "nation", order.owner().name());
            out.append(',');
            field(out, "type", order.orderType().name());
            out.append(',');
            field(out, "unitType",
                    order.unitType() == null ? null : order.unitType().name());
            out.append(',');
            field(out, "origin", name(displayed.origin()));
            out.append(',');
            field(out, "target", name(order.target()));
            out.append(',');
            field(out, "auxiliaryTarget", name(order.auxiliaryTarget()));
            out.append(',');
            field(out, "text", OrderForm.format(order, displayed.origin()));
            out.append('}');

        }

        List<DiploBNOrderResolution> annotations = new ArrayList<>();

        annotations.addAll(phase.resolutions());
        annotations.addAll(phase.retreatResolutions());

        out.append("],\"annotations\":[");

        for (int annotationIndex = 0;
             annotationIndex < annotations.size();
             annotationIndex++) {

            if (annotationIndex > 0)
                out.append(',');

            DiploBNOrderResolution annotation =
                    annotations.get(annotationIndex);

            out.append('{');
            field(out, "nation", annotation.nation().name());
            out.append(',');
            field(out, "origin", annotation.issuingProvince().name());

            out.append(",\"retreatOrder\":")
                    .append(annotation.retreatOrder());

            out.append(",\"successful\":").append(
                    annotation.successful() == null
                            ? "null" : annotation.successful().toString());

            out.append(',');
            field(out, "reason", annotation.reason());
            out.append('}');

        }

        out.append("]}");

    }


    // JSON and HTTP helpers \\

    private static void field(StringBuilder out, String key, String value) {

        appendString(out, key);
        out.append(':');

        if (value == null)
            out.append("null");
        else
            appendString(out, value);

    }

    private static String name(Province province) {
        return province == null ? null : province.name();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Asset asset(
            Path path,
            String contentType
    ) throws IOException {
        return new Asset(Files.readAllBytes(path), contentType);
    }

    private static void sendJson(
            HttpExchange exchange,
            String json
    ) throws IOException {
        send(exchange, 200, "application/json", bytes(json));
    }

    private static void sendError(
            HttpExchange exchange,
            int status,
            String message
    ) throws IOException {

        StringBuilder out = new StringBuilder("{");
        field(out, "error", message);
        out.append('}');

        send(exchange, status, "application/json", bytes(out.toString()));

    }

    private static void send(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] content
    ) throws IOException {

        exchange.getResponseHeaders().set(
                "Content-Type", contentType + "; charset=utf-8");

        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");

        exchange.sendResponseHeaders(status, content.length);

        try (var body = exchange.getResponseBody()) {
            body.write(content);
        }

    }


    // Internal types \\

    private record Asset(byte[] content, String contentType) { }

    private record DisplayedOrder(OrderForm order, Province origin) { }

    private static final class RequestFailure extends RuntimeException {

        private final int status;

        private RequestFailure(int status, String message) {
            super(message);
            this.status = status;
        }

    }


}