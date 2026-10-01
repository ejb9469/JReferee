package analysis.openings;

import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import io.catalog.GameSource;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;
import phase.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;


public class Catalog {

    private final List<Entry> entries;
    private final List<String> diagnostics;
    private final int scanned;
    private final int represented;


    private Catalog(List<Entry> entries, List<String> diagnostics,
                    int scanned, int represented) {

        this.entries = List.copyOf(entries);
        this.diagnostics = List.copyOf(diagnostics);
        this.scanned = scanned;
        this.represented = represented;

    }


    // Loading \\

    public static Catalog load(Path databasePath)
            throws SQLException, ClassNotFoundException {

        Path path = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();

        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Database not found: " + path);

        Class.forName("org.sqlite.JDBC");

        String url = "jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url)) {
            return load(connection);
        }

    }

    public static Catalog load(Connection connection) throws SQLException {

        Objects.requireNonNull(connection, "connection");

        Map<Nation, Map<String, Integer>> counts = new EnumMap<>(Nation.class);
        Map<String, DiploBNPhase> representatives = new HashMap<>();
        List<String> diagnostics = new ArrayList<>();
        DiploBNParser parser = new DiploBNParser();

        int scanned = 0;
        int represented = 0;

        String sql = """
                SELECT source_type, external_key, source_payload
                FROM catalog_game
                ORDER BY catalog_id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {

                scanned++;

                String source = rows.getString("source_type");
                String reference = source + "/" + rows.getString("external_key");

                if (!GameSource.DIPLOBN.name().equals(source)) {
                    diagnostics.add("SKIP " + reference + ": unsupported source");
                    continue;
                }

                DiploBNPhase opening;
                Map<Nation, String> extracted;

                try {
                    opening = openingPhase(parser.parse(rows.getString("source_payload")));
                    extracted = openings(opening);
                } catch (IllegalArgumentException exception) {
                    diagnostics.add("SKIP " + reference + ": " + exception.getMessage());
                    continue;
                }

                if (extracted.isEmpty()) {
                    diagnostics.add("SKIP " + reference + ": no complete national openings");
                    continue;
                }

                Set<Nation> missing = EnumSet.allOf(Nation.class);
                missing.removeAll(extracted.keySet());

                if (!missing.isEmpty())
                    diagnostics.add("INCOMPLETE " + reference + ": " + missing);

                represented++;

                for (Map.Entry<Nation, String> entry : extracted.entrySet()) {

                    counts.computeIfAbsent(entry.getKey(), ignored -> new TreeMap<>())
                            .merge(entry.getValue(), 1, Integer::sum);

                    representatives.putIfAbsent(entry.getValue(), opening);

                }

            }

        }

        List<Entry> entries = new ArrayList<>();

        for (Nation nation : Nation.values()) {

            Map<String, Integer> nationalCounts = counts.getOrDefault(nation, Map.of());
            List<Entry> nationalEntries = new ArrayList<>();

            for (Map.Entry<String, Integer> count : nationalCounts.entrySet()) {

                DiploBNPhase opening = representatives.get(count.getKey());
                List<Order> orders = new ArrayList<>();

                for (Order order : opening.movementOrders())
                    if (order.owner() == nation)
                        orders.add(order);

                nationalEntries.add(new Entry(
                        nation, count.getKey(), count.getValue(), opening.board(), orders));

            }

            nationalEntries.sort(
                    Comparator.comparingInt(Entry::count).reversed()
                            .thenComparing(Entry::signature));

            entries.addAll(nationalEntries);

        }

        return new Catalog(entries, diagnostics, scanned, represented);

    }


    // Opening selection \\

    public static Map<Nation, String> openings(DiploBNGame game) {
        return openings(openingPhase(game));
    }

    private static DiploBNPhase openingPhase(DiploBNGame game) {

        Objects.requireNonNull(game, "game");

        DiploBNPhase opening = null;

        for (DiploBNPhase phase : game.phases()) {

            if (phase.sourcePhase() != 19011
                    || phase.gamePhase() != GamePhase.SPRING_MOVEMENT)
                continue;

            if (opening != null)
                throw new IllegalArgumentException("Multiple Spring 1901 movement snapshots");

            opening = phase;

        }

        if (opening == null)
            throw new IllegalArgumentException("Missing Spring 1901 movement snapshot");

        return opening;

    }

    private static Map<Nation, String> openings(DiploBNPhase opening) {

        Classifier classifier = new Classifier(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                opening.board(),
                opening.movementOrders());

        Map<Nation, String> result = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values())
            if (classifier.isComplete(nation))
                result.put(nation, classifier.signature(nation));

        return Collections.unmodifiableMap(result);

    }


    public List<Entry> entries() {
        return entries;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public int scanned() {
        return scanned;
    }

    public int represented() {
        return represented;
    }

    public int skipped() {
        return scanned - represented;
    }

    public int nationalOpenings() {
        return entries.stream().mapToInt(Entry::count).sum();
    }


    public record Entry(Nation nation, String signature, int count,
                        BoardState board, List<Order> orders) {

        public Entry {

            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(board, "board");
            orders = List.copyOf(orders);

            if (count < 1)
                throw new IllegalArgumentException("Opening count must be positive");

        }

        public List<adjudication.Order> displayOrders() {

            List<adjudication.Order> result = new ArrayList<>();

            for (Order order : orders) {

                Province location = board.locationOf(order.unit());

                if (location == null)
                    throw new IllegalArgumentException(
                            "Opening order issuer is absent from the board: " + order.unit());

                result.add(new adjudication.Order(order, location));

            }

            Collections.sort(result);

            // No provider conversion, and no shared mutable work orders.
            return List.copyOf(result);

        }

    }

}