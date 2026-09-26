package io.catalog.diplobn;

import io.catalog.CatalogImporter;
import io.catalog.CatalogManifestReader;
import parsing.diplobn.DiploBNDownloadedGame;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.JsonReader;
import parsing.diplobn.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Reads a DiploBN game-catalog manifest and imports every listed game.
 *
 * <p>The default manifest location is
 * {@code src/resources/save_games/diplobn-games.json}. Each entry requires a
 * DiploBN game-page URL and may include tags and notes:</p>
 *
 * <pre>
 * {
 *   "games": [
 *     {
 *       "url": "https://diplobn.com/game/?GameID=12990",
 *       "tags": ["regression", "head-to-head"],
 *       "notes": "Optional local catalog note."
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Each entry is processed independently. An invalid manifest entry or a
 * failed remote import is reported in the returned {@link ImportReport} and
 * does not prevent later entries from being attempted.</p>
 *
 * <p>This class deliberately does not define persistence behavior. Its
 * injected {@link DiploBNGameImporter} is responsible for passing successfully
 * downloaded games to the catalog and persistence infrastructure.</p>
 */
public final class DiploBNGameCatalogFileImporter
        implements CatalogImporter<
        DiploBNGameCatalogFileImporter.ImportReport>,
        CatalogManifestReader<
                DiploBNGameCatalogFileImporter.ManifestEntry> {


    public static final Path DEFAULT_MANIFEST_PATH = Path.of(
            "src",
            "resources",
            "save_games",
            "diplobn-games.json");


    private final Path manifestPath;
    private final DiploBNGameImporter gameImporter;


    /**
     * Creates an importer for the standard catalog-manifest location.
     *
     * <p>This constructor downloads and parses each source game, but does not
     * persist it. Use the two-argument constructor once a catalog-backed
     * {@link DiploBNGameImporter} implementation is available.</p>
     */
    public DiploBNGameCatalogFileImporter() {
        this(
                DEFAULT_MANIFEST_PATH,
                new DownloadOnlyGameImporter(
                        new DiploBNGameClient()));
    }

    /**
     * Creates an importer for a caller-supplied manifest path.
     *
     * <p>This constructor downloads and parses each source game, but does not
     * persist it. Use the two-argument constructor once a catalog-backed
     * {@link DiploBNGameImporter} implementation is available.</p>
     */
    public DiploBNGameCatalogFileImporter(
            Path manifestPath
    ) {
        this(
                manifestPath,
                new DownloadOnlyGameImporter(
                        new DiploBNGameClient()));
    }

    /**
     * Creates an importer with a caller-defined catalog-import operation.
     *
     * @param manifestPath source JSON manifest
     * @param gameImporter operation performed for every valid manifest entry
     */
    public DiploBNGameCatalogFileImporter(
            Path manifestPath,
            DiploBNGameImporter gameImporter
    ) {
        this.manifestPath = Objects.requireNonNull(
                manifestPath,
                "manifestPath");

        this.gameImporter = Objects.requireNonNull(
                gameImporter,
                "gameImporter");
    }


    /**
     * Reads and validates all entries in the configured manifest.
     *
     * <p>This strict operation throws if any entry is malformed. Use
     * {@link #importGames()} to continue processing later entries after an
     * individual malformed entry.</p>
     *
     * @return immutable manifest entries
     */
    @Override
    public List<ManifestEntry> read() {

        List<Object> rawEntries = readRawEntries();

        List<ManifestEntry> entries = new ArrayList<>();

        for (int index = 0; index < rawEntries.size(); index++) {
            entries.add(
                    parseEntry(
                            rawEntries.get(index),
                            "$.games[" + index + "]"
                    )
            );
        }

        return List.copyOf(entries);

    }

    /**
     * Source manifest location.
     *
     * @return manifest path
     */
    @Override
    public Path path() {
        return manifestPath;
    }

    /**
     * Reads the configured manifest and processes all listed entries.
     *
     * @return immutable report of successful imports and failures
     */
    @Override
    public ImportReport importGames() {

        try {
            return importRawEntries(readRawEntries());
        } catch (RuntimeException exception) {
            return failedManifestReport(exception);
        }

    }

    /**
     * Parses and imports one manifest document.
     *
     * <p>This overload is primarily useful for tests and callers that obtain
     * manifest content from somewhere other than the file system.</p>
     *
     * @param source JSON manifest text
     * @return immutable report of successful imports and failures
     */
    public ImportReport importSource(
            String source
    ) {

        Objects.requireNonNull(source, "source");

        try {
            return importRawEntries(parseRawEntries(source));
        } catch (RuntimeException exception) {
            return failedManifestReport(exception);
        }

    }


    private List<Object> readRawEntries() {

        String source;

        try {
            source = Files.readString(manifestPath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read DiploBN catalog manifest: "
                            + manifestPath,
                    exception);
        }

        return parseRawEntries(source);

    }

    private ImportReport importRawEntries(
            List<Object> rawEntries
    ) {

        List<Success> successes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (int index = 0; index < rawEntries.size(); index++) {

            ManifestEntry entry;

            try {
                entry = parseEntry(
                        rawEntries.get(index),
                        "$.games[" + index + "]"
                );
            } catch (RuntimeException exception) {
                failures.add(
                        new Failure(
                                index,
                                null,
                                describe(exception),
                                exception
                        )
                );
                continue;
            }

            try {
                ImportedGame imported = gameImporter.importGame(entry);

                successes.add(
                        new Success(
                                index,
                                entry,
                                imported
                        )
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                failures.add(
                        new Failure(
                                index,
                                entry.url(),
                                "Import interrupted",
                                exception
                        )
                );
            } catch (Exception exception) {
                failures.add(
                        new Failure(
                                index,
                                entry.url(),
                                describe(exception),
                                exception
                        )
                );
            }

        }

        return new ImportReport(
                manifestPath,
                Instant.now(),
                successes,
                failures
        );

    }

    private ImportReport failedManifestReport(
            RuntimeException exception
    ) {

        return new ImportReport(
                manifestPath,
                Instant.now(),
                List.of(),
                List.of(
                        new Failure(
                                null,
                                null,
                                describe(exception),
                                exception
                        )
                )
        );

    }

    private static List<Object> parseRawEntries(
            String source
    ) {

        Object root = JsonReader.read(source);

        Map<String, Object> rootObject = object(
                root,
                "$");

        Object gamesValue = required(
                rootObject,
                "games",
                "$");

        return array(
                gamesValue,
                "$.games");

    }

    private static ManifestEntry parseEntry(
            Object rawEntry,
            String path
    ) {

        Map<String, Object> entry = object(
                rawEntry,
                path);

        String url = string(
                required(entry, "url", path),
                path + ".url");

        if (url.isBlank())
            throw new ParseException(
                    path + ".url",
                    "Game URL must not be blank");

        List<String> tags = optionalTags(
                entry,
                path);

        String notes = optionalString(
                entry,
                "notes",
                path);

        return new ManifestEntry(
                url,
                tags,
                notes
        );

    }

    private static List<String> optionalTags(
            Map<String, Object> entry,
            String path
    ) {

        if (!entry.containsKey("tags")
                || entry.get("tags") == null)
            return List.of();

        List<Object> rawTags = array(
                entry.get("tags"),
                path + ".tags");

        List<String> tags = new ArrayList<>();

        for (int index = 0; index < rawTags.size(); index++) {

            String tag = string(
                    rawTags.get(index),
                    path + ".tags[" + index + "]"
            ).strip();

            if (tag.isBlank())
                throw new ParseException(
                        path + ".tags[" + index + "]",
                        "Tag must not be blank");

            if (!tags.contains(tag))
                tags.add(tag);
        }

        return List.copyOf(tags);

    }

    private static Object required(
            Map<String, Object> object,
            String key,
            String path
    ) {

        if (!object.containsKey(key))
            throw new ParseException(
                    path,
                    "Missing required property: " + key);

        return object.get(key);

    }

    private static String optionalString(
            Map<String, Object> object,
            String key,
            String path
    ) {

        if (!object.containsKey(key)
                || object.get(key) == null)
            return null;

        return string(
                object.get(key),
                path + "." + key);

    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
            Object value,
            String path
    ) {

        if (!(value instanceof Map<?, ?>))
            throw new ParseException(
                    path,
                    "Expected object");

        return (Map<String, Object>) value;

    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(
            Object value,
            String path
    ) {

        if (!(value instanceof List<?>))
            throw new ParseException(
                    path,
                    "Expected array");

        return (List<Object>) value;

    }

    private static String string(
            Object value,
            String path
    ) {

        if (!(value instanceof String text))
            throw new ParseException(
                    path,
                    "Expected string");

        return text;

    }

    private static String describe(
            Exception exception
    ) {

        String message = exception.getMessage();

        return exception.getClass().getSimpleName()
                + (message == null
                ? ""
                : ": " + message);

    }


    /**
     * One parsed game request from the catalog manifest.
     */
    public record ManifestEntry(
            String url,
            List<String> tags,
            String notes
    ) {

        public ManifestEntry {

            Objects.requireNonNull(url, "url");

            if (url.isBlank())
                throw new IllegalArgumentException(
                        "url must not be blank");

            tags = List.copyOf(
                    Objects.requireNonNull(tags, "tags"));
        }

    }

    /**
     * Minimal provider-neutral result from processing one source game.
     */
    public record ImportedGame(
            String catalogId,
            long gameId,
            String canonicalUrl,
            DiploBNGame game
    ) {

        public ImportedGame {

            if (gameId <= 0)
                throw new IllegalArgumentException(
                        "gameId must be positive");

            Objects.requireNonNull(
                    canonicalUrl,
                    "canonicalUrl");

            Objects.requireNonNull(game, "game");
        }

    }

    /**
     * Successful processing of one manifest entry.
     */
    public record Success(
            int manifestIndex,
            ManifestEntry entry,
            ImportedGame importedGame
    ) {

        public Success {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(
                    importedGame,
                    "importedGame");
        }

    }

    /**
     * One manifest entry that could not be parsed or imported.
     */
    public record Failure(
            Integer manifestIndex,
            String url,
            String message,
            Exception cause
    ) {

        public Failure {
            Objects.requireNonNull(message, "message");
        }

    }

    /**
     * Result of one complete manifest import attempt.
     */
    public record ImportReport(
            Path manifestPath,
            Instant importedAt,
            List<Success> successes,
            List<Failure> failures
    ) {

        public ImportReport {
            Objects.requireNonNull(
                    manifestPath,
                    "manifestPath");

            Objects.requireNonNull(
                    importedAt,
                    "importedAt");

            successes = List.copyOf(
                    Objects.requireNonNull(
                            successes,
                            "successes"));

            failures = List.copyOf(
                    Objects.requireNonNull(
                            failures,
                            "failures"));
        }

        public int attemptedCount() {
            return successes.size() + failures.size();
        }

        public boolean completedWithoutFailures() {
            return failures.isEmpty();
        }

    }


    /**
     * Temporary default importer that proves manifest parsing and DiploBN
     * download behavior without requiring catalog persistence.
     */
    private static final class DownloadOnlyGameImporter
            implements DiploBNGameImporter {


        private final DiploBNGameClient gameClient;


        private DownloadOnlyGameImporter(
                DiploBNGameClient gameClient
        ) {
            this.gameClient = Objects.requireNonNull(
                    gameClient,
                    "gameClient");
        }


        @Override
        public ImportedGame importGame(
                ManifestEntry entry
        ) throws IOException, InterruptedException {

            DiploBNDownloadedGame downloaded =
                    gameClient.downloadGamePage(entry.url());

            return new ImportedGame(
                    null,
                    downloaded.gameId(),
                    downloaded.canonicalUrl(),
                    downloaded.game()
            );

        }

    }

}