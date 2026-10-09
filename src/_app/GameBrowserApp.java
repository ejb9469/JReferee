package _app;

import ui.GameBrowserServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;


/**
 * Opens a read-only browser for games in the local catalog database.
 */
public final class GameBrowserApp {


    // Construction \\

    private GameBrowserApp() {  }


    // Application entry point \\

    public static void main(String[] args) throws Exception {

        Path database = Path.of("data", "diplobn-catalog.sqlite");
        Path assets = Path.of("src", "resources", "ui");
        int port = 0;

        for (int index = 0; index < args.length; index++) {

            if (index + 1 >= args.length)
                throw usage();

            switch (args[index]) {
                case "--database" -> database = Path.of(args[++index]);
                case "--port" -> port = Integer.parseInt(args[++index]);
                case "--assets" -> assets = Path.of(args[++index]);
                default -> throw usage();
            }

        }

        try (GameBrowserServer server =
                     new GameBrowserServer(database, port, assets)) {

            Thread shutdown = new Thread(
                    server::close,
                    "game-browser-shutdown");

            Runtime.getRuntime().addShutdownHook(shutdown);

            try {

                server.start();

                System.out.println();
                System.out.println("Database: " + database.toAbsolutePath());
                System.out.println("Database access: read-only");

                System.out.println(
                        "Game browser: http://127.0.0.1:"
                                + server.address().getPort());

                System.out.println(
                        "Source snapshots are displayed without replay or adjudication.");

                System.out.println(
                        "Restart the server after changing browser assets.");

                System.out.println("Press Ctrl+C to stop.");

                new CountDownLatch(1).await();

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

            } finally {

                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown has already begun.
                }

            }

        }

    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: GameBrowserApp "
                        + "[--database <path>] "
                        + "[--port <0-65535>] "
                        + "[--assets <ui-directory>]");
    }


}