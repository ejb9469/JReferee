package _app;

import game.Game;
import game.StandardGameFactory;
import ui.BoardViewerServer;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;


/**
 * Entry point for the local JReferee board viewer.
 *
 * <p>Run with an explicit port:</p>
 *
 * <pre>
 * BoardViewerApp 8080
 * </pre>
 *
 * <p>Or run without arguments to use an operating-system-selected port.</p>
 */
public final class BoardViewerApp {

    private static final int EPHEMERAL_PORT = 0;

    private BoardViewerApp() {
    }

    public static void main(String[] args) {

        int port = parsePort(args);
        Game game = StandardGameFactory.create1901();

        try (BoardViewerServer server = new BoardViewerServer(game, port)) {

            server.start();

            InetSocketAddress address = server.address();
            String baseUrl = "http://127.0.0.1:"
                    + address.getPort();

            System.out.println();
            System.out.println("JReferee board viewer started.");
            System.out.println("Viewer:    " + baseUrl + "/");
            System.out.println("Board API: " + baseUrl + "/api/board");
            System.out.println("Press Ctrl+C to stop.");
            System.out.println();

            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            server::close,
                            "jreferee-board-viewer-shutdown"
                    )
            );

            /*
             * Keep the main thread alive while HttpServer handles requests on
             * its own executor threads. Ctrl+C triggers the shutdown hook.
             */
            new CountDownLatch(1).await();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

    }

    private static int parsePort(String[] args) {

        if (args.length == 0)
            return EPHEMERAL_PORT;

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: BoardViewerApp [port]");

        try {
            int port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535)
                throw new IllegalArgumentException("port must be between 0 and 65535");

            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("port must be a whole number", exception);
        }

    }
}