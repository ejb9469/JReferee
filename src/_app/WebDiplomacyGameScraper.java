package _app;

import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;
import io.catalog.SourceFingerprints;
import io.persistence.SQLiteCatalogStore;
import parsing.webdiplomacy.WebDiplomacyGameClient;
import parsing.webdiplomacy.WebDiplomacyGameClient.SkippedGameException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Catalogs an approved range of public webDiplomacy game snapshots
 * in random order.
 */
public final class WebDiplomacyGameScraper {


    private WebDiplomacyGameScraper() { }


    public static void main(String[] args) throws Exception {

        String usage = "Usage: WebDiplomacyGameScraper <first-id> <last-id> [database-path]";
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(usage);
            return;
        }
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException(usage);

        long first = Long.parseLong(args[0]);
        long last = Long.parseLong(args[1]);
        checkRange(first, last);
        Path database = (args.length == 3 ? Path.of(args[2])
                : Path.of("data", "webdiplomacy-catalog.sqlite")).toAbsolutePath().normalize();
        System.out.println("Database file: " + database);
        System.out.println("Raw acquisition only; no training export or replay validation.");
        try (WebDiplomacyGameClient client = new WebDiplomacyGameClient();
             GameCatalog catalog = new GameCatalog(new SQLiteCatalogStore(database))) {
            scrape(first, last, catalog, client);
        }

    }

    public static void scrape(
            long first, long last, GameCatalog catalog, WebDiplomacyGameClient client
    ) throws IOException, InterruptedException {

        checkRange(first, last);
        List<Long> candidates = new ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            candidates.add(id);
        }
        Collections.shuffle(candidates);

        long imported = 0, existing = 0, skipped = 0, current = first;
        boolean complete = false;
        try {
            for (long id : candidates) {
                current = id;
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Scrape interrupted");
                String key = Long.toString(id);
                if (catalog.find(GameSource.WEBDIPLOMACY, key).isPresent()) {
                    existing++;
                    continue;
                }

                WebDiplomacyGameClient.DownloadedGame game;
                try {
                    game = client.downloadGame(id);
                } catch (SkippedGameException exception) {
                    skipped++;
                    System.out.printf("SKIPPED GameID=%d: %s%n", id, exception.reason());
                    continue;
                }
                catalog.catalog(
                        new GameSourceReference(GameSource.WEBDIPLOMACY, key,
                                game.canonicalUri(), game.canonicalUri()),
                        game.title(), null, game.sourceJson(),
                        SourceFingerprints.sha256Hex(game.sourceJson()), game.phaseCount());
                imported++;
                System.out.printf("IMPORTED GameID=%d phases=%d totalImported=%d%n",
                        id, game.phaseCount(), imported);
            }
            complete = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            System.out.printf("Imported=%d Existing=%d Skipped=%d Complete=%s LastGameID=%d%n",
                    imported, existing, skipped, complete, current);
        }

    }

    private static void checkRange(long first, long last) {
        if (first <= 0 || first > last || last > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Expected 1 <= first-id <= last-id <= 2147483647");
    }


}