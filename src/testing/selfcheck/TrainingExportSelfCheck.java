package testing.selfcheck;

import _app.TrainingExportApp;
import agents.encoding.EncodingPurpose;
import agents.encoding.MovementEncoding;
import contracts.OrderForm;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.training.DiploBNTrainingExporter;
import io.json.JsonReader;
import phase.Order;
import phase.UnitId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Encoding round trips, source exclusions, and read-only dataset export.
 * The application checks require the existing SQLite JDBC driver.
 */
public final class TrainingExportSelfCheck {

    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));
    private static final String PHASE = """
            {"Phase":19011,"Status":"AwaitingOrders",
             "SupplyCenters":{"England":["Lon"],"France":["Bre"]},
             "Units":{"England":[["A","Lon"],["F","ENG"]],"France":[["F","MAO"]]},
             "Orders":{"England":[["Lon",["m","Bel"],"s"],["ENG",["c","Lon","Bel"],"s"]],
                       "France":[["MAO",["m",["Spa","nc"]],"f"]]}}
            """;
    private static int checks;

    private TrainingExportSelfCheck() { }

    public static void main(String[] args) throws Exception {
        roundTrips();
        identities();
        sourceExport();
        metadataAndDiagnostics();
        application();
        System.out.println("TrainingExportSelfCheck passed: " + checks + " checks");
    }

    private static void roundTrips() {
        GameState_Movement state = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        for (Nation nation : Nation.values()) {
            MovementEncoding encoding = new MovementEncoding(state, nation);
            List<OrderForm> holds = encoding.catalog().unitLocations().keySet().stream()
                    .<OrderForm>map(Order::hold).toList();
            List<Integer> baseline = encoding.encode(holds);
            require(new HashSet<>(encoding.decode(baseline)).equals(new HashSet<>(holds)), "Hold round trip");
            require(object(encoding.toJson()).get("schema").equals(MovementEncoding.SCHEMA), "Encoding schema");
            for (int slot = 0; slot < encoding.options().size(); slot++) {
                var options = encoding.options().get(slot);
                var labels = options.stream().map(encoding::token).toList();
                require(labels.equals(labels.stream().sorted().toList()), "Option sorting");
                require(new HashSet<>(labels).size() == labels.size(), "Unique option labels");
                for (int index = 0; index < options.size(); index++) {
                    List<Integer> action = new ArrayList<>(baseline);
                    action.set(slot, index);
                    require(encoding.encode(encoding.decode(action)).equals(action), "Option round trip");
                }
            }
            expect(IllegalArgumentException.class, () -> encoding.encode(holds.subList(1, holds.size())));
            List<OrderForm> duplicate = new ArrayList<>(holds);
            duplicate.add(holds.getFirst());
            expect(IllegalArgumentException.class, () -> encoding.encode(duplicate));
            expect(IllegalArgumentException.class, () -> encoding.decode(List.of()));
            List<Integer> invalid = new ArrayList<>(baseline);
            invalid.set(0, -1);
            expect(IllegalArgumentException.class, () -> encoding.decode(invalid));
            invalid.set(0, encoding.options().getFirst().size());
            expect(IllegalArgumentException.class, () -> encoding.decode(invalid));
            expect(UnsupportedOperationException.class, () -> encoding.options().clear());
            expect(UnsupportedOperationException.class, () -> encoding.options().getFirst().clear());
            expect(UnsupportedOperationException.class, () -> encoding.encode(holds).clear());
            expect(UnsupportedOperationException.class, () -> encoding.decode(baseline).clear());
            require(state.equals(StandardGameFactory.create1901().snapshot()), "Position unchanged");
        }
    }

    private static void identities() {
        UnitId army = UnitId.newlyBuilt(1901, Nation.ENGLAND, UnitType.ARMY, Province.Lvp);
        UnitId fleet = UnitId.newlyBuilt(1901, Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        UnitId french = UnitId.newlyBuilt(1901, Nation.FRANCE, UnitType.FLEET, Province.Bre);
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(army, Province.Lon);
        locations.put(fleet, Province.ENG);
        locations.put(french, Province.MAO);
        GameMoment moment = new GameMoment(1902, GamePhase.SPRING_MOVEMENT);
        MovementEncoding first = new MovementEncoding(new GameState_Movement(moment,
                new BoardState(locations, Map.of(Province.Lon, Nation.ENGLAND))), Nation.ENGLAND);
        var orders = List.of(Order.move(army, Province.Bel), Order.convoy(fleet, Province.Lon, Province.Bel));
        var action = first.encode(orders);
        require(first.decode(action).containsAll(orders), "Convoy and move retained");
        require(first.token(orders.getFirst()).equals("ARMY Lon MOVE Bel -"), "Use current location");
        require(!first.toJson().contains(army.value().toString()), "No persistent identity in input");
        List<Map.Entry<UnitId, Province>> entries = new ArrayList<>(locations.entrySet());
        Collections.reverse(entries);
        Map<UnitId, Province> renamed = new LinkedHashMap<>();
        for (var entry : entries)
            renamed.put(UnitId.newlyBuilt(1903, entry.getKey().owner(),
                    entry.getKey().unitType(), entry.getValue()), entry.getValue());
        MovementEncoding second = new MovementEncoding(new GameState_Movement(moment,
                new BoardState(renamed, Map.of(Province.Lon, Nation.ENGLAND))), Nation.ENGLAND);
        require(first.toJson().equals(second.toJson()), "Identity and insertion-order independence");
        require(second.encode(second.decode(action)).equals(action), "Decode binds new unit identities");
        MovementEncoding france = new MovementEncoding(new GameState_Movement(moment,
                new BoardState(locations, Map.of())), Nation.FRANCE);
        require(france.options().getFirst().contains(Order.move(french, Province.SpaNC)), "North coast");
        require(france.options().getFirst().contains(Order.move(french, Province.SpaSC)), "South coast");
        require(!france.token(Order.move(french, Province.SpaNC))
                .equals(france.token(Order.move(french, Province.SpaSC))), "Coast labels differ");
        expect(IllegalArgumentException.class, () -> first.encode(List.of(Order.hold(french))));
        expect(IllegalArgumentException.class, () -> first.encode(List.of(
                Order.move(army, Province.MAO), Order.hold(fleet))));
        MovementEncoding empty = new MovementEncoding(new GameState_Movement(moment,
                new BoardState(Map.of(), Map.of(Province.Lon, Nation.ENGLAND))), Nation.ENGLAND);
        require(empty.encode(List.of()).isEmpty() && empty.decode(List.of()).isEmpty(), "Empty live action");
    }

    private static void sourceExport() throws Exception {
        DiploBNTrainingExporter exporter = new DiploBNTrainingExporter(7);
        String payload = game(PHASE);
        StringBuilder examples = new StringBuilder(), rejected = new StringBuilder();
        var counts = export(exporter, payload, examples, rejected);
        require(counts.examples() == 2 && counts.rejectionRecords() == 0, "Both source submissions exported");
        var rows = examples.toString().lines().map(TrainingExportSelfCheck::object).toList();
        require(rows.getFirst().get("split").equals(rows.getLast().get("split")), "Game-level split");
        for (var row : rows) {
            require(row.get("value_target") == null && row.get("communication_type") == null, "No invented labels");
            var input = JSON.object(row.get("input"), "$.input");
            require(!input.containsKey("action") && !input.containsKey("source_status")
                    && !input.containsKey("value_target"), "Targets and metadata stay outside inputs");
            var slots = JSON.array(input.get("slots"), "$.slots");
            var action = JSON.array(row.get("action"), "$.action");
            require(slots.size() == action.size(), "Action shape");
            List<Object> labels = new ArrayList<>();
            for (int index = 0; index < slots.size(); index++) {
                var slot = JSON.object(slots.get(index), "$.slot");
                var options = JSON.array(slot.get("options"), "$.options");
                int selected = JSON.integer(action.get(index), "$.action");
                require(selected >= 0 && selected < options.size(), "Action index in mask");
                labels.add(options.get(selected));
            }
            require(labels.equals(row.get("power").equals("ENGLAND")
                    ? List.of("FLEET ENG CONVOY Lon Bel", "ARMY Lon MOVE Bel -")
                    : List.of("FLEET MAO MOVE SpaNC -")), "Observed orders retained, including failed moves");
        }
        StringBuilder replay = new StringBuilder();
        export(exporter, payload, replay, new StringBuilder());
        require(examples.toString().equals(replay.toString()), "Byte-for-byte replay");
        excluded(exporter, game(PHASE.replace("[\"Lon\",[\"m\",\"Bel\"],\"s\"],", "")), "INVALID_ACTION", 1);
        excluded(exporter, game(PHASE.replace("\"Bel\"],\"s\"]", "\"MAO\"],\"s\"]")), "INVALID_ACTION", 1);
        excluded(exporter, game(PHASE.replace("\"Orders\":", "\"Unused\":")), "MISSING_FIELDS", 0);
        excluded(exporter, game(PHASE.replace("[\"Lon\",[\"m\",\"Bel\"],\"s\"]",
                "[\"Lon\",[\"m\",\"Bel\"],\"s\"],[\"Lon\",\"h\",\"s\"]")), "INVALID_ACTION", 1);
        excluded(exporter, game("""
                {"Phase":19011,"Status":"AwaitingOrders","Units":{},"SupplyCenters":{},"Orders":{}}
                """), "EMPTY_POSITION", 0);
        excluded(exporter, game("""
                {"Phase":19011,"Status":"AwaitingOrders","Units":{},
                 "SupplyCenters":{"England":["Lon"]},"Orders":{}}
                """), "NO_UNITS", 0);
        excluded(exporter, game(PHASE + "," + PHASE), "DUPLICATE_PHASE", 0);
        excluded(exporter, "{\"GamePhases\":[]}", "INVALID_GAME", 0);
        excluded(exporter, "{broken", "INVALID_GAME", 0);
        excluded(exporter, game(PHASE + ",{\"Phase\":19013,\"Status\":\"AwaitingOrders\"}"),
                "UNSUPPORTED_PHASE", 2);
        StringBuilder failures = new StringBuilder();
        require(exporter.write("DIPLOBN", "42", payload, "wrong", new StringBuilder(), failures)
                .examples() == 0 && failures.toString().contains("PAYLOAD_HASH_MISMATCH"), "Verify payload hash");
        failures.setLength(0);
        require(exporter.write("LOCAL_FILE", "42", payload, "wrong", new StringBuilder(), failures)
                .examples() == 0 && failures.toString().contains("UNSUPPORTED_SOURCE"), "Report other sources");
        Set<EncodingPurpose> splits = new HashSet<>();
        for (int index = 0; index < 100; index++)
            splits.add(exporter.split("DIPLOBN", Integer.toString(index)));
        require(splits.size() == 3, "All split buckets exercised");
        require(exporter.split("DIPLOBN", "42") == EncodingPurpose.TEST
                && exporter.split("DIPLOBN", "12345") == EncodingPurpose.TRAIN, "Fixed split examples");
        expect(IllegalArgumentException.class, () -> exporter.split("DIPLOBN", ""));
        Appendable broken = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("Deliberate output failure");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        expect(IOException.class, () -> exporter.write("DIPLOBN", "42", payload,
                SourceFingerprints.sha256Hex(payload), broken, new StringBuilder()));
    }

    private static void metadataAndDiagnostics() throws Exception {
        var exporter = new DiploBNTrainingExporter(7);
        String metadata = """
                "CommunicationType":"Full","ScoringSystem":"OpenTributeFrac","LimitType":"TimeLimited",
                "DeadlineType":"Live","Modality":"Online","DrawSize":6,"GameYearsCompleted":10,
                "Players":{"England":"EXCLUDED_NAME"},"PlayerIDs":{"England":123},
                "ResultSummary":{"England":{"CenterCount":14,"Score":140,"Rank":1,
                  "InGameAtEnd":"true","UnexcusedResignation":false,"Note":"EXCLUDED_NOTE"}},
                """;
        String payload = "{" + metadata + game(PHASE).substring(1);
        StringBuilder examples = new StringBuilder(), rejected = new StringBuilder();
        var report = detailed(exporter, payload, examples, rejected);
        require(report.counts().equals(new DiploBNTrainingExporter.Counts(2, 0)), "Metadata preserves acceptance");
        require(report.metadataAvailable() && report.metadataWarnings() == 0, "Metadata read without warnings");
        require(report.communicationType().equals("Full"), "Literal communication label");
        var record = object(report.gameJson());
        var source = JSON.object(record.get("source_metadata"), "$.source_metadata");
        require(source.get("LimitType").equals("TimeLimited")
                && source.get("ScoringSystem").equals("OpenTributeFrac"), "Scoring and ending labels");
        var results = JSON.object(source.get("ResultSummary"), "$.results");
        var england = JSON.object(results.get("England"), "$.England");
        require(((Number) england.get("Score")).intValue() == 140
                && england.get("InGameAtEnd").equals("true")
                && Boolean.FALSE.equals(england.get("UnexcusedResignation")), "Preserve source scalar types");
        require(!report.gameJson().contains("EXCLUDED_") && !report.gameJson().contains("PlayerIDs"), "Exclude identities and notes");
        StringBuilder plain = new StringBuilder();
        export(exporter, game(PHASE), plain, new StringBuilder());
        var annotatedRows = examples.toString().lines().map(TrainingExportSelfCheck::object).toList();
        var plainRows = plain.toString().lines().map(TrainingExportSelfCheck::object).toList();
        for (int index = 0; index < annotatedRows.size(); index++) {
            var annotated = annotatedRows.get(index);
            require(annotated.get("input").equals(plainRows.get(index).get("input"))
                    && annotated.get("action").equals(plainRows.get(index).get("action")), "No metadata in model inputs");
            require(annotated.get("communication_type").equals("Full")
                    && annotated.get("value_target") == null, "Observed communication without value target");
        }
        String invalid = PHASE.replace("[\"Lon\",[\"m\",\"Bel\"],\"s\"]",
                "[\"Lon\",[\"m\",\"NTH\"],[\"f\",\"Invalid move path\"]]");
        rejected.setLength(0);
        report = detailed(exporter, game(invalid), new StringBuilder(), rejected);
        require(report.rejectionReasons().equals(Map.of("INVALID_ACTION", 1L)), "Reason counts");
        var error = firstError(rejected);
        require(error.get("order").equals("ARMY Lon MOVE NTH -")
                && error.get("location").equals("Lon")
                && ((Number) error.get("order_index")).intValue() == 0, "Source order index, not encoded slot index");
        var result = JSON.object(error.get("source_result"), "$.source_result");
        require(Boolean.FALSE.equals(result.get("successful"))
                && result.get("reason").equals("Invalid move path"), "Original resolution detail");
        rejected.setLength(0);
        detailed(exporter, game(invalid.replace(",[\"f\",\"Invalid move path\"]", "")), new StringBuilder(), rejected);
        require(firstError(rejected).get("source_result") == null, "Missing source result remains unknown");
        rejected.setLength(0);
        detailed(exporter, game(PHASE.replace("[\"Lon\",[\"m\",\"Bel\"],\"s\"],", "")), new StringBuilder(), rejected);
        error = firstError(rejected);
        require(error.get("code").equals("MISSING_ORDER") && error.get("order") == null
                && error.get("source_result") == null && error.get("location").equals("Lon"), "Missing-order diagnostic");
        rejected.setLength(0);
        detailed(exporter, game(PHASE.replace("[\"Lon\",[\"m\",\"Bel\"],\"s\"]",
                        "[\"Lon\",[\"m\",\"Bel\"],\"s\"],[\"Lon\",\"h\",[\"f\",\"Second submission\"]]")),
                new StringBuilder(), rejected);
        error = firstError(rejected);
        result = JSON.object(error.get("source_result"), "$.source_result");
        require(error.get("code").equals("DUPLICATE_UNIT")
                && result.get("reason").equals("Second submission"), "Duplicate issuers retain their own result");
        require(detailed(exporter, game(PHASE.replace("\"f\"", "[\"f\",\"Invalid Order\"]")),
                new StringBuilder(), new StringBuilder()).counts().examples() == 2, "Historical failure is not a filter");
        payload = "{\"CommunicationType\":[],\"DrawSize\":{},\"ResultSummary\":{\"England\":{\"Score\":[]}},"
                + game(PHASE).substring(1);
        report = detailed(exporter, payload, new StringBuilder(), new StringBuilder());
        require(report.metadataWarnings() == 3 && report.communicationType() == null
                && report.counts().examples() == 2, "Malformed optional metadata warns without excluding actions");
        require(report.gameJson().contains("expected a scalar"), "Metadata warnings are written");
        report = detailed(exporter, "{\"CommunicationType\":\"Future\\\"Mode\\n\",\"GamePhases\":[]}",
                new StringBuilder(), new StringBuilder());
        require(report.metadataAvailable() && report.communicationType().equals("Future\"Mode\n")
                && report.counts().rejectionRecords() == 1, "Metadata survives invalid game and JSON escaping");
        object(report.gameJson());
        require(!detailed(exporter, "{broken", new StringBuilder(), new StringBuilder()).metadataAvailable(),
                "Unreadable metadata marked unavailable");
        require(!exporter.writeDetailed("DIPLOBN", "42", game(PHASE), "wrong",
                new StringBuilder(), new StringBuilder()).metadataAvailable(), "Unverified payload metadata not read");
        var immutable = report;
        expect(UnsupportedOperationException.class, () -> immutable.rejectionReasons().clear());
    }

    private static Map<String, Object> firstError(StringBuilder rejected) {
        var row = object(rejected.toString().lines().findFirst().orElseThrow());
        return JSON.object(JSON.array(row.get("errors"), "$.errors").getFirst(), "$.errors[0]");
    }

    private static DiploBNTrainingExporter.Report detailed(DiploBNTrainingExporter exporter, String payload,
                                                           StringBuilder examples, StringBuilder rejected) throws IOException {
        return exporter.writeDetailed("DIPLOBN", "42", payload, SourceFingerprints.sha256Hex(payload), examples, rejected);
    }

    private static void application() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path root = Files.createTempDirectory("jreferee-training-check-");
        Path database = root.resolve("catalog.sqlite"), output = root.resolve("export");
        Path partial = root.resolve("partial");
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE catalog_game (source_type TEXT, external_key TEXT,
                            source_payload TEXT, source_payload_sha256 TEXT)
                        """);
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO catalog_game VALUES(?,?,?,?)")) {
                    for (int index = 0; index < 40; index++) {
                        String mode = switch (index % 4) {
                            case 0 -> "\"CommunicationType\":\"Full\",";
                            case 1 -> "\"CommunicationType\":\"UnmappedMode\",";
                            case 2 -> "";
                            default -> "\"CommunicationType\":[],";
                        };
                        String payload = "{" + mode + game(PHASE + "," + PHASE.replace("19011", "19012")).substring(1);
                        insert.setString(1, "DIPLOBN");
                        insert.setString(2, Integer.toString(index));
                        insert.setString(3, payload);
                        insert.setString(4, SourceFingerprints.sha256Hex(payload));
                        insert.executeUpdate();
                    }
                }
            }
            byte[] before = Files.readAllBytes(database);
            String[] arguments = {"--database", database.toString(), "--output", output.toString(), "--split-seed", "7"};
            run(arguments);
            require(Arrays.equals(before, Files.readAllBytes(database)), "Database bytes unchanged");
            Map<String, Object> manifest = object(Files.readString(output.resolve("manifest.json")));
            require(Boolean.TRUE.equals(manifest.get("complete")), "Completion manifest");
            require(((Number) manifest.get("games_read")).intValue() == 40, "All games read");
            require(((Number) manifest.get("rejection_records")).intValue() == 0, "No unexpected exclusions");
            require(((Number) manifest.get("metadata_unavailable_games")).intValue() == 0, "Metadata availability count");
            require(((Number) manifest.get("games_with_metadata_warnings")).intValue() == 10, "Warning game count");
            var modes = JSON.array(manifest.get("communication_types"), "$.communication_types");
            require(modes.size() == 3, "Literal and missing communication groups");
            for (Object value : modes) {
                var mode = JSON.object(value, "$.mode");
                int expected = mode.get("communication_type") == null ? 20 : 10;
                require(((Number) mode.get("games")).intValue() == expected
                        && ((Number) mode.get("accepted_examples")).intValue() == expected * 4
                        && ((Number) mode.get("rejection_records")).intValue() == 0, "Mode counts count games once");
            }
            var reports = Files.readAllLines(output.resolve("games.jsonl"));
            require(reports.size() == 40, "One metadata report per catalog entry");
            require(JSON.object(manifest.get("rejection_reasons"), "$.reasons").isEmpty(), "Empty reason totals");
            Set<String> allKeys = new HashSet<>();
            for (var split : EncodingPurpose.values()) {
                var rows = Files.readAllLines(output.resolve(split.name().toLowerCase(Locale.ROOT) + ".jsonl"));
                require(!rows.isEmpty(), "Each output split populated");
                Map<String, Integer> keys = new HashMap<>();
                for (String line : rows) {
                    var row = object(line);
                    require(row.get("split").equals(split.name()), "Correct output file");
                    keys.merge((String) row.get("game_key"), 1, Integer::sum);
                }
                for (var entry : keys.entrySet()) {
                    require(allKeys.add(entry.getKey()), "No cross-split game leakage");
                    require(entry.getValue() == 4, "Both phases and nations retained together");
                }
                String count = split.name().toLowerCase(Locale.ROOT) + "_examples";
                require(((Number) manifest.get(count)).intValue() == rows.size(), "Manifest example count");
            }
            require(allKeys.size() == 40, "All identities retained");
            expect(IllegalArgumentException.class, () -> run(arguments));
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE catalog_game SET external_key = '' WHERE external_key = '0'");
            }
            expect(IllegalArgumentException.class, () -> run(
                    new String[]{"--database", database.toString(), "--output", partial.toString()}));
            require(!Files.exists(partial.resolve("manifest.json")), "Failed export is not marked complete");
            expect(IllegalArgumentException.class, () -> run(new String[]{"--unknown", "x"}));
            expect(IllegalArgumentException.class, () -> run(new String[]{"--split-seed", "7", "--split-seed", "8"}));
        } finally {
            for (Path directory : List.of(output, partial)) {
                for (String name : List.of("train.jsonl", "validation.jsonl", "test.jsonl",
                        "rejected.jsonl", "games.jsonl", "manifest.json"))
                    Files.deleteIfExists(directory.resolve(name));
                Files.deleteIfExists(directory);
            }
            Files.deleteIfExists(database);
            Files.delete(root);
        }
    }

    private static void run(String[] arguments) throws Exception {
        PrintStream original = System.out;
        try (PrintStream captured = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            TrainingExportApp.main(arguments);
        } finally {
            System.setOut(original);
        }
    }

    private static String game(String phases) {
        return "{\"GamePhases\":[" + phases + "]}";
    }

    private static Map<String, Object> object(String text) {
        return JSON.object(JsonReader.read(text), "$");
    }

    private static DiploBNTrainingExporter.Counts export(DiploBNTrainingExporter exporter, String payload,
                                                         StringBuilder examples, StringBuilder rejected) throws IOException {
        return exporter.write("DIPLOBN", "42", payload, SourceFingerprints.sha256Hex(payload), examples, rejected);
    }

    private static void excluded(DiploBNTrainingExporter exporter, String payload, String reason, int accepted)
            throws IOException {
        StringBuilder examples = new StringBuilder(), rejected = new StringBuilder();
        var result = export(exporter, payload, examples, rejected);
        require(result.examples() == accepted && result.rejectionRecords() > 0, "Exclusion count: " + reason);
        require(rejected.toString().lines().map(TrainingExportSelfCheck::object)
                .anyMatch(row -> row.get("reason").equals(reason)), "Exclusion reason: " + reason);
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