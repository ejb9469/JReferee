package _app;

import ui.ArchiveReplayServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Opens completed archives without models, search or a database. */
public final class ArchiveReplayApp {

    private ArchiveReplayApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException(
                    "Usage: ArchiveReplayApp <completed-game-or-match-directory> [port] [ui-directory]");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        Path assets = args.length > 2 ? Path.of(args[2]) : Path.of("src/resources/ui");
        try (ArchiveReplayServer server = new ArchiveReplayServer(
                Path.of(args[0]), port, assets, System.out::println)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start();
            System.out.println("Read-only replay: http://127.0.0.1:" + server.address().getPort() + "/");
            System.out.println("No models, searches, training or archive writes. Stop this application to close the viewer.");
            new CountDownLatch(1).await();
        }

    }

}