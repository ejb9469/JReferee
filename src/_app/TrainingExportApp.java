package _app;

import agents.encoding.MovementEncoding;
import io.training.DiploBNTrainingExporter;
import io.training.TrainingFilter;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Exports movement imitation examples from an existing, read-only catalog.
 */
public final class TrainingExportApp {

    private TrainingExportApp() { }

    public static void main(String[] args) throws Exception {
        Path database = Path.of("data", "diplobn-catalog.sqlite");
        Path output = Path.of("data", "training-movement-v4");
        TrainingFilter defaults = new TrainingFilter();
        long splitSeed = defaults.splitSeed();
        Set<String> communicationTypes = defaults.communicationTypes();
        boolean includeUnknown = defaults.includeUnknownCommunication();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) {
                System.out.println(usage());
                return;
            }
            if (!seen.add(args[index]) || index + 1 >= args.length)
                throw new IllegalArgumentException(usage());
            switch (args[index]) {
                case "--database" -> database = Path.of(args[++index]);
                case "--output" -> output = Path.of(args[++index]);
                case "--split-seed" -> splitSeed = Long.parseLong(args[++index]);
                case "--communication-types" -> {
                    String value = args[++index];
                    communicationTypes = new TreeSet<>();
                    if (!value.isEmpty())
                        for (String type : value.split(",", -1))
                            communicationTypes.add(type.strip());
                }
                case "--include-unknown" -> {
                    String value = args[++index];
                    if (!value.equals("true") && !value.equals("false"))
                        throw new IllegalArgumentException("--include-unknown must be true or false");
                    includeUnknown = Boolean.parseBoolean(value);
                }
                default -> throw new IllegalArgumentException(usage());
            }
        }
        TrainingFilter filter = new TrainingFilter(splitSeed, communicationTypes, includeUnknown);
        database = database.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(database))
            throw new IllegalArgumentException("Database not found: " + database);
        if (Files.exists(output))
            throw new IllegalArgumentException("Output directory already exists: " + output);
        Class.forName("org.sqlite.JDBC");

        DiploBNTrainingExporter exporter = new DiploBNTrainingExporter(filter);
        long games = 0, rejections = 0, unavailable = 0, warnings = 0;
        long[] counts = new long[DiploBNTrainingExporter.Split.values().length];
        Map<String, long[]> communication = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<String, Long> reasons = new TreeMap<>();
        Map<String, Long> selections = new TreeMap<>();
        for (var selection : DiploBNTrainingExporter.Selection.values())
            selections.put(selection.name(), 0L);
        String url = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT source_type, external_key, source_payload, source_payload_sha256
                     FROM catalog_game ORDER BY source_type, external_key
                     """)) {
            Files.createDirectory(output);
            try (BufferedWriter train = writer(output, "train.jsonl");
                 BufferedWriter validation = writer(output, "validation.jsonl");
                 BufferedWriter test = writer(output, "test.jsonl");
                 BufferedWriter rejected = writer(output, "rejected.jsonl");
                 BufferedWriter metadata = writer(output, "games.jsonl")) {
                while (rows.next()) {
                    String source = rows.getString("source_type"), key = rows.getString("external_key");
                    var split = exporter.split(source, key);
                    BufferedWriter target = switch (split) {
                        case TRAIN -> train;
                        case VALIDATION -> validation;
                        case TEST -> test;
                    };
                    var report = exporter.writeDetailed(source, key, rows.getString("source_payload"),
                            rows.getString("source_payload_sha256"), target, rejected);
                    var result = report.counts();
                    counts[split.ordinal()] += result.examples();
                    rejections += result.rejectionRecords();
                    metadata.write(report.gameJson() + "\n");
                    if (!report.metadataAvailable()) unavailable++;
                    if (report.metadataWarnings() > 0) warnings++;
                    selections.merge(report.selection().name(), 1L, Long::sum);
                    long[] mode = communication.computeIfAbsent(report.communicationType(), ignored -> new long[4]);
                    mode[0]++;
                    mode[1] += result.examples();
                    mode[2] += result.rejectionRecords();
                    if (report.selection() == DiploBNTrainingExporter.Selection.FILTERED_OUT) mode[3]++;
                    report.rejectionReasons().forEach((reason, count) -> reasons.merge(reason, count, Long::sum));
                    games++;
                }
            }
        }

        String manifest = """
                {
                  "schema": "%s",
                  "encoding": "%s",
                  "complete": true,
                  "split_seed": %d,
                  "split_percentages": [80, 10, 10],
                  "filter": %s,
                  "games_read": %d,
                  "game_selection": %s,
                  "train_examples": %d,
                  "validation_examples": %d,
                  "test_examples": %d,
                  "rejection_records": %d,
                  "validation": "CATALOG_LEGALITY_ONLY",
                  "communication_types": %s,
                  "rejection_reasons": %s,
                  "metadata_unavailable_games": %d,
                  "games_with_metadata_warnings": %d,
                  "default_order_status": "UNKNOWN",
                  "value_targets": false
                }
                """.formatted(DiploBNTrainingExporter.SCHEMA, MovementEncoding.SCHEMA,
                filter.splitSeed(), filter.toJson(), games, countsJson(selections),
                counts[0], counts[1], counts[2], rejections,
                communicationJson(communication), countsJson(reasons), unavailable, warnings);
        Files.writeString(output.resolve("manifest.json"), manifest,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Output: " + output);
        System.out.print(manifest);
        System.out.println("Inspect rejected.jsonl before training. No replay or outcome verification was performed.");
        System.out.println("Game selection is recorded in games.jsonl. Filtered games are not invalid-data rejections.");
        System.out.println("Source results are not value targets; default-order status remains unknown.");
    }

    private static String communicationJson(Map<String, long[]> modes) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (var entry : modes.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"communication_type\":");
            if (entry.getKey() == null) out.append("null");
            else appendString(out, entry.getKey());
            long[] counts = entry.getValue();
            out.append(",\"games\":").append(counts[0]).append(",\"accepted_examples\":").append(counts[1])
                    .append(",\"rejection_records\":").append(counts[2])
                    .append(",\"filtered_games\":").append(counts[3]).append('}');
        }
        return out.append(']').toString();
    }

    private static String countsJson(Map<String, Long> counts) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) out.append(',');
            first = false;
            appendString(out, entry.getKey());
            out.append(':').append(entry.getValue());
        }
        return out.append('}').toString();
    }

    private static BufferedWriter writer(Path directory, String name) throws java.io.IOException {
        return Files.newBufferedWriter(directory.resolve(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String usage() {
        return "Usage: TrainingExportApp [--database <catalog.sqlite>] "
                + "[--output <new-directory>] [--split-seed <long>] "
                + "[--communication-types <label,...>] [--include-unknown <true|false>]";
    }
}