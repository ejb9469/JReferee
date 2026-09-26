package io.catalog.diplobn;

import io.catalog.CatalogImporter;
import io.catalog.CatalogManifestReader;
import io.json.JsonAccess;
import parsing.diplobn.DiploBNDownloadedGame;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.JsonReader;
import parsing.diplobn.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;


/**
 * Reads a DiploBN catalog manifest and imports each listed game independently.
 *
 * <p>Persistence is delegated to the injected DiploBNGameImporter.</p>
 */
public final class DiploBNGameCatalogFileImporter
        implements CatalogImporter<DiploBNGameCatalogFileImporter.ImportReport>,
                     CatalogManifestReader<DiploBNGameCatalogFileImporter.ManifestEntry> {


    // Constants \\

    public static final Path DEFAULT_MANIFEST_PATH =
            Path.of("src", "resources", "save_games", "diplobn-games.json");

    private static final JsonAccess JSON = new JsonAccess(ParseException::new);


    // Core state \\

    private final Path manifestPath;
    private final DiploBNGameImporter gameImporter;


    // Constructors \\

    public DiploBNGameCatalogFileImporter() {
        this(DEFAULT_MANIFEST_PATH, new DownloadOnlyGameImporter(new DiploBNGameClient()));
    }

    public DiploBNGameCatalogFileImporter(Path manifestPath) {
        this(manifestPath, new DownloadOnlyGameImporter(new DiploBNGameClient()));
    }

    public DiploBNGameCatalogFileImporter(
            Path manifestPath,
            DiploBNGameImporter gameImporter
    ) {

        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        this.gameImporter = Objects.requireNonNull(gameImporter, "gameImporter");

    }


    // Public operations \\

    /**
     * Strict reading: any invalid entry causes this operation to fail.
     */
    @Override
    public List<ManifestEntry> read() {

        List<Object> rawEntries = readRawEntries();
        List<ManifestEntry> entries = new ArrayList<>();

        for (int index = 0; index < rawEntries.size(); index++)
            entries.add(parseEntry(rawEntries.get(index), "$.games[" + index + "]"));

        return List.copyOf(entries);

    }

    @Override
    public Path path() {
        return manifestPath;
    }

    @Override
    public ImportReport importGames() {

        try {
            return importRawEntries(readRawEntries());
        } catch (RuntimeException exception) {
            return failedManifestReport(exception);
        }

    }

    public ImportReport importSource(String source) {

        Objects.requireNonNull(source, "source");

        try {
            return importRawEntries(parseRawEntries(source));
        } catch (RuntimeException exception) {
            return failedManifestReport(exception);
        }

    }


    // Manifest processing \\

    private List<Object> readRawEntries() {

        String source;

        try {
            source = Files.readString(manifestPath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read DiploBN catalog manifest: " + manifestPath, exception);
        }

        return parseRawEntries(source);

    }

    private ImportReport importRawEntries(List<Object> rawEntries) {

        List<Success> successes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (int index = 0; index < rawEntries.size(); index++) {

            ManifestEntry entry;

            try {
                entry = parseEntry(rawEntries.get(index), "$.games[" + index + "]");
            } catch (RuntimeException exception) {
                failures.add(new Failure(index, null, describe(exception), exception));
                continue;
            }

            try {
                ImportedGame imported = gameImporter.importGame(entry);
                successes.add(new Success(index, entry, imported));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failures.add(new Failure(index, entry.url(), "Import interrupted", exception));
            } catch (Exception exception) {
                failures.add(new Failure(index, entry.url(), describe(exception), exception));
            }

        }

        return new ImportReport(manifestPath, Instant.now(), successes, failures);

    }

    private ImportReport failedManifestReport(RuntimeException exception) {

        return new ImportReport(
                manifestPath,
                Instant.now(),
                List.of(),
                List.of(new Failure(null, null, describe(exception), exception)));

    }

    private static List<Object> parseRawEntries(String source) {

        Map<String, Object> root = JSON.object(JsonReader.read(source), "$");

        return JSON.array(JSON.required(root, "games", "$"), "$.games");

    }

    private static ManifestEntry parseEntry(Object rawEntry, String path) {

        Map<String, Object> entry = JSON.object(rawEntry, path);
        String url = JSON.string(JSON.required(entry, "url", path), path + ".url");

        if (url.isBlank())
            throw new ParseException(path + ".url", "Game URL must not be blank");

        List<String> tags = optionalTags(entry, path);
        String notes = JSON.optionalString(entry, "notes", path);

        return new ManifestEntry(url, tags, notes);

    }

    private static List<String> optionalTags(Map<String, Object> entry, String path) {

        if (!entry.containsKey("tags") || entry.get("tags") == null)
            return List.of();

        List<Object> rawTags = JSON.array(entry.get("tags"), path + ".tags");
        List<String> tags = new ArrayList<>();

        for (int index = 0; index < rawTags.size(); index++) {

            String tagPath = path + ".tags[" + index + "]";
            String tag = JSON.string(rawTags.get(index), tagPath).strip();

            if (tag.isBlank())
                throw new ParseException(tagPath, "Tag must not be blank");

            if (!tags.contains(tag))
                tags.add(tag);

        }

        return List.copyOf(tags);

    }

    private static String describe(Exception exception) {

        String message = exception.getMessage();

        return exception.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);

    }


    // Result types \\

    public record ManifestEntry(String url, List<String> tags, String notes) {

        public ManifestEntry {

            Objects.requireNonNull(url, "url");

            if (url.isBlank())
                throw new IllegalArgumentException("url must not be blank");

            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));

        }

    }

    public record ImportedGame(
            String catalogId,
            long gameId,
            String canonicalUrl,
            DiploBNGame game
    ) {

        public ImportedGame {

            if (gameId <= 0)
                throw new IllegalArgumentException("gameId must be positive");

            Objects.requireNonNull(canonicalUrl, "canonicalUrl");
            Objects.requireNonNull(game, "game");

        }

    }

    public record Success(
            int manifestIndex,
            ManifestEntry entry,
            ImportedGame importedGame
    ) {

        public Success {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(importedGame, "importedGame");
        }

    }

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

    public record ImportReport(
            Path manifestPath,
            Instant importedAt,
            List<Success> successes,
            List<Failure> failures
    ) {

        public ImportReport {

            Objects.requireNonNull(manifestPath, "manifestPath");
            Objects.requireNonNull(importedAt, "importedAt");

            successes = List.copyOf(Objects.requireNonNull(successes, "successes"));
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));

        }

        public int attemptedCount() {
            return successes.size() + failures.size();
        }

        public boolean completedWithoutFailures() {
            return failures.isEmpty();
        }

    }


    private static final class DownloadOnlyGameImporter implements DiploBNGameImporter {

        private final DiploBNGameClient gameClient;

        private DownloadOnlyGameImporter(DiploBNGameClient gameClient) {
            this.gameClient = Objects.requireNonNull(gameClient, "gameClient");
        }

        @Override
        public ImportedGame importGame(ManifestEntry entry)
                throws IOException, InterruptedException {

            DiploBNDownloadedGame downloaded = gameClient.downloadGamePage(entry.url());

            return new ImportedGame(
                    null,
                    downloaded.gameId(),
                    downloaded.canonicalUrl(),
                    downloaded.game());

        }

    }


}