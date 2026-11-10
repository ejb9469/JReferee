package _app;

import io.catalog.SourceFingerprints;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes one cataloged DiploBN game's original JSON to a new file.
 * The database is opened read-only; the stored payload is not reformatted.
 */
public final class DBNGameExportApp {

    private DBNGameExportApp() { }

    public static void main(String[] args) throws Exception {
        Path database = Path.of("data", "diplobn-catalog.sqlite");
        Path output = null;
        long gameId = 0;
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) {
                System.out.println(usage());
                return;
            }
            if (!seen.add(args[index]) || index + 1 >= args.length)
                throw new IllegalArgumentException(usage());
            switch (args[index]) {
                case "--game-id" -> gameId = Long.parseLong(args[++index]);
                case "--database" -> database = Path.of(args[++index]);
                case "--output" -> output = Path.of(args[++index]);
                default -> throw new IllegalArgumentException(usage());
            }
        }

        if (gameId <= 0)
            throw new IllegalArgumentException(usage());
        database = database.toAbsolutePath().normalize();
        output = (output == null ? Path.of("data", "diplobn-" + gameId + ".json") : output)
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(database))
            throw new IllegalArgumentException("Database not found: " + database);
        if (Files.exists(output))
            throw new IllegalArgumentException("Output already exists: " + output);

        Class.forName("org.sqlite.JDBC");
        String payload;
        String url = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT source_payload, source_payload_sha256
                     FROM catalog_game
                     WHERE source_type = ? AND external_key = ?
                     """)) {
            statement.setString(1, "DIPLOBN");
            statement.setString(2, Long.toString(gameId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    throw new IllegalArgumentException("DiploBN game not found in catalog: " + gameId);
                payload = result.getString("source_payload");
                if (payload == null || payload.isBlank()
                        || !SourceFingerprints.sha256Hex(payload)
                        .equals(result.getString("source_payload_sha256")))
                    throw new IllegalStateException("Stored payload is missing or its checksum differs: " + gameId);
                if (result.next())
                    throw new IllegalStateException("Multiple catalog entries for DiploBN game: " + gameId);
            }
        }

        Files.writeString(output, payload, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Exported DiploBN game " + gameId + ": " + output);
    }

    private static String usage() {
        return "Usage: DBNGameExportApp --game-id <positive-id> "
                + "[--database <catalog.sqlite>] [--output <new-json-file>]";
    }
}