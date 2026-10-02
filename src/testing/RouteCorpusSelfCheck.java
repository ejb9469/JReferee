package testing;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.StandardGameFactory;
import game.record.GameRecord;
import parsing.diplobn.DiploBNRecordImporter;
import phase.UnitId;

import java.sql.*;
import java.util.*;


public final class RouteCorpusSelfCheck {

    private RouteCorpusSelfCheck() {  }


    public static void main(String[] args) throws Exception {

        Class.forName("org.sqlite.JDBC");

        Map<CorpusPartition, Long> representatives = new EnumMap<>(CorpusPartition.class);

        for (long id = 1; id <= 10_000 && representatives.size() < 3; id++)
            representatives.putIfAbsent(CorpusPartition.forSourceId(id), id);

        require(representatives.size() == 3, "Could not find representatives for every partition");

        BoardState initial = StandardGameFactory.create1901().board();

        String validPayload = payload(
                phase(initial, 19011, true),
                phase(initial, 19012, false));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE catalog_game (
                            catalog_id TEXT PRIMARY KEY,
                            source_type TEXT NOT NULL,
                            external_key TEXT NOT NULL,
                            source_payload TEXT NOT NULL
                        )
                        """);
            }

            for (Map.Entry<CorpusPartition, Long> entry : representatives.entrySet())
                insert(connection, entry.getKey().name(), "DIPLOBN",
                        Long.toString(entry.getValue()), validPayload);

            insert(connection, "unsupported", "OTHER", "99991", "{}");
            insert(connection, "invalid-id", "DIPLOBN", "not-an-id", validPayload);
            insert(connection, "parse-error", "DIPLOBN", "99992", "{");
            insert(connection, "gap", "DIPLOBN", "99993", payload(
                    phase(initial, 19011, true),
                    phase(initial, 19013, false)));

            RouteCorpus corpus = RouteCorpus.load(connection);

            require(corpus.scanned() == 7, "Wrong scanned count");
            require(corpus.acceptedCount() == 3, "Expected three accepted games");
            require(corpus.rejectedCount() == 4, "Expected four rejected rows");
            require(corpus.diagnostics().size() == 4, "Missing rejection details");

            require(corpus.rejectionCounts().getOrDefault("UNSUPPORTED_SOURCE", 0L) == 1,
                    "Unsupported source was not reported");
            require(corpus.rejectionCounts().getOrDefault("INVALID_SOURCE_ID", 0L) == 1,
                    "Invalid ID was not reported");
            require(corpus.rejectionCounts().getOrDefault("PARSE_REJECTED", 0L) == 1,
                    "Parser rejection was not reported");
            require(corpus.rejectionCounts().getOrDefault("REPLAY_REJECTED", 0L) == 1,
                    "Replay rejection was not reported");

            Set<UUID> archiveIds = new HashSet<>();

            for (CorpusPartition partition : CorpusPartition.values()) {

                long sourceId = representatives.get(partition);
                List<GameRecord> games = corpus.games(partition);

                require(games.size() == 1, "Expected one accepted game in " + partition);
                require(corpus.sourceIds(partition).equals(List.of(sourceId)),
                        "Source identity is in the wrong partition");

                GameRecord record = games.getFirst();

                require(record.id().equals(DiploBNRecordImporter.archiveId(sourceId)),
                        "Archive ID does not match source identity");
                require(record.resolvedPhases().size() == 1,
                        "Final boundary orders were unexpectedly replayed");
                require(archiveIds.add(record.id()), "Partitions overlap");

            }

            require(!connection.isClosed(), "Loader closed the caller's connection");

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM catalog_game")) {

                rows.next();
                require(rows.getLong(1) == 7, "Loader modified the database");

            }

            RouteCorpus repeated = RouteCorpus.load(connection);

            require(corpus.accepted().keySet().equals(repeated.accepted().keySet()),
                    "Repeated loading changed accepted source IDs");
            require(corpus.diagnostics().equals(repeated.diagnostics()),
                    "Repeated loading changed diagnostics");

            for (CorpusPartition partition : CorpusPartition.values())
                require(corpus.sourceIds(partition).equals(repeated.sourceIds(partition)),
                        "Repeated loading changed partition membership");

            long duplicateId = representatives.get(CorpusPartition.TRAINING);

            insert(connection, "duplicate", "DIPLOBN",
                    Long.toString(duplicateId), validPayload);

            boolean duplicateRejected = false;

            try {
                RouteCorpus.load(connection);
            } catch (IllegalStateException expected) {
                duplicateRejected = expected.getMessage().contains("Duplicate DiploBN");
            }

            require(duplicateRejected, "Duplicate source identity was silently accepted");

            corpus.printReport(System.out);

        }

        boolean invalidIdRejected = false;

        try {
            CorpusPartition.forSourceId(0);
        } catch (IllegalArgumentException expected) {
            invalidIdRejected = true;
        }

        require(invalidIdRejected, "Invalid partition identity was accepted");

        System.out.println();
        System.out.println("RouteCorpus checks passed.");

    }


    // Test-only JSON construction using known enum values. \\

    private static String payload(String... phases) {
        return "{\"GamePhases\":[" + String.join(",", phases) + "]}";
    }

    private static String phase(BoardState board, int code, boolean submittedHolds) {

        StringBuilder out = new StringBuilder();

        out.append("{\"Phase\":").append(code);
        out.append(",\"Status\":\"AwaitingOrders\"");
        out.append(",\"SupplyCenters\":{");

        boolean firstNation = true;

        for (Nation nation : Nation.values()) {

            if (!firstNation)
                out.append(',');

            out.append('"').append(sourceName(nation)).append("\":[");

            boolean firstCenter = true;

            for (var entry : board.owners().entrySet()) {

                if (entry.getValue() != nation)
                    continue;

                if (!firstCenter)
                    out.append(',');

                out.append('"').append(entry.getKey().name()).append('"');
                firstCenter = false;

            }

            out.append(']');
            firstNation = false;

        }

        out.append("},\"Units\":{");
        firstNation = true;

        for (Nation nation : Nation.values()) {

            if (!firstNation)
                out.append(',');

            out.append('"').append(sourceName(nation)).append("\":[");

            boolean firstUnit = true;

            for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

                if (entry.getKey().owner() != nation)
                    continue;

                if (!firstUnit)
                    out.append(',');

                out.append("[\"")
                        .append(entry.getKey().unitType() == UnitType.ARMY ? "A" : "F")
                        .append("\",");

                appendLocation(out, entry.getValue());
                out.append(']');
                firstUnit = false;

            }

            out.append(']');
            firstNation = false;

        }

        out.append("},\"Orders\":{");

        if (submittedHolds) {

            firstNation = true;

            for (Nation nation : Nation.values()) {

                if (!firstNation)
                    out.append(',');

                out.append('"').append(sourceName(nation)).append("\":[");

                boolean firstOrder = true;

                for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

                    if (entry.getKey().owner() != nation)
                        continue;

                    if (!firstOrder)
                        out.append(',');

                    out.append("[\"")
                            .append(Province.canonical(entry.getValue()).name())
                            .append("\",\"h\"]");

                    firstOrder = false;

                }

                out.append(']');
                firstNation = false;

            }

        }

        return out.append("}}").toString();

    }

    private static void appendLocation(StringBuilder out, Province province) {

        if (province.parent == null) {
            out.append('"').append(province.name()).append('"');
        } else {
            out.append("[\"").append(province.parent.name())
                    .append("\",\"").append(province.suffix).append("\"]");
        }

    }

    private static String sourceName(Nation nation) {

        return switch (nation) {
            case AUSTRIA -> "Austria";
            case ENGLAND -> "England";
            case FRANCE -> "France";
            case GERMANY -> "Germany";
            case ITALY -> "Italy";
            case RUSSIA -> "Russia";
            case TURKEY -> "Turkey";
        };

    }

    private static void insert(
            Connection connection, String catalogId,
            String source, String externalKey, String payload) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO catalog_game (
                    catalog_id, source_type, external_key, source_payload
                ) VALUES (?, ?, ?, ?)
                """)) {

            statement.setString(1, catalogId);
            statement.setString(2, source);
            statement.setString(3, externalKey);
            statement.setString(4, payload);
            statement.executeUpdate();

        }

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}