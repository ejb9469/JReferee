package _app;

import agents.encoding.EncodingPurpose;
import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.ValueOutcomeAudit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/**
 * Read-only source-outcome audit. TEST payloads are never selected.
 * Output contains selected metadata and counts, not player identities or orders.
 */
@Deprecated  // on arrival
public final class ValueOutcomeAuditApp {


    // Construction \\

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private ValueOutcomeAuditApp() { }


    // Application \\

    public static void main(String[] args) throws Exception {

        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("--database", "--movement-dataset", "--output");

        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ValueOutcomeAuditApp --database <catalog.sqlite> "
                    + "--movement-dataset <existing-v4-export> --output <new-directory>");
            return;
        }

        for (int index = 0; index < args.length; index += 2)
            if (index + 1 >= args.length || !allowed.contains(args[index])
                    || options.putIfAbsent(args[index], args[index + 1]) != null)
                throw new IllegalArgumentException("Invalid or repeated arguments; use --help");

        if (!options.keySet().equals(allowed))
            throw new IllegalArgumentException("All three paths are required; use --help");

        Path database = Path.of(options.get("--database")).toAbsolutePath().normalize();
        Path dataset = Path.of(options.get("--movement-dataset"));
        Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        Path manifestPath = dataset.resolve("manifest.json");

        if (!Files.isRegularFile(database))
            throw new IllegalArgumentException("Database not found: " + database);
        if (Files.exists(output))
            throw new FileAlreadyExistsException(output.toString());

        String movementHash = hash(manifestPath);
        Map<String, Object> movement = JSON.object(
                JsonReader.read(Files.readString(manifestPath)), "$");
        if (!movementHash.equals(hash(manifestPath)))
            throw new IOException("Movement manifest changed while reading");

        if (!"jreferee-diplobn-imitation-v4".equals(movement.get("schema"))
                || !Boolean.TRUE.equals(movement.get("complete")))
            throw new IllegalArgumentException("Expected a completed v4 movement export");

        Object seedValue = movement.get("split_seed");
        if (!(seedValue instanceof Long || seedValue instanceof Integer))  // ugly, but this is how we work with JSON objects
            throw new IllegalArgumentException("Expected an integer movement split seed");
        long seed = ((Number) seedValue).longValue();

        Class.forName("org.sqlite.JDBC");
        Files.createDirectory(output);

        Map<String, Long> findings = new TreeMap<>();
        Map<String, Long> groups = new TreeMap<>();
        long inspected = 0, skippedTest = 0;
        long started = System.nanoTime();
        Path games = output.resolve("games.jsonl");
        String url = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url)) {

            try (Statement guard = connection.createStatement()) {
                guard.execute("PRAGMA query_only=ON");
            }
            connection.setAutoCommit(false);

            try (Statement index = connection.createStatement();
                 ResultSet rows = index.executeQuery(
                         "SELECT source_type, external_key FROM catalog_game "
                                 + "ORDER BY source_type, external_key");
                 PreparedStatement payload = connection.prepareStatement(
                         "SELECT source_payload, source_payload_sha256 FROM catalog_game "
                                 + "WHERE source_type=? AND external_key=?");
                 BufferedWriter writer = Files.newBufferedWriter(
                         games, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {

                while (rows.next()) {

                    String source = rows.getString(1), key = rows.getString(2);
                    EncodingPurpose split = ValueOutcomeAudit.split(seed, source, key);

                    if (split == EncodingPurpose.TEST) {
                        skippedTest++;
                        continue;
                    }

                    payload.setString(1, source);
                    payload.setString(2, key);

                    Map<String, Object> report;
                    try (ResultSet selected = payload.executeQuery()) {
                        if (!selected.next())
                            throw new SQLException("Catalog entry disappeared within the read transaction");
                        report = ValueOutcomeAudit.inspect(
                                source, key, selected.getString(1), selected.getString(2));
                        if (selected.next())
                            throw new SQLException("Duplicate catalog source/game identity");
                    }

                    report.put("split", split.name());
                    writer.write(ValueOutcomeAudit.json(report));
                    writer.newLine();

                    String finding = (String) report.get("finding");
                    findings.merge(finding, 1L, Long::sum);
                    String group = ValueOutcomeAudit.json(Arrays.asList(
                            split.name(), report.get("scoring_label"),
                            report.get("limit_label"), report.get("last_status"), finding));
                    groups.merge(group, 1L, Long::sum);
                    inspected++;

                    if (inspected % 100 == 0) {
                        writer.flush();
                        System.out.println("Inspected " + inspected + "; TEST skipped " + skippedTest);
                    }

                }

            }

            connection.rollback();

        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema", "jreferee-value-outcome-audit-v1");
        summary.put("complete", true);
        summary.put("value_targets", false);
        summary.put("termination_verified", false);
        summary.put("test_payloads_opened", 0);
        summary.put("split_seed", seed);
        summary.put("movement_manifest_sha256", movementHash);
        summary.put("scope", "CURRENT_CATALOG_TRAIN_VALIDATION_ALL_COMMUNICATION_TYPES");
        summary.put("games_inspected", inspected);
        summary.put("test_games_skipped", skippedTest);
        summary.put("findings", findings);
        summary.put("group_key_fields", List.of(
                "split", "scoring_label", "limit_label", "last_status", "finding"));
        summary.put("groups", groups);
        summary.put("games_sha256", hash(games));
        summary.put("seconds", (System.nanoTime() - started) / 1e9);

        String text = ValueOutcomeAudit.json(summary);
        Files.writeString(output.resolve("manifest.json"), text + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        System.out.println(text);
        System.out.println("Audit only: no game was certified for value training.");

    }


    // Artifact fingerprint \\

    private static String hash(Path path) throws IOException, NoSuchAlgorithmException {
        try (DigestInputStream input = new DigestInputStream(
                Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        }
    }

}