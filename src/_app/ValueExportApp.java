package _app;

import agents.encoding.EncodingPurpose;
import io.training.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static io.training.ValueDatasetJson.*;

/**
 * Exports completed-game value observations after provider-neutral checks.
 * Source adapters are selected explicitly; there is no default provider parser.
 */
public final class ValueExportApp {


    // Provider registration \\

    private static final Map<String, ValueSourceAdapter> ADAPTERS = Map.of(
            "DIPLOBN", (source, key, payload, expectedHash) -> {
                DiploBNValueAdapter.Result result = new DiploBNValueAdapter().read(
                        source, key, payload, expectedHash);
                return new ValueSourceAdapter.Result(
                        result.game(),
                        result.rejection().map(Enum::name),
                        result.diagnostics()
                );
            }
    );

    private ValueExportApp() { }


    // Application \\

    public static void main(String[] args) throws Exception {

        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("--database", "--movement-dataset", "--output");

        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ValueExportApp --database <catalog.sqlite> "
                    + "--movement-dataset <v4-export> --output <new-directory>");
            return;
        }

        for (int index = 0; index < args.length; index += 2)
            if (index + 1 >= args.length || !allowed.contains(args[index])
                    || options.putIfAbsent(args[index], args[index + 1]) != null)
                throw new IllegalArgumentException("Invalid or repeated arguments; use --help");

        if (!options.keySet().equals(allowed))
            throw new IllegalArgumentException("All three paths are required");

        Path database = Path.of(options.get("--database")).toAbsolutePath().normalize();
        Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        Path movementPath = Path.of(options.get("--movement-dataset"), "manifest.json");

        if (!Files.isRegularFile(database))
            throw new IllegalArgumentException("Database not found: " + database);
        if (Files.exists(output))
            throw new FileAlreadyExistsException(output.toString());

        String movementHash = hash(movementPath);
        Map<String, Object> movement = parse(Files.readString(movementPath));

        if (!movementHash.equals(hash(movementPath)))
            throw new IOException("Movement manifest changed while reading");
        if (!"jreferee-diplobn-imitation-v4".equals(text(movement, "schema"))
                || !Boolean.TRUE.equals(movement.get("complete")))
            throw new IOException("Expected the completed movement v4 manifest");

        Object seedValue = movement.get("split_seed");
        if (!(seedValue instanceof Long || seedValue instanceof Integer))
            throw new IOException("Invalid movement split seed");
        long seed = ((Number) seedValue).longValue();

        Class.forName("org.sqlite.JDBC");
        Files.createDirectory(output);

        Map<EncodingPurpose, long[]> totals = new EnumMap<>(EncodingPurpose.class);
        totals.put(EncodingPurpose.TRAIN, new long[2]);
        totals.put(EncodingPurpose.VALIDATION, new long[2]);
        Map<String, Long> reasons = new TreeMap<>();
        long scanned = 0, skippedTest = 0;
        long started = System.nanoTime();
        String url = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url)) {

            try (Statement guard = connection.createStatement()) {
                guard.execute("PRAGMA query_only=ON");
            }
            connection.setAutoCommit(false);

            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery(
                         "SELECT source_type, external_key, source_payload_sha256 "
                                 + "FROM catalog_game ORDER BY source_type, external_key");
                 PreparedStatement payload = connection.prepareStatement(
                         "SELECT source_payload FROM catalog_game "
                                 + "WHERE source_type=? AND external_key=?");
                 BufferedWriter train = writer(output, "train.jsonl");
                 BufferedWriter validation = writer(output, "validation.jsonl");
                 BufferedWriter games = writer(output, "games.jsonl")) {

                while (rows.next()) {

                    String source = rows.getString(1);
                    String key = rows.getString(2);
                    String payloadHash = rows.getString(3);
                    EncodingPurpose split = ValueOutcomeAudit.split(seed, source, key);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("source", source);
                    entry.put("game_key", key);
                    entry.put("payload_sha256", payloadHash);
                    entry.put("split", split.name());
                    entry.put("positions", 0);

                    if (split == EncodingPurpose.TEST) {

                        entry.put("status", "TEST_SKIPPED");
                        skippedTest++;

                    } else {

                        ValueSourceAdapter adapter = ADAPTERS.get(source);
                        ValueSourceAdapter.Result result;

                        if (adapter == null) {

                            result = ValueSourceAdapter.Result.rejected("UNSUPPORTED_SOURCE");

                        } else {

                            payload.setString(1, source);
                            payload.setString(2, key);

                            try (ResultSet selected = payload.executeQuery()) {
                                if (!selected.next())
                                    throw new SQLException("Catalog entry disappeared");
                                result = adapter.read(source, key, selected.getString(1), payloadHash);
                                if (selected.next())
                                    throw new SQLException("Duplicate catalog source/game identity");
                            }

                        }

                        if (result.game().isPresent()) {

                            ValueDataset.GameExample game = result.game().orElseThrow();

                            if (!game.source().equals(source) || !game.gameKey().equals(key)
                                    || !game.payloadSha256().equals(payloadHash))
                                throw new IllegalStateException("Adapter changed source identity");

                            entry.put("source_scoring", game.outcome().sourceScoring().orElse(null));
                            entry.put("source_limit", game.outcome().sourceLimit().orElse(null));

                        }

                        result = result.validatePositions();
                        entry.put("diagnostics", result.diagnostics());

                        if (result.game().isPresent()) {

                            ValueDataset.GameExample game = result.game().orElseThrow();
                            ValueRewardContract.Assessment assessment = game.assess(
                                    ValueRewardContract.defaults());

                            if (assessment.rejection().isPresent())
                                throw new IllegalStateException(
                                        "Adapter admitted an ineligible outcome: "
                                                + assessment.rejection().orElseThrow());

                            String line = encode(game, split);
                            BufferedWriter destination = split == EncodingPurpose.TRAIN
                                    ? train : validation;
                            destination.write(line);
                            destination.newLine();

                            totals.get(split)[0]++;
                            totals.get(split)[1] += game.positions().size();
                            entry.put("status", "ACCEPTED");
                            entry.put("positions", game.positions().size());
                            entry.put("termination", game.outcome().termination().name());

                        } else {

                            String reason = result.rejection().orElseThrow();
                            entry.put("status", "REJECTED");
                            entry.put("reason", reason);
                            reasons.merge(source + ":" + reason, 1L, Long::sum);

                        }

                    }

                    games.write(json(entry));
                    games.newLine();
                    scanned++;

                    if (scanned % 100 == 0) {
                        train.flush();
                        validation.flush();
                        games.flush();
                        System.out.println("Scanned " + scanned + "; TEST skipped " + skippedTest);
                    }

                }

            }

            connection.rollback();

        }

        Map<String, Object> files = new TreeMap<>();
        for (EncodingPurpose split : totals.keySet()) {
            String name = JsonlValueDataset.fileName(split);
            files.put(name, Map.of(
                    "sha256", hash(output.resolve(name)),
                    "games", totals.get(split)[0],
                    "positions", totals.get(split)[1]
            ));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", JsonlValueDataset.SCHEMA);
        manifest.put("complete", true);
        manifest.put("reward_contract", ValueRewardContract.VERSION);
        manifest.put("position_validation", ValueSourceAdapter.POSITION_VALIDATION);
        manifest.put("target_scoring", "SUM_OF_SQUARES");
        manifest.put("split_seed", seed);
        manifest.put("movement_manifest_sha256", movementHash);
        manifest.put("test_exported", false);
        manifest.put("test_payloads_opened", 0);
        manifest.put("test_games_skipped", skippedTest);
        manifest.put("catalog_games_scanned", scanned);
        manifest.put("registered_sources", new TreeSet<>(ADAPTERS.keySet()));
        manifest.put("scope", "CURRENT_CATALOG_ALL_COMMUNICATION_TYPES");
        manifest.put("rejections", reasons);
        manifest.put("games_sha256", hash(output.resolve("games.jsonl")));
        manifest.put("files", files);
        manifest.put("seconds", (System.nanoTime() - started) / 1e9);

        Files.writeString(output.resolve("manifest.pending"), json(manifest) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(output.resolve("manifest.pending"), output.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE);

        System.out.println(json(manifest));
        System.out.println("Export complete. Run ValueDatasetCheckApp before training.");

    }

    private static BufferedWriter writer(Path directory, String name) throws IOException {
        return Files.newBufferedWriter(directory.resolve(name),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

}