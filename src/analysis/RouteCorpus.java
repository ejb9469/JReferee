package analysis;

import game.record.GameRecord;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNRecordImporter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;


public final class RouteCorpus {

    private final NavigableMap<Long, GameRecord> accepted;
    private final Map<CorpusPartition, List<GameRecord>> partitions;
    private final Map<String, Long> rejectionCounts;
    private final List<String> diagnostics;
    private final long scanned;


    private RouteCorpus(
            Map<Long, GameRecord> accepted,
            Map<String, Long> rejectionCounts,
            List<String> diagnostics,
            long scanned) {

        this.accepted = Collections.unmodifiableNavigableMap(new TreeMap<>(accepted));
        this.rejectionCounts = Collections.unmodifiableMap(new TreeMap<>(rejectionCounts));
        this.diagnostics = List.copyOf(diagnostics);
        this.scanned = scanned;

        Map<CorpusPartition, List<GameRecord>> grouped =
                new EnumMap<>(CorpusPartition.class);

        for (CorpusPartition partition : CorpusPartition.values())
            grouped.put(partition, new ArrayList<>());

        for (Map.Entry<Long, GameRecord> entry : this.accepted.entrySet())
            grouped.get(CorpusPartition.forSourceId(entry.getKey())).add(entry.getValue());

        Map<CorpusPartition, List<GameRecord>> immutable =
                new EnumMap<>(CorpusPartition.class);

        for (CorpusPartition partition : CorpusPartition.values())
            immutable.put(partition, List.copyOf(grouped.get(partition)));

        this.partitions = Collections.unmodifiableMap(immutable);

        long rejected = 0;

        for (long count : rejectionCounts.values())
            rejected = Math.addExact(rejected, count);

        if (Math.addExact(accepted.size(), rejected) != scanned)
            throw new IllegalStateException("Corpus accounting does not match scanned rows");

    }


    // Loading \\

