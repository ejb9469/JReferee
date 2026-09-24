package _app;

import game.Game;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import ui.BoardViewerServer;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;


/**
 * Entry point for the local JReferee board viewer.
 *
 * <p>The command-line entry point starts the viewer with the standard initial
 * board and a GameRecord containing that initial Spring 1901 position. To
 * replay a resolved game, another application entry point should create a
 * GameRecord through GameRecordBuilder and call {@link #run(Game, GameRecord,
 * int)}.</p>
 *
 * <p>Run with an explicit port:</p>
 *
 * <pre>
 * BoardViewerApp 8080
 * </pre>
 *
 * <p>Run without arguments to request an operating-system-selected port.</p>
 */
public final class BoardViewerApp {


    private static final int EPHEMERAL_PORT = 0;
    private static final String STANDARD_RULESET_ID =
            "jreferee-standard-v1";


    private BoardViewerApp() {  }


    /**
     * Starts a viewer for the standard initial Spring 1901 position.
     *
     * <p>This default path has one replay position because no phases have been
     * resolved yet. A caller that resolves phases through GameRecordBuilder can
     * pass its completed GameRecord to {@link #run(Game, GameRecord, int)} to
     * make the complete history available at {@code /api/history}.</p>
     */
    public static void main(String[] args) {

        int port = parsePort(args);

        Game game = StandardGameFactory.create1901();

        GameRecord initialRecord = new GameRecordBuilder(
                UUID.randomUUID(),
                STANDARD_RULESET_ID,
                game
        ).build();

        run(game, initialRecord, port);

    }


    /**
     * Starts the board-viewer HTTP server for a live game and an immutable
     * replay record.
     *
     * <p>The live {@code game} supplies {@code GET /api/board}. The
     * {@code gameRecord} supplies {@code GET /api/history}.</p>
     *
     * <p>For a meaningful multi-season history, construct the record by
     * resolving every phase through GameRecordBuilder:</p>
     *
     * <pre>
     * Game game = StandardGameFactory.create1901();
     *
     * GameRecordBuilder archive = new GameRecordBuilder(
     *         UUID.randomUUID(),
     *         "jreferee-standard-v1",
     *         game
     * );
     *
     * archive.resolveMovement(springOrders, springMoment);
     * archive.resolveMovement(fallOrders, fallMoment);
     *
     * GameRecord record = archive.build();
     * BoardViewerApp.run(game, record, 8080);
     * </pre>
     *
     * @param game current live game state
     * @param gameRecord immutable record used for replay navigation
     * @param port loopback port from 0 through 65535; 0 chooses an ephemeral
     *             available port
     */
    public static void run(
            Game game, GameRecord gameRecord, int port) {

        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(gameRecord, "gameRecord");

        try (BoardViewerServer server =
                     new BoardViewerServer(game, gameRecord, port))
        {

            server.start();

            InetSocketAddress address = server.address();

            String baseUrl = "http://127.0.0.1:"
                    + address.getPort();

            System.out.println();
            System.out.println("JReferee board viewer started.");
            System.out.println("Viewer:     " + baseUrl + "/");
            System.out.println("Board API:  " + baseUrl + "/api/board");
            System.out.println("History API:" + baseUrl + "/api/history");
            System.out.println(
                    "Replay positions: "
                            + (gameRecord.resolvedPhases().size() + 1)
            );
            System.out.println("Press Ctrl+C to stop.");
            System.out.println();

            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            server::close,
                            "jreferee-board-viewer-shutdown"
                    )
            );

            /*
             * HttpServer processes requests on its executor threads. Keep the
             * main thread alive until Ctrl+C invokes the shutdown hook.
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