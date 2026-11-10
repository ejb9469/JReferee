package testing.selfcheck;

import _app.*;
import agents.encoding.*;
import agents.policy.*;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.*;
import phase.UnitId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Export/reader round trips, strict failures, learning, persistence, and
 * fixed-root probabilities. Requires the existing SQLite JDBC driver.
 */
public final class MovementPolicySelfCheck {

    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));
    private static int checks;

    private MovementPolicySelfCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jreferee-policy-check-");
        try {
            Path database = root.resolve("catalog.sqlite"), data = root.resolve("data");
            fixture(database);
            quiet(() -> TrainingExportApp.main(new String[]{"--database", database.toString(), "--output", data.toString()}));
            reading(data);
            conditionalPolicy();
            corruptions(data);
            learning(data, root);
            applications(data, root);
            Path selected = root.resolve("selected");
            quiet(() -> TrainingExportApp.main(new String[]{"--database", database.toString(), "--output", selected.toString(),
                    "--communication-types", "None", "--include-unknown", "false"}));
            var filtered = new JsonlMovementDataset(selected);
            require(filtered.examples(EncodingPurpose.TRAIN) == 0, "Selection retained");
            expect(IllegalArgumentException.class, () -> PolicyTraining.fit(filtered, new PolicyTraining.Settings(), ignored -> { }));
            Files.writeString(selected.resolve("train.jsonl"), Files.readAllLines(data.resolve("train.jsonl")).getFirst() + "\n");
            expect(IOException.class, () -> consume(filtered, EncodingPurpose.TRAIN));
            System.out.println("MovementPolicySelfCheck passed: " + checks + " checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void reading(Path directory) throws Exception {
        var dataset = new JsonlMovementDataset(directory);
        require(dataset.filter().equals(new TrainingFilter()), "Press and unknown defaults retained");
        require(dataset.fingerprint().equals(new JsonlMovementDataset(directory).fingerprint()), "Stable index fingerprint");
        for (EncodingPurpose split : EncodingPurpose.values()) {
            long count = 0;
            Set<String> labels = new HashSet<>();
            try (var cursor = dataset.open(split)) {
                MovementDataset.Example example;
                while ((example = cursor.next()) != null) {
                    count++;
                    labels.add(example.communicationType());
                    var input = example.input();
                    var encoding = encoding(input.moment(), input.power());
                    var live = encoding.input();
                    require(input.units().equals(live.units()) && input.centers().equals(live.centers()), "Board round trip");
                    for (int slot = 0; slot < input.slots().size(); slot++)
                        require(input.slots().get(slot).options().stream().map(MovementInput.Option::label).toList()
                                        .equals(live.slots().get(slot).options().stream().map(MovementInput.Option::label).toList()),
                                "Live and stored masks match");
                    require(encoding.encode(encoding.decode(example.action())).equals(example.action()), "Action round trip");
                }
                require(cursor.next() == null && count == dataset.examples(split), "Verified EOF");
                var stats = cursor.statistics();
                require(stats.examples() == count && stats.options() == stats.cacheHits() + stats.cacheMisses(), "Reader accounting");
                require(stats.cacheHits() > 0 && stats.peakCachedOptions() <= JsonlMovementDataset.DEFAULT_TOKEN_CACHE
                        && stats.sha256().length() == 64, "Bounded cached options and split digest");
            }
            if (split == EncodingPurpose.TRAIN)
                require(labels.contains("Full") && labels.contains(null), "Mixed training labels");
        }
        var cursor = dataset.open(EncodingPurpose.TRAIN);
        require(cursor.next() != null && cursor.statistics().sha256() == null, "Early cursor has no verified digest");
        cursor.close();
        expect(IOException.class, cursor::next);
        try (var uncached = new JsonlMovementDataset(directory, 0).open(EncodingPurpose.TRAIN)) {
            while (uncached.next() != null) { }
            require(uncached.statistics().cacheHits() == 0 && uncached.statistics().peakCachedOptions() == 0,
                    "Cache can be disabled");
        }
        try (var bounded = new JsonlMovementDataset(directory, 8).open(EncodingPurpose.TRAIN)) {
            while (bounded.next() != null) require(bounded.statistics().peakCachedOptions() <= 8, "Exact cache bound");
        }
        for (Nation power : Nation.values()) {
            var state = (GameState_Movement) StandardGameFactory.create1901().snapshot();
            var encoding = new MovementEncoding(state, power);
            var input = encoding.input();
            var policy = new LinearMovementPolicy(1024);
            var prepared = policy.prepare(input);
            var sampled = prepared.sample(new Random(5));
            input.requireAction(sampled);
            require(prepared.greedy().equals(Collections.nCopies(input.slots().size(), 0)), "Stable argmax ties");
            double expected = -input.slots().stream().mapToDouble(slot -> Math.log(slot.options().size())).sum();
            require(Math.abs(prepared.logProbability(sampled) - expected) < 1e-12, "Uniform full-catalog mass");
            require(Math.abs(policy.bind(state).logProbability(power, encoding.decode(sampled)) - expected) < 1e-12,
                    "Root prior uses full-action probability");
            double[] altered = prepared.logProbabilities(List.of());
            altered[0] = 100;
            require(prepared.logProbabilities(List.of())[0] <= 0, "Prepared arrays are defensive");
            expect(IllegalArgumentException.class, () -> prepared.logProbabilities(sampled));
            expect(IllegalArgumentException.class, () -> prepared.logProbability(List.of()));
        }
        expect(IllegalArgumentException.class, () -> JsonReader.read("{\"x\":null,\"x\":1}"));
        expect(IllegalArgumentException.class, () -> MovementInput.Option.parse("FLEET MAO MOVE Spa -"));
        expect(IllegalArgumentException.class, () -> MovementPolicy.requireDistribution(new double[]{0, 0}, 2));
        expect(IllegalArgumentException.class, () -> MovementPolicy.requireDistribution(new double[]{Double.NaN}, 1));
    }

    private static void corruptions(Path directory) throws Exception {
        Path train = directory.resolve("train.jsonl"), manifest = directory.resolve("manifest.json");
        String original = Files.readString(train), header = Files.readString(manifest);
        String row = original.lines().findFirst().orElseThrow();
        List<String> damaged = List.of(
                row.replace("jreferee-diplobn-imitation-v4", "jreferee-diplobn-imitation-v3"),
                row.replace("jreferee-movement-v2", "jreferee-movement-v1"),
                row.replace("\"STANDARD\"", "\"VARIANT\""),
                row.replace("\"year\":1901", "\"year\":1900"),
                row.replaceFirst("\"power\":\"ENGLAND\"", "\"power\":\"FRANCE\""),
                row.replace("\"split\":\"TRAIN\"", "\"split\":\"TEST\""),
                row.replaceFirst("\"payload_sha256\":\"[^\"]+\"", "\"payload_sha256\":\"wrong\""),
                row.replaceFirst("\"action\":\\[\\d+", "\"action\":[99999"),
                row.replace("\"FLEET ENG HOLD - -\",", ""),
                row.replace("\"FLEET ENG HOLD - -\"", "\"FLEET ENG HOLD - -\",\"FLEET ENG HOLD - -\""),
                row.replace("[\"ENGLAND\",\"ARMY\",\"Lon\"]", "[\"ENGLAND\",\"FLEET\",\"Lon\"]"),
                row.replace("\"value_target\":null", "\"value_target\":null,\"value_target\":null"));
        try {
            for (String invalid : damaged) {
                require(!invalid.equals(row), "Corruption changes the fixture");
                Files.writeString(train, invalid + "\n" + original.substring(row.length() + 1));
                expect(IOException.class, () -> consume(new JsonlMovementDataset(directory), EncodingPurpose.TRAIN));
            }
            for (String invalid : List.of("", original.substring(row.length() + 1), row + "\n" + original,
                    original + row + "\n", "\n" + original, " ".repeat(JsonlMovementDataset.MAX_LINE_CHARACTERS + 1))) {
                Files.writeString(train, invalid);
                expect(IOException.class, () -> consume(new JsonlMovementDataset(directory), EncodingPurpose.TRAIN));
            }
            Files.write(train, new byte[]{(byte) 0xc3, 0x28});
            expect(IOException.class, () -> consume(new JsonlMovementDataset(directory), EncodingPurpose.TRAIN));
            Files.writeString(train, original);
            for (String invalid : List.of(header.replace("\"complete\": true", "\"complete\": false"),
                    header.replace("\"split_seed\": 7", "\"split_seed\": 9"),
                    header.replace("\"games_read\": 6", "\"games_read\": 0"))) {
                require(!invalid.equals(header), "Manifest corruption changes the fixture");
                Files.writeString(manifest, invalid);
                expect(IOException.class, () -> new JsonlMovementDataset(directory));
            }
        } finally {
            Files.writeString(train, original);
            Files.writeString(manifest, header);
        }
        var dataset = new JsonlMovementDataset(directory);
        consume(dataset, EncodingPurpose.TRAIN);
        Files.writeString(train, original.replace("\n", "\r\n"));
        try { expect(IOException.class, () -> consume(dataset, EncodingPurpose.TRAIN)); }
        finally { Files.writeString(train, original); }
    }

    private static void conditionalPolicy() throws Exception {
        var input = encoding(new GameMoment(1901, GamePhase.SPRING_MOVEMENT), Nation.ENGLAND).input();
        MovementPolicy policy = observation -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return observation; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                observation.requirePrefix(prefix);
                double[] values = new double[observation.slots().get(prefix.size()).options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                int favored = !prefix.isEmpty() && prefix.getFirst() == 1 ? 1 : 0;
                values[favored] = Math.log(0.8);
                values[1 - favored] = Math.log(0.2);
                return values;
            }
        };
        var prepared = policy.prepare(input);
        require(Math.abs(prepared.logProbability(List.of(1, 1)) - Math.log(0.16)) < 1e-12,
                "Full-action score uses conditional prefixes");
        require(prepared.greedy().equals(List.of(0, 0)), "Greedy actions use their own prefixes");
        MovementDataset memory = new MovementDataset() {
            @Override public long examples(EncodingPurpose split) { return 1; }
            @Override public String fingerprint() { return "fixture"; }
            @Override public Cursor open(EncodingPurpose split) {
                return new Cursor() {
                    private boolean read;
                    @Override public Example next() {
                        if (read) return null;
                        read = true;
                        return new Example("FIXTURE", "1", "Full", input, List.of(1, 1));
                    }
                    @Override public Statistics statistics() {
                        return new Statistics(read ? 1 : 0, 0, 0, 0, 0, 0, 0, null);
                    }
                    @Override public void close() { }
                };
            }
        };
        var result = PolicyEvaluation.evaluate(memory, EncodingPurpose.VALIDATION, policy);
        require(result.overall().correctOrders() == 1 && result.overall().exactActions() == 0,
                "Teacher-forced order and greedy-action metrics remain distinct");
    }

    private static void learning(Path directory, Path root) throws Exception {
        var dataset = new JsonlMovementDataset(directory);
        var settings = new PolicyTraining.Settings(4, 4096, 0.05, 8, 11);
        var first = PolicyTraining.fit(dataset, settings, ignored -> { });
        var second = PolicyTraining.fit(dataset, settings, ignored -> { });
        require(first.bestValidation().overall().orderNll() < first.initialValidation().overall().orderNll() * 0.35,
                "The controlled fixture learns substantially better than uniform");
        Path saved = root.resolve("first.bin"), duplicate = root.resolve("second.bin");
        first.model().save(saved);
        second.model().save(duplicate);
        byte[] bytes = Files.readAllBytes(saved);
        require(Arrays.equals(bytes, Files.readAllBytes(duplicate)), "Deterministic fitted weights");
        var loaded = LinearMovementPolicy.load(saved);
        try (var cursor = dataset.open(EncodingPurpose.TRAIN)) {
            var example = Objects.requireNonNull(cursor.next());
            double probability = first.model().prepare(example.input()).logProbability(example.action());
            require(probability == loaded.prepare(example.input()).logProbability(example.action()), "Exact saved-model scores");
            var learner = new LinearMovementPolicy.Learner(4096);
            var frozen = learner.snapshot();
            double before = frozen.prepare(example.input()).logProbability(example.action());
            learner.learn(example.input(), example.action(), 0.1);
            require(before == frozen.prepare(example.input()).logProbability(example.action()), "Snapshots are frozen");
        }
        expect(FileAlreadyExistsException.class, () -> first.model().save(saved));
        Path broken = root.resolve("broken.bin");
        Files.write(broken, Arrays.copyOf(bytes, bytes.length - 1));
        expect(IOException.class, () -> LinearMovementPolicy.load(broken));
        byte[] changed = bytes.clone();
        changed[changed.length - 40] ^= 1;
        Files.write(broken, changed);
        expect(IOException.class, () -> LinearMovementPolicy.load(broken));
        Files.write(broken, Arrays.copyOf(bytes, bytes.length + 1));
        expect(IOException.class, () -> LinearMovementPolicy.load(broken));
        expect(IllegalArgumentException.class, () -> new LinearMovementPolicy(1000));
        expect(IllegalArgumentException.class, () -> new PolicyTraining.Settings(1, 1024, Double.NaN, 1, 7));
    }

    private static void applications(Path directory, Path root) throws Exception {
        Path output = root.resolve("trained"), evaluation = root.resolve("evaluation.json");
        String[] train = {"--dataset", directory.toString(), "--output", output.toString(), "--epochs", "2",
                "--dimensions", "4096", "--shuffle-buffer", "4"};
        Path test = directory.resolve("test.jsonl");
        String original = Files.readString(test);
        try {
            Files.writeString(test, "{unreadable test data\n");
            quiet(() -> PolicyTrainingApp.main(train));
        } finally { Files.writeString(test, original); }
        var report = object(Files.readString(output.resolve("training.json")));
        require(Boolean.TRUE.equals(report.get("complete")) && Boolean.FALSE.equals(report.get("test_evaluated")),
                "Training leaves test data unread");
        expect(IllegalArgumentException.class, () -> quiet(() -> PolicyTrainingApp.main(train)));
        quiet(() -> PolicyEvaluationApp.main(new String[]{"--dataset", directory.toString(), "--model",
                output.resolve("policy.bin").toString(), "--split", "test", "--output", evaluation.toString()}));
        var result = JSON.object(object(Files.readString(evaluation)).get("result"), "$.result");
        require(result.get("split").equals("TEST"), "Test evaluation is explicit");
        expect(IllegalArgumentException.class, () -> PolicyTrainingApp.main(new String[]{"--epochs", "1", "--epochs", "2"}));
        expect(IllegalArgumentException.class, () -> PolicyEvaluationApp.main(new String[]{"--split", "train"}));
    }

    private static MovementEncoding encoding(GameMoment moment, Nation power) {
        Map<UnitId, Province> units = new LinkedHashMap<>();
        units.put(UnitId.newlyBuilt(1901, Nation.ENGLAND, UnitType.ARMY, Province.Lon), Province.Lon);
        units.put(UnitId.newlyBuilt(1901, Nation.ENGLAND, UnitType.FLEET, Province.ENG), Province.ENG);
        units.put(UnitId.newlyBuilt(1901, Nation.FRANCE, UnitType.FLEET, Province.MAO), Province.MAO);
        return new MovementEncoding(new GameState_Movement(moment, new BoardState(units,
                Map.of(Province.Lon, Nation.ENGLAND, Province.Bre, Nation.FRANCE))), power);
    }

    private static void fixture(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Map<String, String> payloads = new TreeMap<>();
        payloads.put(key(EncodingPurpose.TRAIN, 0), payload("Full"));
        payloads.put(key(EncodingPurpose.TRAIN, 1), payload(null));
        payloads.put(key(EncodingPurpose.VALIDATION, 0), payload("None"));
        payloads.put(key(EncodingPurpose.TEST, 0), payload("Full"));
        payloads.put("broken", "{broken");
        payloads.put("hash", payload("Full"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE catalog_game (source_type TEXT, external_key TEXT, source_payload TEXT, source_payload_sha256 TEXT)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO catalog_game VALUES(?,?,?,?)")) {
                for (var entry : payloads.entrySet()) {
                    insert.setString(1, "DIPLOBN");
                    insert.setString(2, entry.getKey());
                    insert.setString(3, entry.getValue());
                    insert.setString(4, entry.getKey().equals("hash") ? "wrong" : SourceFingerprints.sha256Hex(entry.getValue()));
                    insert.executeUpdate();
                }
            }
        }
    }

    private static String key(EncodingPurpose split, int ordinal) {
        int found = 0;
        var exporter = new DiploBNTrainingExporter(7);
        for (int index = 0; index < 1000; index++) {
            String key = Integer.toString(index);
            if (exporter.split("DIPLOBN", key) == split && found++ == ordinal) return key;
        }
        throw new AssertionError("Fixture split key not found");
    }

    private static String payload(String communication) {
        List<String> phases = new ArrayList<>();
        for (int year = 1901; year < 1907; year++)
            for (int season = 1; season <= 2; season++)
                phases.add("""
                        {"Phase":%d,"Status":"AwaitingOrders",
                         "SupplyCenters":{"England":["Lon"],"France":["Bre"]},
                         "Units":{"England":[["A","Lon"],["F","ENG"]],"France":[["F","MAO"]]},
                         "Orders":{"England":[["Lon",["m","Bel"],"s"],["ENG",["c","Lon","Bel"],"s"]],
                                   "France":[["MAO",["m",["Spa","nc"]],"f"]]}}
                        """.formatted(year * 10 + season));
        return "{" + (communication == null ? "" : "\"CommunicationType\":\"" + communication + "\",")
                + "\"GamePhases\":[" + String.join(",", phases) + "]}";
    }

    private static void consume(MovementDataset dataset, EncodingPurpose split) throws IOException {
        try (var cursor = dataset.open(split)) { while (cursor.next() != null) { } }
    }
    private static Map<String, Object> object(String text) { return JSON.object(JsonReader.read(text), "$"); }
    private interface Checked { void run() throws Exception; }
    private static void quiet(Checked operation) throws Exception {
        PrintStream original = System.out;
        try (PrintStream captured = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            operation.run();
        } finally { System.setOut(original); }
    }
    private static void expect(Class<? extends Exception> type, Checked action) {
        try { action.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
        checks++;
    }
}