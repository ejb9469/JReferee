package analysis.openings;

import domain.Nation;
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

    private static final GameMoment OPENING_MOMENT =
            new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

    private final List<Opening> entries;
    private final List<OpeningCombination> combinations;
    private final Map<String, Opening> byId;
    private final Map<String, Integer> counts;
    private final List<String> diagnostics;

    private final int scanned;
    private final int represented;


    private Catalog(Map<String, Opening> openings, Map<String, Integer> counts,
                    List<String> diagnostics, int scanned, int represented) {

        this.byId = Map.copyOf(openings);
        this.counts = Map.copyOf(counts);
        this.diagnostics = List.copyOf(diagnostics);
        this.scanned = scanned;
        this.represented = represented;

        List<Opening> national = new ArrayList<>();
        List<OpeningCombination> combined = new ArrayList<>();

        for (Opening opening : openings.values()) {
            if (opening instanceof OpeningCombination combination)
                combined.add(combination);
            else
                national.add(opening);
        }

        Comparator<Opening> frequency = Comparator
                .comparingInt((Opening opening) -> this.counts.get(opening.id()))
                .reversed()
                .thenComparing(Opening::signature);

        national.sort(
                Comparator.comparing(Opening::nation)
                        .thenComparing(frequency));

        combined.sort(frequency);

        this.entries = List.copyOf(national);
        this.combinations = List.copyOf(combined);

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

        Map<String, Opening> openings = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
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

                DiploBNPhase phase;
                Map<Nation, Opening> national;
                OpeningCombination combination;

                try {

                    phase = openingPhase(parser.parse(rows.getString("source_payload")));
                    national = extract(phase);

                    combination = national.size() == Nation.values().length
                            ? new OpeningCombination(
                            OPENING_MOMENT, phase.board(), phase.movementOrders())
                            : null;

                } catch (IllegalArgumentException exception) {
                    diagnostics.add("SKIP " + reference + ": " + exception.getMessage());
                    continue;
                }

                if (national.isEmpty()) {
                    diagnostics.add("SKIP " + reference + ": no complete national openings");
                    continue;
                }

                Set<Nation> missing = EnumSet.allOf(Nation.class);
                missing.removeAll(national.keySet());

                if (!missing.isEmpty())
                    diagnostics.add("INCOMPLETE " + reference + ": " + missing);

                represented++;

                for (Opening opening : national.values())
                    record(openings, counts, opening);

                // One combination occurrence per complete game, not per nation.
                if (combination != null)
                    record(openings, counts, combination);

            }

        }

        return new Catalog(openings, counts, diagnostics, scanned, represented);

    }

    private static void record(Map<String, Opening> openings,
                               Map<String, Integer> counts, Opening candidate) {

        Opening existing = openings.putIfAbsent(candidate.id(), candidate);

        if (existing != null && !existing.equals(candidate))
            throw new IllegalStateException(
                    "Opening identifier collision: " + candidate.id());

        counts.merge(candidate.id(), 1, Math::addExact);

    }


    // Extraction \\

    public static Map<Nation, Opening> extract(DiploBNGame game) {
        return extract(openingPhase(game));
    }

    private static Map<Nation, Opening> extract(DiploBNPhase phase) {

        Classifier classifier = new Classifier(
                OPENING_MOMENT, phase.board(), phase.movementOrders());

        Map<Nation, Opening> result = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values()) {

            if (!classifier.isComplete(nation))
                continue;

            List<Order> orders = new ArrayList<>();

            for (Order order : phase.movementOrders())
                if (order.owner() == nation)
                    orders.add(order);

            result.put(nation, new Opening(
                    nation, OPENING_MOMENT, phase.board(), orders));

        }

        return Collections.unmodifiableMap(result);

    }

    public static Optional<OpeningCombination> combination(DiploBNGame game) {

        DiploBNPhase phase = openingPhase(game);

        Classifier classifier = new Classifier(
                OPENING_MOMENT, phase.board(), phase.movementOrders());

        if (!classifier.isComplete())
            return Optional.empty();

        return Optional.of(new OpeningCombination(
                OPENING_MOMENT, phase.board(), phase.movementOrders()));

    }

    public static Map<Nation, String> openings(DiploBNGame game) {

        // Preserve the signature-returning API.
        Map<Nation, String> result = new EnumMap<>(Nation.class);

        for (Map.Entry<Nation, Opening> entry : extract(game).entrySet())
            result.put(entry.getKey(), entry.getValue().signature());

        return Collections.unmodifiableMap(result);

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


    // Queries \\

    public List<Opening> entries() {
        return entries;
    }

    public List<Opening> openings(Nation nation) {

        Objects.requireNonNull(nation, "nation");

        return entries.stream()
                .filter(opening -> opening.nation() == nation)
                .toList();

    }

    public List<OpeningCombination> combinations() {
        return combinations;
    }

    public List<OpeningCombination> combinationsContaining(String openingId) {

        Objects.requireNonNull(openingId, "openingId");

        return combinations.stream()
                .filter(combination -> combination.openingIds().containsValue(openingId))
                .toList();

    }

    public Optional<Opening> find(String id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public int count(String id) {
        return counts.getOrDefault(Objects.requireNonNull(id, "id"), 0);
    }

    public int count(Opening opening) {

        Objects.requireNonNull(opening, "opening");

        Opening existing = byId.get(opening.id());

        if (existing != null && !existing.equals(opening))
            throw new IllegalArgumentException(
                    "Opening identifier refers to different content: " + opening.id());

        return count(opening.id());

    }


    // Frequency split: unique <= limit, distinct > limit. \\

    public List<Opening> unique(int limit) {
        return unique(entries, limit);
    }

    public List<Opening> distinct(int limit) {
        return distinct(entries, limit);
    }

    /**
     * Observed candidates with between one and limit appearances, inclusive.
     */
    public <T extends Opening> List<T> unique(Collection<T> candidates, int limit) {
        return selectFrequency(candidates, limit, false);
    }

    /**
     * Observed candidates with strictly more than limit appearances.
     */
    public <T extends Opening> List<T> distinct(Collection<T> candidates, int limit) {
        return selectFrequency(candidates, limit, true);
    }

    private <T extends Opening> List<T> selectFrequency(
            Collection<T> candidates, int limit, boolean above) {

        Objects.requireNonNull(candidates, "candidates");

        if (limit < 1)
            throw new IllegalArgumentException("Appearance limit must be at least 1");

        Set<T> selected = new LinkedHashSet<>();

        for (T candidate : candidates) {

            int appearances = count(candidate);

            // An unknown opening is not a rare observed opening.
            if (appearances == 0)
                continue;

            if (above ? appearances > limit : appearances <= limit)
                selected.add(candidate);

        }

        return List.copyOf(selected);

    }


    // Unfiltered totals \\

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

        int total = 0;

        for (Opening opening : entries)
            total = Math.addExact(total, count(opening));

        return total;

    }

    public int completeGames() {

        int total = 0;

        for (OpeningCombination combination : combinations)
            total = Math.addExact(total, count(combination));

        return total;

    }

}