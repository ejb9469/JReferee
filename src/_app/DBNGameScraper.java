package _app;

import domain.Constants;
import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;
import io.persistence.SQLiteCatalogStore;
import parsing.diplobn.DiploBNDownloadedGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.ParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Imports an inclusive range of candidate DiploBN GameIDs in random order.
 * Existing catalog entries are left untouched.
 */
public final class DBNGameScraper {


    private static final long DELAY_MILLIS = 500;


    private DBNGameScraper() { }


    public static void main(String[] args) throws Exception {

        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: DBNGameScraper <first-id> <last-id> [database-path]");
        }

        long first = Long.parseLong(args[0]);
        long last = Long.parseLong(args[1]);

        // Five digits is a scanning assumption, not a verified site rule.
        if (first < 10_000 || last > 99_999 || first > last) {
            throw new IllegalArgumentException(
                    "Expected 10000 <= first-id <= last-id <= 99999");
        }

        Path database = (args.length == 3
                ? Path.of(args[2])
                : Path.of("data", "diplobn-catalog.sqlite"))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database file: " + database);

        List<Long> candidates = new ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            candidates.add(id);
        }
        Collections.shuffle(candidates);

        DiploBNGameClient client = new DiploBNGameClient();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        int imported = 0;
        int existing = 0;
        int unavailable = 0;
        boolean requested = false;

        try (GameCatalog catalog =
                     new GameCatalog(new SQLiteCatalogStore(database))) {

            for (long id : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Scrape interrupted");
                }

                String key = Long.toString(id);

                if (catalog.find(GameSource.DIPLOBN, key).isPresent()) {
                    existing++;
                    continue;
                }

                if (requested) {
                    Thread.sleep(DELAY_MILLIS);
                }
                requested = true;

                DiploBNDownloadedGame downloaded;

                try {
                    downloaded = client.downloadGame(id);
                } catch (ParseException exception) {
                    unavailable++;
                    System.err.printf(
                            "UNSUPPORTED GameID=%d: %s%n",
                            id, exception.getMessage());
                    continue;
                } catch (IOException exception) {
                    /*
                     * The existing client does not expose a typed
                     * "game not found" result. This specific message means
                     * no game payload was returned, not proven nonexistence.
                     */
                    String noPayload =
                            "DiploBN response did not contain a GamePhases object"
                                    + " for GameID " + id;

                    if (noPayload.equals(exception.getMessage())) {
                        unavailable++;
                        System.err.printf(
                                "NO PAYLOAD GameID=%d%n", id);
                        continue;
                    }

                    // Stop on HTTP errors, timeouts, malformed hub JSON,
                    // or other transport failures.
                    throw new IOException(
                            "Stopped at GameID=" + id
                                    + "; earlier imports remain saved. "
                                    + exception.getMessage(),
                            exception);
                }

                var game = downloaded.game();

                if (game.phases().isEmpty()) {
                    unavailable++;
                    System.err.printf("EMPTY GameID=%d%n", id);
                    continue;
                }

                URI uri = URI.create(downloaded.canonicalUrl());
                String payload = downloaded.sourceJson();
                String fingerprint = HexFormat.of().formatHex(
                        sha256.digest(
                                payload.getBytes(StandardCharsets.UTF_8)));

                // Persistence failures deliberately abort the run.
                catalog.catalog(
                        new GameSourceReference(
                                GameSource.DIPLOBN, key, uri, uri),
                        game.gameLabel(),
                        game.competition(),
                        payload,
                        fingerprint,
                        game.phases().size());

                imported++;
                System.out.printf(
                        "%sIMPORTED%s GameID=%d phases=%d%n",
                        Constants.ANSI_GREEN, Constants.ANSI_RESET,
                        id, game.phases().size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            System.out.printf(
                    "Imported=%d Existing=%d Unavailable/unsupported=%d%n",
                    imported, existing, unavailable);
        }

    }


}