    public static RouteCorpus load(Path databasePath)
            throws SQLException, ClassNotFoundException {

        Path path = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();

        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Database not found: " + path);

        Class.forName("org.sqlite.JDBC");

        String url = "jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url)) {
            return load(connection);
        }

    }

    /**
     * Uses SELECT only. Does not close or alter the caller's connection.
     */
    public static RouteCorpus load(Connection connection) throws SQLException {

        Objects.requireNonNull(connection, "connection");

        DiploBNParser parser = new DiploBNParser();
        DiploBNRecordImporter importer = new DiploBNRecordImporter();

        Map<Long, GameRecord> accepted = new TreeMap<>();
        Map<String, Long> rejectionCounts = new TreeMap<>();
        List<String> diagnostics = new ArrayList<>();
        Set<Long> sourceIds = new HashSet<>();

        long scanned = 0;

        String sql = """
                SELECT catalog_id, source_type, external_key, source_payload
                FROM catalog_game
                ORDER BY source_type, external_key, catalog_id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {

                scanned++;

                String source = rows.getString("source_type");
                String externalKey = rows.getString("external_key");
                String catalogId = rows.getString("catalog_id");

                String reference = source + "/" + externalKey + " [" + catalogId + "]";

                if (!"DIPLOBN".equals(source)) {

                    reject(rejectionCounts, diagnostics, "UNSUPPORTED_SOURCE",
                            reference, "Only DIPLOBN is supported");

                    continue;

                }

                long sourceId;

                try {
                    sourceId = sourceId(externalKey);
                } catch (IllegalArgumentException exception) {

                    reject(rejectionCounts, diagnostics, "INVALID_SOURCE_ID",
                            reference, exception.getMessage());

                    continue;

                }

                // Multiple versions must not silently become separate observations.
                if (!sourceIds.add(sourceId))
                    throw new IllegalStateException(
                            "Duplicate DiploBN source identity in catalog: " + sourceId
                                    + ". Resolve duplicate versions before building the corpus.");

                CorpusPartition partition = CorpusPartition.forSourceId(sourceId);
                String partitionedReference = reference + " {" + partition + "}";

                String payload = rows.getString("source_payload");

                if (payload == null || payload.isBlank()) {

                    reject(rejectionCounts, diagnostics, "EMPTY_PAYLOAD",
                            partitionedReference, "Source payload is absent or blank");

                    continue;

                }

                DiploBNGame parsed;

                try {
                    parsed = parser.parse(payload);
                } catch (IllegalArgumentException exception) {

                    reject(rejectionCounts, diagnostics, "PARSE_REJECTED",
                            partitionedReference, exception.getMessage());

                    continue;

                }

                GameRecord record;
                List<String> importNotes;

                try {

                    DiploBNRecordImporter.ImportResult imported =
                            importer.importWithReport(sourceId, parsed);

                    record = imported.game();
                    importNotes = imported.notes();

                    UnitRoutes validator = new UnitRoutes();
                    validator.add(record);

                } catch (IllegalArgumentException exception) {

                    reject(rejectionCounts, diagnostics, "REPLAY_REJECTED",
                            partitionedReference, exception.getMessage());

                    continue;

                }

                if (!record.id().equals(DiploBNRecordImporter.archiveId(sourceId)))
                    throw new IllegalStateException(
                            "Imported archive ID does not match source identity: " + sourceId);

                accepted.put(sourceId, record);

                for (String note : importNotes)
                    diagnostics.add("IMPORT_NOTE " + partitionedReference + ": " + note);

            }

        }

        return new RouteCorpus(accepted, rejectionCounts, diagnostics, scanned);

    }

    private static long sourceId(String externalKey) {

        if (externalKey == null || !externalKey.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException(
                    "Expected a canonical positive decimal GameID");

        try {
            return Long.parseLong(externalKey);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("GameID exceeds the supported integer range", exception);
        }

    }

    private static void reject(
            Map<String, Long> counts,
            List<String> diagnostics,
            String category,
            String reference,
            String reason) {

        counts.merge(category, 1L, Math::addExact);

        diagnostics.add(category + " " + reference + ": "
                + (reason == null ? "(no message)" : reason));

    }


    // Immutable results \\

    public long scanned() {
        return scanned;
    }

    public long acceptedCount() {
        return accepted.size();
    }

    public long rejectedCount() {
        return scanned - accepted.size();
    }

    public NavigableMap<Long, GameRecord> accepted() {
        return accepted;
    }

    public List<GameRecord> games(CorpusPartition partition) {
        return partitions.get(Objects.requireNonNull(partition, "partition"));
    }

    public List<Long> sourceIds(CorpusPartition partition) {

        Objects.requireNonNull(partition, "partition");

        return accepted.keySet().stream()
                .filter(id -> CorpusPartition.forSourceId(id) == partition)
                .toList();

    }

    public Map<String, Long> rejectionCounts() {
        return rejectionCounts;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }


    public void printReport(PrintStream output) {

        Objects.requireNonNull(output, "output");

        output.println("ROUTE CORPUS IMPORT");
        output.println();
        output.println("Split version: " + CorpusPartition.SPLIT_VERSION);
        output.println("Replay ruleset: " + DiploBNRecordImporter.RULESET);
        output.println();

        output.printf("Catalog rows scanned: %d%n", scanned());
        output.printf("Accepted records:     %d%n", acceptedCount());
        output.printf("Rejected rows:        %d%n", rejectedCount());

        output.println();

        for (CorpusPartition partition : CorpusPartition.values())
            output.printf("%-12s %d game(s)%n", partition, games(partition).size());

        long phases = 0;

        for (GameRecord record : accepted.values())
            phases = Math.addExact(phases, record.resolvedPhases().size());

        output.printf("%nVerified replayed phases: %d%n", phases);
        output.println("Each final source snapshot was used as a verification boundary only.");

        if (!rejectionCounts.isEmpty()) {

            output.println();
            output.println("Rejections:");

            for (Map.Entry<String, Long> entry : rejectionCounts.entrySet())
                output.printf("  %-22s %d%n", entry.getKey(), entry.getValue());

        }

    }

}