package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import game.Game;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

public final class BoardViewerServer implements AutoCloseable {

    private final Game game;
    private final HttpServer server;

    public BoardViewerServer(Game game, int port) {
        this.game = Objects.requireNonNull(game, "game");

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("port out of range: " + port);

        try {
            InetSocketAddress bindAddress = new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    port);

            this.server = HttpServer.create(bindAddress, 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create board viewer server", exception);
        }

        server.createContext("/api/board", new BoardHandler());
        server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

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
                exchange.getResponseHeaders().set("Allow", "GET");
                writeJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                BoardSnapshot snapshot = BoardSnapshotMapper.from(game);
                byte[] payload = BoardSnapshotJsonWriter.toJsonBytes(snapshot);

                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, payload.length);

                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(payload);
                }
            } catch (RuntimeException exception) {
                writeJsonResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            }
        }

        private void writeJsonResponse(HttpExchange exchange, int status, String payload) throws IOException {
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);

            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        }
    }

}
