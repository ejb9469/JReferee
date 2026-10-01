package _app;

import analysis.openings.Catalog;
import ui.BoardViewerServer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;


public class OpeningBrowserApp {

    private OpeningBrowserApp() {     }


    public static void main(String[] args) throws SQLException, ClassNotFoundException {

        Path databasePath = Path.of("data", "diplobn-catalog.sqlite");
        int port = 0;

        for (int index = 0; index < args.length; index++) {

            String argument = args[index];

            if (index + 1 >= args.length)
                throw usage();

            switch (argument) {
                case "--database" -> databasePath = Path.of(args[++index]);
                case "--port" -> port = Integer.parseInt(args[++index]);
                default -> throw usage();
            }

        }

        System.out.println("Loading opening catalog...");

        // The database connection is closed before the server starts.
        Catalog catalog = Catalog.load(databasePath);

        try (BoardViewerServer server = new BoardViewerServer(catalog, port)) {

            Thread shutdown = new Thread(server::close, "opening-browser-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);

            try {

                server.start();

                System.out.println("Openings browser: http://127.0.0.1:" + server.address().getPort());
                System.out.println("Distinct national openings: " + catalog.entries().size());
                System.out.println("Restart to reload the catalog. Press Ctrl+C to stop.");

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
                "Usage: OpeningBrowserApp [--database <path>] [--port <0-65535>]");
    }

}