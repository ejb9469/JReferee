package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import game.Game;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;


/**
 * Loopback-only HTTP server for the JReferee board viewer.
 *
 * <p>Serves the current board state at {@code GET /api/board}, and serves the
 * static viewer assets rooted at {@code src/resources/ui}:</p>
 *
 * <ul>
 *     <li>{@code GET /} -> {@code index.html}</li>
 *     <li>{@code GET /ui/viewer.css}</li>
 *     <li>{@code GET /ui/viewer.js}</li>
 *     <li>{@code GET /ui/maps/standard.svg}</li>
 * </ul>
 *
 * <p>This server intentionally binds to {@code 127.0.0.1}; it is not exposed
 * to other machines on the local network.</p>
 */
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


    // member fields (final)
    private final Game game;
    private final HttpServer server;
    private final Path staticRoot;


    /**
     * Creates a loopback-only viewer server using the normal source-tree
     * resource directory.
     *
     * @param game current game to expose through {@code /api/board}
     * @param port port from 0 through 65535; 0 requests an ephemeral port
     */
    public BoardViewerServer(Game game, int port) {
        this(game, port, DEFAULT_STATIC_ROOT);
    }

    /**
     * Creates a loopback-only viewer server using a caller-supplied static
     * resource directory.
     *
     * @param game current game to expose through {@code /api/board}
     * @param port port from 0 through 65535; 0 requests an ephemeral port
     * @param staticRoot directory containing {@code index.html}, viewer assets,
     *                   and the {@code maps} directory
     */
    public BoardViewerServer(
            Game game,
            int port,
            Path staticRoot
    ) {

        this.game = Objects.requireNonNull(game, "game");
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
                    port
            );
            this.server = HttpServer.create(bindAddress, 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create board viewer server", exception);
        }

        server.createContext("/api/board", new BoardHandler());
        server.createContext("/", new StaticAssetHandler());
        server.setExecutor(null);

    }

    /**
     * Starts accepting HTTP requests.
     */
    public void start() {
        server.start();
    }

    /**
     * Stops the server after allowing active exchanges up to the requested
     * number of seconds to complete.
     *
     * @param delaySeconds non-negative shutdown delay
     */
    public void stop(int delaySeconds) {
        if (delaySeconds < 0)
            throw new IllegalArgumentException("delaySeconds must not be negative");
        server.stop(delaySeconds);
    }

    /**
     * Returns the actual loopback address. This is useful when constructed
     * with port {@code 0}, because the operating system chooses the port.
     */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop(0);
    }

    private final class BoardHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!"GET".equals(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }

            try {
                BoardSnapshot snapshot = BoardSnapshotMapper.from(game);
                byte[] payload = BoardSnapshotJsonWriter.toJsonBytes(snapshot);
                writeResponse(
                        exchange,
                        200,
                        "application/json; charset=utf-8",
                        payload
                );
            } catch (RuntimeException exception) {
                writeResponse(
                        exchange,
                        500,
                        "application/json; charset=utf-8",
                        "{\"error\":\"Internal Server Error\"}"
                                .getBytes(StandardCharsets.UTF_8)
                );
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

        /**
         * Maps browser-facing routes to files below {@code staticRoot}.
         *
         * <p>The browser requests {@code /ui/viewer.js}, but the source-tree
         * file is {@code src/resources/ui/viewer.js}; therefore the leading
         * {@code /ui/} route prefix is removed before resolving the path.</p>
         */
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

            /*
             * Do not allow paths such as /ui/../../README.md to escape the
             * viewer-resource directory.
             */
            if (!candidate.startsWith(staticRoot))
                return null;

            return candidate;
        }

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

        String extension = filename
                .substring(extensionStart + 1)
                .toLowerCase();

        return CONTENT_TYPES.getOrDefault(
                extension,
                "application/octet-stream");

    }

}