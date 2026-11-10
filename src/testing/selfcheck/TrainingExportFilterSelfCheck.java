package testing.selfcheck;

import _app.TrainingExportApp;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.training.DiploBNTrainingExporter;
import io.training.TrainingFilter;
import parsing.diplobn.JsonReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static io.json.JsonEscaper.appendString;
import static io.training.DiploBNTrainingExporter.Selection.*;

/**
 * Configurable game selection, stable splits, and export accounting.
 * The application checks require the existing SQLite JDBC driver.
 */
public final class TrainingExportFilterSelfCheck {

    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));
    private static final String PHASE = """
            {"Phase":19011,"Status":"Completed","SupplyCenters":{"England":["Lon"]},
             "Units":{"England":[["A","Lon"]]},"Orders":{"England":[["Lon","h","s"]]}}
            """;
    private static final List<String> OUTPUT_FILES = List.of(
            "train.jsonl", "validation.jsonl", "test.jsonl", "rejected.jsonl", "games.jsonl", "manifest.json");
    private static int checks;

    private TrainingExportFilterSelfCheck() { }

    public static void main(String[] args) throws Exception {
        filters();
        export();
        application();
        System.out.println("TrainingExportFilterSelfCheck passed: " + checks + " checks");
    }

    private static void filters() {
        var defaults = new TrainingFilter();
        require(defaults.splitSeed() == 7 && defaults.communicationTypes() == null
                && defaults.includeUnknownCommunication(), "Include-all defaults");
        for (String label : Arrays.asList("None", "Full", "UnmappedMode", null))
            require(defaults.includes(label), "Default label inclusion");
        Set<String> selected = new HashSet<>(List.of("None", "Full"));
        var filter = new TrainingFilter(-7, selected, true);
        selected.clear();
        require(filter.communicationTypes().equals(Set.of("None", "Full")), "Defensive copy");
        require(filter.includes(null) && !filter.includes("full") && !filter.includes("Future"), "Exact labels");
        require(JSON.array(object(filter.toJson()).get("communication_types"), "$.types")
                .equals(List.of("Full", "None")), "Stable configuration ordering");
        expect(UnsupportedOperationException.class, () -> filter.communicationTypes().clear());
        expect(IllegalArgumentException.class, () -> new TrainingFilter(7, Set.of(" "), true));
        expect(NullPointerException.class, () -> new TrainingFilter(7,
                new HashSet<>(Arrays.asList("None", null)), true));
        expect(NullPointerException.class, () -> new DiploBNTrainingExporter(null));
        var known = new TrainingFilter(7, null, false);
        require(known.includes("UnmappedMode") && !known.includes(null), "Unfamiliar is not missing");
        var unknown = new TrainingFilter(7, Set.of(), true);
        require(unknown.includes(null) && !unknown.includes("None"), "Unknown-only selection");
        var empty = new TrainingFilter(7, Set.of(), false);
        require(!empty.includes(null) && !empty.includes("Full"), "Empty selection");
        String escaped = "Future\"Mode\n";
        require(JSON.array(object(new TrainingFilter(7, Set.of(escaped), true).toJson())
                .get("communication_types"), "$.types").equals(List.of(escaped)), "Configuration JSON escaping");

        for (long seed : new long[]{7, 0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var all = new DiploBNTrainingExporter(seed);
            var subset = new DiploBNTrainingExporter(new TrainingFilter(seed, Set.of("None"), false));
            for (int key = 0; key < 100; key++)
                require(all.split("DIPLOBN", Integer.toString(key))
                        == subset.split("DIPLOBN", Integer.toString(key)), "Selection does not change splits");
        }
        var first = new DiploBNTrainingExporter(7);
        var second = new DiploBNTrainingExporter(8);
        boolean changed = false;
        for (int key = 0; key < 100; key++)
            changed |= first.split("DIPLOBN", Integer.toString(key)) != second.split("DIPLOBN", Integer.toString(key));
        require(changed, "Seed controls split assignment");
    }

    private static void export() throws Exception {
        var all = new DiploBNTrainingExporter(new TrainingFilter());
        var legacy = new DiploBNTrainingExporter(7);
        var none = new DiploBNTrainingExporter(new TrainingFilter(7, Set.of("None"), false));
        for (String label : Arrays.asList("None", "Full", "UnmappedMode", null)) {
            String payload = game(label);
            StringBuilder baseline = new StringBuilder(), compatibility = new StringBuilder(), failures = new StringBuilder();
            var report = write(all, payload, baseline, failures);
            require(report.selection() == INCLUDED && report.counts().examples() == 2
                    && failures.isEmpty(), "Default includes every communication group");
            require(write(legacy, payload, compatibility, new StringBuilder()).equals(report)
                    && baseline.toString().equals(compatibility.toString()), "Seed constructor compatibility");
            StringBuilder selected = new StringBuilder();
            report = write(none, payload, selected, failures);
            boolean included = "None".equals(label);
            require(report.selection() == (included ? INCLUDED : FILTERED_OUT), "API selection");
            require(report.counts().examples() == (included ? 2 : 0)
                    && report.counts().rejectionRecords() == 0 && failures.isEmpty(), "Filtered is not rejected");
            require(selected.toString().equals(included ? baseline.toString() : ""), "Retained rows unchanged");
            require(object(report.gameJson()).get("selection").equals(report.selection().name())
                    && report.metadataAvailable(), "Game selection recorded");
        }

        String malformed = game(null).replace("{\"GamePhases\":", "{\"CommunicationType\":[],\"GamePhases\":");
        var report = write(none, malformed, new StringBuilder(), new StringBuilder());
        require(report.selection() == FILTERED_OUT && report.metadataWarnings() == 1, "Unknown metadata excluded with warning");
        report = write(all, malformed, new StringBuilder(), new StringBuilder());
        require(report.selection() == INCLUDED && report.metadataWarnings() == 1
                && report.counts().examples() == 2, "Unknown metadata retained by default");
        String invalid = "{\"CommunicationType\":\"Full\",\"GamePhases\":[]}";
        report = write(none, invalid, new StringBuilder(), new StringBuilder());
        require(report.selection() == FILTERED_OUT && report.rejectionReasons().isEmpty(),
                "Excluded games are not parsed into phases");
        report = write(all, invalid, new StringBuilder(), new StringBuilder());
        require(report.selection() == INCLUDED && report.rejectionReasons().equals(Map.of("INVALID_GAME", 1L)),
                "Included does not imply valid");
        for (String broken : List.of("{broken", "[]")) {
            report = write(none, broken, new StringBuilder(), new StringBuilder());
            require(report.selection() == NOT_EVALUATED
                    && report.rejectionReasons().equals(Map.of("INVALID_GAME", 1L)), "Unreadable JSON before selection");
        }
        report = none.writeDetailed("DIPLOBN", "42", game("Full"), "wrong",
                new StringBuilder(), new StringBuilder());
        require(report.selection() == NOT_EVALUATED && !report.metadataAvailable()
                && report.rejectionReasons().equals(Map.of("PAYLOAD_HASH_MISMATCH", 1L)), "Hash checked before filtering");
        report = none.writeDetailed("LOCAL_FILE", "42", game("Full"), "wrong",
                new StringBuilder(), new StringBuilder());
        require(report.selection() == NOT_EVALUATED
                && report.rejectionReasons().equals(Map.of("UNSUPPORTED_SOURCE", 1L)), "Source checked before filtering");
    }

    private static void application() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path root = Files.createTempDirectory("jreferee-training-filter-check-");
        Path database = root.resolve("catalog.sqlite");
        List<String> names = List.of("all", "none", "known", "unknown", "empty", "mixed");
        try {
            List<String> payloads = List.of(game("None"), game("Full"), game("UnmappedMode"), game(null),
                    game(null).replace("{\"GamePhases\":", "{\"CommunicationType\":[],\"GamePhases\":"),
                    game(null).replace("{\"GamePhases\":", "{\"CommunicationType\":null,\"GamePhases\":"),
                    "{\"CommunicationType\":\"Full\",\"GamePhases\":[]}", "{broken", game("Full"));
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE catalog_game (source_type TEXT, external_key TEXT,
                            source_payload TEXT, source_payload_sha256 TEXT)
                        """);
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO catalog_game VALUES(?,?,?,?)")) {
                    for (int index = 0; index < payloads.size(); index++) {
                        String payload = payloads.get(index);
                        insert.setString(1, "DIPLOBN");
                        insert.setString(2, Integer.toString(index));
                        insert.setString(3, payload);
                        insert.setString(4, index == 8 ? "wrong" : SourceFingerprints.sha256Hex(payload));
                        insert.executeUpdate();
                    }
                }
            }
            byte[] before = Files.readAllBytes(database);
            checkApplication(database, root.resolve("all"), new TrainingFilter(),
                    7, 0, 12, 3);
            checkApplication(database, root.resolve("none"), new TrainingFilter(7, Set.of("None"), false),
                    1, 6, 2, 2, "--communication-types", "None", "--include-unknown", "false");
            checkApplication(database, root.resolve("known"), new TrainingFilter(7, null, false),
                    4, 3, 6, 3, "--include-unknown", "false");
            checkApplication(database, root.resolve("unknown"), new TrainingFilter(7, Set.of(), true),
                    3, 4, 6, 2, "--communication-types", "");
            checkApplication(database, root.resolve("empty"), new TrainingFilter(7, Set.of(), false),
                    0, 7, 0, 2, "--communication-types", "", "--include-unknown", "false");
            checkApplication(database, root.resolve("mixed"), new TrainingFilter(99, Set.of("Full", "None"), true),
                    6, 1, 10, 3, "--communication-types", " Full, None ", "--include-unknown", "true", "--split-seed", "99");
            require(Arrays.equals(before, Files.readAllBytes(database)), "Database unchanged");
            expect(IllegalArgumentException.class, () -> run("--database", database.toString(),
                    "--output", root.resolve("all").toString()));
            for (String[] bad : List.of(
                    new String[]{"--include-unknown", "yes"},
                    new String[]{"--include-unknown", "TRUE"},
                    new String[]{"--include-unknown"},
                    new String[]{"--communication-types", "Full,"},
                    new String[]{"--communication-types", "Full,,None"},
                    new String[]{"--communication-types", " "},
                    new String[]{"--communication-types", "Full", "--communication-types", "None"}))
                expect(IllegalArgumentException.class, () -> run(bad));
        } finally {
            for (String name : names) {
                Path output = root.resolve(name);
                for (String file : OUTPUT_FILES) Files.deleteIfExists(output.resolve(file));
                Files.deleteIfExists(output);
            }
            Files.deleteIfExists(database);
            Files.delete(root);
        }
    }

    private static void checkApplication(Path database, Path output, TrainingFilter filter,
                                         int included, int filtered, int examples, int rejections,
                                         String... options) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("--database", database.toString(), "--output", output.toString()));
        arguments.addAll(List.of(options));
        run(arguments.toArray(String[]::new));
        var manifest = object(Files.readString(output.resolve("manifest.json")));
        require(Boolean.TRUE.equals(manifest.get("complete"))
                && manifest.get("schema").equals(DiploBNTrainingExporter.SCHEMA), "Versioned completed export");
        require(manifest.get("filter").equals(object(filter.toJson()))
                && ((Number) manifest.get("split_seed")).longValue() == filter.splitSeed(), "Configuration recorded");
        var selections = JSON.object(manifest.get("game_selection"), "$.selection");
        require(number(selections, "INCLUDED") == included && number(selections, "FILTERED_OUT") == filtered
                && number(selections, "NOT_EVALUATED") == 2, "Selection accounting");
        require(number(manifest, "games_read") == included + filtered + 2, "Every game accounted for");
        require(number(manifest, "rejection_records") == rejections
                && number(manifest, "metadata_unavailable_games") == 2
                && number(manifest, "games_with_metadata_warnings") == 1, "Validation and metadata accounting");
        var games = Files.readAllLines(output.resolve("games.jsonl")).stream()
                .map(TrainingExportFilterSelfCheck::object).toList();
        require(games.size() == 9, "Metadata retained for all games");
        require(games.stream().filter(g -> g.get("selection").equals("FILTERED_OUT")).count() == filtered,
                "Filtered game records");
        for (var game : games)
            if (game.get("selection").equals("FILTERED_OUT"))
                require(number(game, "accepted_examples") == 0 && number(game, "rejection_records") == 0,
                        "No validation counts for excluded phases");
        long groupedGames = 0, groupedFiltered = 0, groupedExamples = 0, groupedRejections = 0;
        for (Object value : JSON.array(manifest.get("communication_types"), "$.communication_types")) {
            var mode = JSON.object(value, "$.mode");
            groupedGames += number(mode, "games");
            groupedFiltered += number(mode, "filtered_games");
            groupedExamples += number(mode, "accepted_examples");
            groupedRejections += number(mode, "rejection_records");
        }
        require(groupedGames == 9 && groupedFiltered == filtered && groupedExamples == examples
                && groupedRejections == rejections, "Per-label totals");
        int actualExamples = 0;
        for (var split : DiploBNTrainingExporter.Split.values()) {
            String name = split.name().toLowerCase(Locale.ROOT);
            var rows = Files.readAllLines(output.resolve(name + ".jsonl"));
            require(number(manifest, name + "_examples") == rows.size(), "Split totals");
            actualExamples += rows.size();
            for (String line : rows) {
                var row = object(line);
                require(filter.includes((String)row.get("communication_type")), "Only selected labels exported");
                require(row.get("split").equals(split.name()) && new DiploBNTrainingExporter(filter)
                        .split("DIPLOBN", (String)row.get("game_key")) == split, "Configured game split");
                var input = JSON.object(row.get("input"), "$.input");
                require(!input.containsKey("communication_type") && !input.containsKey("filter"),
                        "Selection metadata outside model input");
            }
        }
        require(actualExamples == examples, "Accepted example count");
        var failures = Files.readAllLines(output.resolve("rejected.jsonl"));
        require(failures.size() == rejections, "Rejection file count");
        Set<String> rejectedKeys = new HashSet<>();
        for (String line : failures) rejectedKeys.add((String)object(line).get("game_key"));
        require(rejectedKeys.equals(rejections == 3 ? Set.of("6", "7", "8") : Set.of("7", "8")),
                "Filtered games absent from rejection file");
    }

    private static String game(String label) {
        StringBuilder out = new StringBuilder("{");
        if (label != null) {
            out.append("\"CommunicationType\":");
            appendString(out, label);
            out.append(',');
        }
        return out.append("\"GamePhases\":[").append(PHASE).append(',')
                .append(PHASE.replace("19011", "19012")).append("]}").toString();
    }

    private static DiploBNTrainingExporter.Report write(DiploBNTrainingExporter exporter, String payload,
                                                        StringBuilder examples, StringBuilder rejected) throws IOException {
        return exporter.writeDetailed("DIPLOBN", "42", payload, SourceFingerprints.sha256Hex(payload), examples, rejected);
    }

    private static Map<String, Object> object(String text) {
        return JSON.object(JsonReader.read(text), "$");
    }

    private static long number(Map<String, Object> object, String field) {
        return ((Number)object.get(field)).longValue();
    }

    private static void run(String... args) throws Exception {
        PrintStream original = System.out;
        try (PrintStream captured = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            TrainingExportApp.main(args);
        } finally {
            System.setOut(original);
        }
    }

    private interface Checked { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, Checked action) {
        try {
            action.run();
        } catch (Exception exception) {
            require(type.isInstance(exception), "Unexpected exception: " + exception);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}