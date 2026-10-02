package _app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;


public final class RouteCorpusSampleApp {

    private RouteCorpusSampleApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: RouteCorpusSampleApp [database-path]");

        Path database = (args.length == 0
                ? Path.of("data", "diplobn-catalog.sqlite")
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        if (!Files.isRegularFile(database))
            throw new IllegalArgumentException("Database not found: " + database);

        Class.forName("org.sqlite.JDBC");

        String url = "jdbc:sqlite:"
                + database.toUri().toASCIIString()
                + "?mode=ro";

        String sql = """
                SELECT external_key, source_payload
                FROM catalog_game
                WHERE source_type = ?
                  AND external_key IN (?, ?)
                ORDER BY external_key
                """;

        Set<String> found = new HashSet<>();

        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, "DIPLOBN");
            statement.setString(2, "10667");
            statement.setString(3, "11682");

            try (ResultSet rows = statement.executeQuery()) {

                while (rows.next()) {

                    String id = rows.getString("external_key");

                    if (!found.add(id))
                        throw new IllegalStateException(
                                "Duplicate catalog source identity: DIPLOBN/" + id);

                    String payload = rows.getString("source_payload");

                    if (payload == null || payload.isBlank())
                        throw new IllegalStateException(
                                "Missing source payload for DIPLOBN/" + id);

                    System.out.println("===== BEGIN DIPLOBN/" + id + " =====");
                    System.out.println(payload);
                    System.out.println("===== END DIPLOBN/" + id + " =====");
                    System.out.println();

                }

            }

        }

        if (!found.equals(Set.of("10667", "11682")))
            throw new IllegalStateException(
                    "Expected both sample games; found: " + found);

    }

}