package ui;

import agents.execution.GameRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import contracts.OrderForm;
import domain.Constants;
import domain.Nation;
import domain.Province;
import game.GameState_Retreats;
import game.record.*;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNOrderResolution;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementResult;
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
 * Read-only browser for cataloged snapshots or a completed agent game.
 *
 * <p>Stored snapshots are not replayed or adjudicated. Movement snapshots
 * are independently passed to the currently registered detectives.
 * Recorded games retain their actual orders and outcomes; browsing them
 * performs no additional adjudication and requires no database.</p>
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
    private final GameRunner.Result recordedGame;
    private final String recordedPayload;
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

        this(databaseUrl(database), null, port, assetRoot);

    }

    public GameBrowserServer(
            GameRunner.Result result,
            int port,
            Path assetRoot
    ) throws IOException {
        this(null, Objects.requireNonNull(result, "result"), port, assetRoot);
    }

    private static String databaseUrl(Path database)
            throws SQLException, ClassNotFoundException {

        Objects.requireNonNull(database, "database");
        Path databasePath = database.toAbsolutePath().normalize();

        if (!Files.isRegularFile(databasePath))
            throw new IllegalArgumentException(
                    "Database not found: " + databasePath);

        Class.forName("org.sqlite.JDBC");

        String url =
                "jdbc:sqlite:" + databasePath.toUri().toASCIIString() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery(
                     "SELECT " + GAME_COLUMNS
                             + ", source_payload FROM catalog_game LIMIT 0")) {
            // Verify the existing schema without initializing or changing it.
        }

        return url;

    }

    private GameBrowserServer(
            String databaseUrl, GameRunner.Result recordedGame, int port, Path assetRoot
    ) throws IOException {

        Objects.requireNonNull(assetRoot, "assetRoot");
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Port must be between 0 and 65535");

        this.databaseUrl = databaseUrl;
        this.recordedGame = recordedGame;

        this.assets = Map.of(
                "/", asset(assetRoot.resolve("games.html"), "text/html"),
                "/ui/games.js", asset(
                        assetRoot.resolve("games.js"), "text/javascript"),
                "/ui/games-detectives.js", asset(
                        assetRoot.resolve("games-detectives.js"), "text/javascript"),
                "/ui/games.css", asset(
                        assetRoot.resolve("games.css"), "text/css"),
                "/ui/games-detectives.css", asset(
                        assetRoot.resolve("games-detectives.css"), "text/css"),
                "/ui/openings.css", asset(
                        assetRoot.resolve("openings.css"), "text/css"),
                "/ui/maps/standard.svg", asset(
                        assetRoot.resolve("maps/standard.svg"), "image/svg+xml"));

        this.recordedPayload = recordedGame == null ? null : recordedGameJson();

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
                    offset = Integer.parseInt(query.getOrDefault("offset", "0"));
                } catch (NumberFormatException exception) {
                    throw new RequestFailure(400, "Invalid offset");
                }

                if (offset < 0)
                    throw new RequestFailure(400, "Offset must not be negative");

                sendJson(exchange, gamesJson(search, offset));
                return;

            }

            if (path.equals("/api/game")) {

                String id = query(exchange, Set.of("id")).get("id");

                if (id == null || id.isBlank() || id.length() > 128)
                    throw new RequestFailure(400, "A catalog ID is required");

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

        if (recordedGame != null)
            return recordedGamesJson(search, offset);

        String pattern = "%" + search.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";

        StringBuilder out = new StringBuilder();

        try (Connection connection = connect()) {

            connection.setAutoCommit(false);
            long total;

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

        if (recordedGame != null) {
            if (!recordedGame.record().id().toString().equals(id))
                throw new RequestFailure(404, "Recorded game not found");
            return recordedPayload;
        }

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

        StringBuilder out = new StringBuilder("{");

        field(out, "id", id);
        out.append(',');
        field(out, "title", title == null ? game.gameLabel() : title);
        out.append(',');
        field(out, "competition",
                competition == null ? game.competition() : competition);
        out.append(',');
        field(out, "externalKey", externalKey);
        out.append(',');
        field(out, "source", source);

        appendColors(out);
        out.append(",\"phases\":[");

        for (int index = 0; index < game.phases().size(); index++) {

            if (index > 0)
                out.append(',');

            appendPhase(out, game.phases().get(index), index);

        }

        return out.append("]}").toString();

    }

    private static void appendColors(StringBuilder out) {
        out.append(",\"colors\":{");
        boolean first = true;

        for (Nation nation : Nation.values()) {

            if (!first)
                out.append(',');

            field(out, nation.name(), Constants.nationHex(nation));
            first = false;

        }

        out.append('}');
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

            for (Order order : phase.movementOrders())
                orders.add(new DisplayedOrder(
                        order, phase.board().locationOf(order.unit())));

        }

        // Preserve retreat submissions even on combined source snapshots.
        for (RetreatOrder order : phase.retreatOrders())
            orders.add(new DisplayedOrder(
                    order, phase.board().locationOf(order.unit())));

        appendOrders(out, orders);

        List<DiploBNOrderResolution> annotations = new ArrayList<>();
        annotations.addAll(phase.resolutions());
        annotations.addAll(phase.retreatResolutions());

        out.append(",\"annotations\":[");

        for (int indexOfAnnotation = 0; indexOfAnnotation < annotations.size(); indexOfAnnotation++) {

            if (indexOfAnnotation > 0)
                out.append(',');

            DiploBNOrderResolution annotation = annotations.get(indexOfAnnotation);
            appendAnnotation(out, annotation.nation(), annotation.issuingProvince(),
                    annotation.retreatOrder(), annotation.successful(), annotation.reason());
        }

        out.append("],\"dislodged\":[],\"investigation\":")
                .append(GameInvestigationJsonWriter.toJson(phase));

        out.append('}');

    }


    // Recorded agent games \\

    private String recordedTitle() {
        return "Agent game - seed " + recordedGame.options().seed();
    }

    private String recordedGamesJson(String search, int offset) {
        String id = recordedGame.record().id().toString();
        boolean matches = (recordedTitle() + " " + id).toLowerCase(Locale.ROOT)
                .contains(search.toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder("{\"total\":").append(matches ? 1 : 0);
        out.append(",\"offset\":").append(offset);
        out.append(",\"limit\":").append(PAGE_SIZE).append(',');
        field(out, "autoSelect", id);
        out.append(",\"games\":[");
        if (matches && offset == 0) {
            out.append('{');
            field(out, "id", id);
            out.append(',');
            field(out, "title", recordedTitle());
            out.append(",\"competition\":null,");
            field(out, "source", "JREFEREE");
            out.append(',');
            field(out, "externalKey", id);
            out.append(",\"phaseCount\":").append(recordedGame.record().resolvedPhases().size() + 1);
            out.append('}');
        }
        return out.append("]}").toString();
    }

    private String recordedGameJson() {
        GameRecord record = recordedGame.record();
        StringBuilder out = new StringBuilder("{");
        field(out, "id", record.id().toString());
        out.append(',');
        field(out, "title", recordedTitle());
        out.append(",\"competition\":null,");
        field(out, "externalKey", record.id().toString());
        out.append(',');
        field(out, "source", "JREFEREE");
        out.append(',');
        field(out, "summary", recordedGame.reason() + " at " + recordedGame.state().moment()
                + "; " + record.rulesetId()
                + recordedGame.winner().map(nation -> "; winner: " + nation).orElse(""));
        out.append(',');
        field(out, "scoring", recordedGame.options().scoring().name());
        out.append(",\"scores\":{");
        boolean first = true;
        for (var entry : recordedGame.scores().entrySet()) {
            if (!first)
                out.append(',');
            appendString(out, entry.getKey().name());
            out.append(':').append(entry.getValue());
            first = false;
        }
        out.append('}');
        appendColors(out);
        out.append(",\"phases\":[");
        for (int index = 0; index < record.resolvedPhases().size(); index++) {
            if (index > 0)
                out.append(',');
            appendRecordedPhase(out, record.resolvedPhases().get(index), index, record.rulesetId());
        }
        if (!record.resolvedPhases().isEmpty())
            out.append(',');
        appendPosition(out, record.resolvedPhases().size(),
                BoardSnapshotMapper.from(recordedGame.state().moment(), recordedGame.state().board()),
                "FINAL - " + recordedGame.reason());
        out.append(",\"orders\":[],\"annotations\":[]");
        appendDislodged(out, recordedGame.state() instanceof GameState_Retreats retreats
                ? retreats.movementResult().dislodgements().values() : List.of());
        out.append(",\"investigation\":").append(GameInvestigationJsonWriter.unavailable(
                "NOT_APPLICABLE", "Final position; no further orders were submitted."));
        return out.append("}]}").toString();
    }

    private static void appendPosition(StringBuilder out, int index, BoardSnapshot board, String status) {
        out.append("{\"sourceIndex\":").append(index).append(",\"sourcePhase\":null,");
        field(out, "status", status);
        out.append(",\"board\":").append(BoardSnapshotJsonWriter.toJson(board));
    }

    private static void appendRecordedPhase(StringBuilder out, ResolvedPhaseRecord phase,
                                            int index, String rulesetId) {
        appendPosition(out, index,
                BoardSnapshotMapper.from(phase.gameMoment(), phase.boardBefore()), "RESOLVED");
        List<DisplayedOrder> orders = new ArrayList<>();
        switch (phase) {
            case MovementPhaseRecord movement -> movement.submittedOrders().forEach(order ->
                    orders.add(new DisplayedOrder(order, phase.boardBefore().locationOf(order.unit()))));
            case RetreatPhaseRecord retreat -> retreat.submittedOrders().forEach(order ->
                    orders.add(new DisplayedOrder(order,
                            retreat.result().movementResult().dislodgementOf(order.unit()).displacedFrom())));
            case AdjustmentPhaseRecord adjustment -> adjustment.submittedOrders().forEach(order ->
                    orders.add(new DisplayedOrder(order, order instanceof DisbandOrder disband
                            ? phase.boardBefore().locationOf(disband.unit()) : order.origin())));
        }
        appendOrders(out, orders);
        StringJoiner annotations = new StringJoiner(",");
        switch (phase) {
            case MovementPhaseRecord movement -> movement.result().outcomes().forEach((unit, outcome) ->
                    annotations.add(annotation(unit.owner(), phase.boardBefore().locationOf(unit), false,
                            outcome.succeeded(), outcome.effectiveType().name()
                                    + (outcome.submitted() ? "" : " (default order)")
                                    + (outcome.rewrittenBySzykman() ? "; Szykman rewrite" : "")
                                    + (movement.result().dislodgements().containsKey(unit) ? "; DISLODGED" : ""))));
            case RetreatPhaseRecord retreat -> retreat.result().outcomes().forEach((unit, outcome) ->
                    annotations.add(annotation(unit.owner(),
                            retreat.result().movementResult().dislodgementOf(unit).displacedFrom(),
                            true, outcome.succeeded(), outcome.status().name())));
            case AdjustmentPhaseRecord adjustment -> {
                for (var outcome : adjustment.result().outcomes()) {
                    AdjustmentOrder order = outcome.order();
                    Province origin = order instanceof DisbandOrder disband
                            ? phase.boardBefore().locationOf(disband.unit()) : order.origin();
                    annotations.add(annotation(order.owner(), origin, false, outcome.accepted(),
                            order.orderType() + ": " + outcome.status()));
                }
                Set<UnitId> submittedDisbands = new HashSet<>();
                for (AdjustmentOrder order : adjustment.submittedOrders())
                    if (order instanceof DisbandOrder disband)
                        submittedDisbands.add(disband.unit());
                for (UnitId unit : adjustment.result().disbandedUnits())
                    if (!submittedDisbands.contains(unit))
                        annotations.add(annotation(unit.owner(), phase.boardBefore().locationOf(unit),
                                false, true, "AUTOMATIC_DISBAND"));
            }
        }
        out.append(",\"annotations\":[").append(annotations).append(']');
        appendDislodged(out, phase instanceof RetreatPhaseRecord retreat
                ? retreat.result().movementResult().dislodgements().values() : List.of());
        out.append(",\"investigation\":").append(phase instanceof MovementPhaseRecord movement
                ? GameInvestigationJsonWriter.toJson(movement, rulesetId)
                : GameInvestigationJsonWriter.unavailable("NOT_APPLICABLE",
                "Movement investigators do not inspect retreat or adjustment snapshots."));
        out.append('}');
    }

    private static void appendDislodged(StringBuilder out, Collection<MovementResult.Dislodgement> units) {
        out.append(",\"dislodged\":[");
        boolean first = true;
        for (var unit : units) {
            if (!first)
                out.append(',');
            out.append('{');
            field(out, "nation", unit.unit().owner().name());
            out.append(',');
            field(out, "type", unit.unit().unitType().name());
            out.append(',');
            field(out, "province", unit.displacedFrom().name());
            out.append('}');
            first = false;
        }
        out.append(']');
    }

    private static String annotation(Nation nation, Province origin, boolean retreat,
                                     Boolean succeeded, String reason) {
        StringBuilder out = new StringBuilder();
        appendAnnotation(out, nation, origin, retreat, succeeded, reason);
        return out.toString();
    }

    private static void appendAnnotation(StringBuilder out, Nation nation, Province origin,
                                         boolean retreat, Boolean succeeded, String reason) {
        out.append('{');
        field(out, "nation", nation.name());
        out.append(',');
        field(out, "origin", name(origin));
        out.append(",\"retreatOrder\":").append(retreat);
        out.append(",\"successful\":").append(succeeded);
        out.append(',');
        field(out, "reason", reason);
        out.append('}');
    }

    private static void appendOrders(StringBuilder out, List<DisplayedOrder> orders) {
        out.append(",\"orders\":[");
        for (int index = 0; index < orders.size(); index++) {
            if (index > 0)
                out.append(',');
            DisplayedOrder displayed = orders.get(index);
            OrderForm order = displayed.order();
            out.append('{');
            field(out, "nation", order.owner().name());
            out.append(',');
            field(out, "type", order.orderType().name());
            out.append(',');
            field(out, "unitType", order.unitType() == null ? null : order.unitType().name());
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
        out.append(']');
    }


    // Serialization and HTTP helpers \\

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

    private static Asset asset(Path path, String contentType) throws IOException {
        return new Asset(Files.readAllBytes(path), contentType);
    }

    private static void sendJson(HttpExchange exchange, String json)
            throws IOException {
        send(exchange, 200, "application/json", bytes(json));
    }

    private static void sendError(
            HttpExchange exchange,
            int status,
            String message
    ) throws IOException {

        StringBuilder out = new StringBuilder("{");
        field(out, "error", message);

        send(exchange, status, "application/json", bytes(out.append('}').toString()));

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