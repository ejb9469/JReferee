package io.training;

import agents.encoding.EncodingPurpose;
import io.catalog.SourceFingerprints;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static io.training.ValueDatasetJson.*;

/**
 * Provider-neutral reader for completed TRAIN/VALIDATION value exports.
 * TEST is absent by design, not an empty evaluated split.
 */
public final class JsonlValueDataset implements ValueDataset {


    // Dataset identity \\

    public static final String SCHEMA = "jreferee-value-dataset-v1";

    private record Identity(String source, String gameKey) { }
    private record Indexed(String payloadHash, long positions) { }
    private record FileInfo(String hash, long games, long positions) { }

    private final Path directory;
    private final String fingerprint;
    private final Map<EncodingPurpose, Map<Identity, Indexed>> index;
    private final Map<EncodingPurpose, FileInfo> files;


    // Construction \\

    public JsonlValueDataset(Path directory) throws IOException {

        this.directory = directory.toAbsolutePath().normalize();
        String manifestText = Files.readString(this.directory.resolve("manifest.json"));
        Map<String, Object> manifest = parse(manifestText);

        if (!SCHEMA.equals(text(manifest, "schema"))
                || !Boolean.TRUE.equals(manifest.get("complete"))
                || !ValueRewardContract.VERSION.equals(text(manifest, "reward_contract"))
                || !Boolean.FALSE.equals(manifest.get("test_exported")))
            throw new IOException("Unsupported or incomplete value dataset");

        Object seedValue = manifest.get("split_seed");
        if (!(seedValue instanceof Long || seedValue instanceof Integer))
            throw new IOException("Invalid split seed");
        long seed = ((Number) seedValue).longValue();

        this.index = new EnumMap<>(EncodingPurpose.class);
        this.files = new EnumMap<>(EncodingPurpose.class);

        Map<String, Object> fileMetadata = object(manifest.get("files"));

        for (EncodingPurpose split : List.of(EncodingPurpose.TRAIN, EncodingPurpose.VALIDATION)) {
            Map<String, Object> info = object(fileMetadata.get(fileName(split)));
            this.files.put(split, new FileInfo(
                    text(info, "sha256"), count(info, "games"), count(info, "positions")));
            this.index.put(split, new LinkedHashMap<>());
        }

        String expectedIndexHash = text(manifest, "games_sha256");
        Set<Identity> allGames = new HashSet<>();

        try (Lines lines = new Lines(this.directory.resolve("games.jsonl"))) {

            String line;

            while ((line = lines.next()) != null) {

                Map<String, Object> row = parse(line);
                Identity id = new Identity(text(row, "source"), text(row, "game_key"));
                EncodingPurpose split = EncodingPurpose.valueOf(text(row, "split"));

                if (!allGames.add(id))
                    throw new IOException("Duplicate source/game identity in dataset index");

                if (ValueOutcomeAudit.split(seed, id.source(), id.gameKey()) != split)
                    throw new IOException("Dataset split differs from its recorded seed");

                String status = text(row, "status");

                if (status.equals("ACCEPTED")) {

                    requireSupported(split);
                    String payloadHash = text(row, "payload_sha256");
                    long positions = count(row, "positions");

                    if (!payloadHash.matches("[0-9a-f]{64}") || positions < 1)
                        throw new IOException("Invalid accepted-game index entry");

                    this.index.get(split).put(id, new Indexed(payloadHash, positions));

                } else if (status.equals("TEST_SKIPPED")) {

                    if (split != EncodingPurpose.TEST)
                        throw new IOException("Non-TEST game marked TEST_SKIPPED");

                } else if (status.equals("REJECTED")) {

                    if (split == EncodingPurpose.TEST || text(row, "reason").isBlank())
                        throw new IOException("Invalid rejection entry");

                } else {
                    throw new IOException("Unknown index status: " + status);
                }

            }

            if (!lines.hashAtEnd().equals(expectedIndexHash))
                throw new IOException("Game-index checksum mismatch");

        }

        for (EncodingPurpose split : this.files.keySet()) {

            FileInfo info = this.files.get(split);
            Map<Identity, Indexed> entries = this.index.get(split);
            long positions = entries.values().stream().mapToLong(Indexed::positions).sum();

            if (entries.size() != info.games() || positions != info.positions())
                throw new IOException("Manifest and accepted-game index counts disagree");

            this.index.put(split, Collections.unmodifiableMap(entries));

        }

        this.fingerprint = SourceFingerprints.sha256Hex(
                manifestText + "\n" + expectedIndexHash);

    }


    // Split access \\

    public static String fileName(EncodingPurpose split) {
        requireSupported(split);
        return split.name().toLowerCase(Locale.ROOT) + ".jsonl";
    }

    private static void requireSupported(EncodingPurpose split) {
        Objects.requireNonNull(split, "split");
        if (split == EncodingPurpose.TEST)
            throw new IllegalArgumentException("TEST was not exported in this development dataset");
    }

    @Override
    public long games(EncodingPurpose split) {
        requireSupported(split);
        return this.files.get(split).games();
    }

    @Override
    public long positions(EncodingPurpose split) {
        requireSupported(split);
        return this.files.get(split).positions();
    }

    @Override
    public String fingerprint() {
        return this.fingerprint;
    }

    @Override
    public Cursor open(EncodingPurpose split) throws IOException {
        requireSupported(split);
        return new Input(split);
    }


    // Verified cursor \\

    private final class Input implements Cursor {

        private final EncodingPurpose split;
        private final Lines lines;
        private final Set<Identity> seen = new HashSet<>();
        private long gameCount;
        private long positionCount;
        private long readNanos;
        private boolean finished;
        private Optional<String> sha256 = Optional.empty();

        private Input(EncodingPurpose split) throws IOException {
            this.split = split;
            this.lines = new Lines(directory.resolve(fileName(split)));
        }

        @Override
        public GameExample next() throws IOException {

            if (this.finished)
                return null;

            long started = System.nanoTime();

            try {

                String line = this.lines.next();

                if (line == null) {

                    FileInfo expected = files.get(this.split);
                    String actualHash = this.lines.hashAtEnd();

                    if (!actualHash.equals(expected.hash())
                            || this.gameCount != expected.games()
                            || this.positionCount != expected.positions()
                            || !this.seen.equals(index.get(this.split).keySet()))
                        throw new IOException("Split checksum, membership or counts disagree");

                    this.sha256 = Optional.of(actualHash);
                    this.finished = true;
                    return null;

                }

                GameExample game = decode(line, this.split);
                Identity id = new Identity(game.source(), game.gameKey());
                Indexed expected = index.get(this.split).get(id);

                if (expected == null || !this.seen.add(id)
                        || !expected.payloadHash().equals(game.payloadSha256())
                        || expected.positions() != game.positions().size())
                    throw new IOException("Game does not match its split index");

                this.gameCount++;
                this.positionCount += game.positions().size();
                return game;

            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid value-game record", exception);
            } finally {
                this.readNanos += System.nanoTime() - started;
            }

        }

        @Override
        public Statistics statistics() {
            return new Statistics(
                    this.gameCount, this.positionCount, this.readNanos, this.sha256);
        }

        @Override
        public void close() throws IOException {
            this.lines.close();
        }

    }

}