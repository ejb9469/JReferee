package _app;

import parsing.diplobn.JsonReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;


public final class RetreatSourceInspectionApp {

    private RetreatSourceInspectionApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: RetreatSourceInspectionApp [database-path]");

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

        try (Connection connection = DriverManager.getConnection(url)) {

            inspect(connection, "40164", 6, 19031);

        }

    }


    private static void inspect(
            Connection connection,
            String gameId,
            int failingIndex,
            int expectedPhaseCode) throws SQLException {

        String payload;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_payload
                FROM catalog_game
                WHERE source_type = 'DIPLOBN'
                  AND external_key = ?
                """)) {

            statement.setString(1, gameId);

            try (ResultSet rows = statement.executeQuery()) {

                if (!rows.next())
                    throw new IllegalArgumentException(
                            "Catalog game not found: DIPLOBN/" + gameId);

                payload = rows.getString("source_payload");

                if (rows.next())
                    throw new IllegalStateException(
                            "Duplicate catalog source identity: DIPLOBN/" + gameId);

            }

        }

        Map<?, ?> root = object(JsonReader.read(payload), "game");
        List<?> phases = array(root.get("GamePhases"), "GamePhases");

        if (failingIndex < 0 || failingIndex + 1 >= phases.size())
            throw new IllegalArgumentException(
                    "Expected failing snapshot and following boundary for " + gameId);

        Map<?, ?> failing = object(phases.get(failingIndex), "failing snapshot");

        if (!(failing.get("Phase") instanceof Number phaseCode)
                || phaseCode.longValue() != expectedPhaseCode)
            throw new IllegalArgumentException(
                    "Snapshot index no longer matches the diagnostic for " + gameId
                            + "; found Phase=" + failing.get("Phase"));

        System.out.println("===== DIPLOBN/" + gameId + " =====");
        System.out.println(
                "Raw decoded source fields below; Java collection notation, not JSON.");
        System.out.println();

        int first = Math.max(0, failingIndex - 1);
        int last = failingIndex + 1;

        for (int index = first; index <= last; index++) {

            Map<?, ?> phase = object(phases.get(index), "snapshot " + index);

            String role = index == failingIndex
                    ? "FAILING SOURCE SNAPSHOT"
                    : index < failingIndex
                    ? "PRECEDING SNAPSHOT"
                    : "FOLLOWING SNAPSHOT";

            System.out.println("--- index " + index + ": " + role + " ---");

            printField(phase, "Phase");
            printField(phase, "Status");
            printField(phase, "Units");
            printField(phase, "SupplyCenters");
            printField(phase, "DislodgedUnits");

            // Include every nation's instructions: retreats are simultaneous.
            printField(phase, "Orders");
            printField(phase, "RetreatOrders");

            System.out.println();

        }

        System.out.println("===== END DIPLOBN/" + gameId + " =====");
        System.out.println();

    }

    private static void printField(Map<?, ?> object, String field) {

        if (!object.containsKey(field)) {
            System.out.println(field + ": <ABSENT>");
            return;
        }

        Object value = object.get(field);

        if (value instanceof Map<?, ?> map) {

            System.out.println(field + ":");

            if (map.isEmpty())
                System.out.println("  <EMPTY OBJECT>");

            for (Map.Entry<?, ?> entry : map.entrySet())
                System.out.println("  " + entry.getKey() + ": " + entry.getValue());

        } else {
            System.out.println(field + ": " + value);
        }

    }

    private static Map<?, ?> object(Object value, String label) {

        if (value instanceof Map<?, ?> map)
            return map;

        throw new IllegalArgumentException(label + " must be an object");

    }

    private static List<?> array(Object value, String label) {

        if (value instanceof List<?> list)
            return list;

        throw new IllegalArgumentException(label + " must be an array");

    }

}