package _app;

import adjudication.Order;
import analysis.openings.Classifier;
import domain.Nation;
import game.GameMoment;
import game.GamePhase;
import io.catalog.GameSource;
import parsing.diplobn.DiploBNAdjudicationOrderTranslator;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNOrder;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static domain.Constants.*;


public class OpeningCatalogApp {

    private static final Path DEFAULT_DATABASE_PATH =
            Path.of("data", "diplobn-catalog.sqlite");


    private OpeningCatalogApp() {     }


    public static void main(String[] args) throws SQLException, ClassNotFoundException {

        Path databasePath = databasePath(args).toAbsolutePath().normalize();

        if (!Files.isRegularFile(databasePath))
            throw new IllegalArgumentException("Database not found: " + databasePath);

        Class.forName("org.sqlite.JDBC");

        // URI encoding also handles spaces and other special path characters.
        String url = "jdbc:sqlite:" + databasePath.toUri().toASCIIString() + "?mode=ro";
        boolean color = System.getenv("NO_COLOR") == null
                && !"dumb".equalsIgnoreCase(System.getenv("TERM"));

        try (Connection connection = DriverManager.getConnection(url)) {

            System.out.println();
            System.out.println(styled(
                    "OPENING CATALOG",
                    ANSI_BOLD + ANSI_CYAN, color));
            System.out.println(styled(
                    "Opening catalog: " + databasePath,
                    ANSI_DIM, color));
            System.out.println();

            printReport(connection, System.out, color);

        }

    }

    private static Path databasePath(String[] args) {

        if (args.length == 0)
            return DEFAULT_DATABASE_PATH;

        if (args.length == 2 && args[0].equals("--database") && !args[1].isBlank())
            return Path.of(args[1]);

        throw new IllegalArgumentException(
                "Usage: OpeningCatalogApp [--database <path>]");

    }


    // Opening extraction \\

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

            // Do not guess which snapshot is authoritative.
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
                opening.movementOrders()
        );

        Map<Nation, String> result = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values())
            if (classifier.isComplete(nation))
                result.put(nation, classifier.signature(nation));

        return Collections.unmodifiableMap(result);

    }

    private static List<String> formattedOrders(DiploBNPhase opening, Nation nation) {

        DiploBNAdjudicationOrderTranslator translator =
                new DiploBNAdjudicationOrderTranslator();

        List<Order> orders = new ArrayList<>();

        for (DiploBNOrder source : opening.sourceMovementOrders())
            if (source.owner() == nation)
                orders.add(translator.translate(source, opening.board()));

        // Use the adjudication package's sorting and notation.
        Collections.sort(orders);

        List<String> lines = new ArrayList<>();

        for (Order order : orders)
            lines.add(order.toString());

        return List.copyOf(lines);

    }


    // Catalog report \\

    public static void printReport(Connection connection, PrintStream output) throws SQLException {
        // Keep captured reports and existing tests free of escape sequences.
        printReport(connection, output, false);
    }

    public static void printReport(Connection connection, PrintStream output,
                                   boolean color) throws SQLException {

        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(output, "output");

        Map<Nation, Map<String, List<String>>> occurrences = new EnumMap<>(Nation.class);
        Map<String, List<String>> orderLines = new HashMap<>();
        DiploBNParser parser = new DiploBNParser();

        int scanned = 0;
        int represented = 0;
        int skipped = 0;
        int nationalOpenings = 0;

        String sql = """
                SELECT catalog_id, source_type, external_key, source_payload
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
                    printSkip(output, reference, "unsupported source", color);
                    skipped++;
                    continue;
                }

                DiploBNPhase opening;
                Map<Nation, String> extracted;

                try {
                    DiploBNGame game = parser.parse(rows.getString("source_payload"));
                    opening = openingPhase(game);
                    extracted = openings(opening);
                } catch (IllegalArgumentException exception) {
                    printSkip(output, reference, exception.getMessage(), color);
                    skipped++;
                    continue;
                }

                if (extracted.isEmpty()) {
                    printSkip(output, reference, "no complete national openings", color);
                    skipped++;
                    continue;
                }

                Set<Nation> missing = EnumSet.allOf(Nation.class);
                missing.removeAll(extracted.keySet());

                if (!missing.isEmpty()) {
                    output.println(
                            styled("INCOMPLETE", ANSI_BOLD + ANSI_YELLOW, color)
                                    + " " + styled(reference, ANSI_DIM, color)
                                    + ": " + nationNames(missing, color));
                }

                represented++;
                nationalOpenings += extracted.size();

                for (Map.Entry<Nation, String> entry : extracted.entrySet()) {

                    Nation nation = entry.getKey();
                    String signature = entry.getValue();

                    Map<String, List<String>> bySignature = occurrences.computeIfAbsent(
                            nation, ignored -> new TreeMap<>());

                    bySignature.computeIfAbsent(
                            signature, ignored -> new ArrayList<>()).add(reference);

                    // One representative order listing per exact opening.
                    if (!orderLines.containsKey(signature))
                        orderLines.put(signature, formattedOrders(opening, nation));

                }

            }

        }

        output.println();
        output.println(styled("Games scanned: " + scanned, ANSI_CYAN, color));
        output.println(styled("Games represented: " + represented, ANSI_GREEN, color));
        output.println(styled(
                "Games skipped: " + skipped,
                skipped == 0 ? ANSI_DIM : ANSI_ORANGE, color));
        output.println(styled(
                "Complete national openings: " + nationalOpenings,
                ANSI_BOLD + ANSI_CYAN, color));

        for (Nation nation : Nation.values()) {

            Map<String, List<String>> bySignature = occurrences.getOrDefault(nation, Map.of());

            int total = 0;

            for (List<String> games : bySignature.values())
                total += games.size();

            output.printf("%n%s%s%n",
                    nationName(nation, color),
                    styled(
                            ": " + total + " complete opening(s), "
                                    + bySignature.size() + " distinct",
                            ANSI_BOLD, color));

            List<Map.Entry<String, List<String>>> ranked =
                    new ArrayList<>(bySignature.entrySet());

            ranked.sort((first, second) -> {

                int countComparison = Integer.compare(
                        second.getValue().size(), first.getValue().size());

                if (countComparison != 0)
                    return countComparison;

                return first.getKey().compareTo(second.getKey());

            });

            for (Map.Entry<String, List<String>> entry : ranked) {

                output.println("  " + styled(
                        entry.getValue().size() + " game(s)",
                        ANSI_BOLD + ANSI_GREEN, color));

                for (String order : orderLines.get(entry.getKey()))
                    output.println("    " + nationText(order, nation, color));

                output.println();

            }

        }

    }


    // Console styling \\

    private static void printSkip(PrintStream output, String reference,
                                  String reason, boolean color) {

        output.println(
                styled("SKIP", ANSI_BOLD + ANSI_RED, color)
                        + " " + styled(reference, ANSI_DIM, color)
                        + ": " + styled(reason, ANSI_ORANGE, color));

    }

    private static String nationName(Nation nation, boolean color) {
        return nationText(nation.name(), nation, color);
    }

    private static String nationText(String text, Nation nation, boolean color) {

        String background = nation == Nation.GERMANY ? ANSI_BG_LIGHT : "";

        return styled(text,
                background + ANSI_BOLD + nationColor(nation), color);

    }

    private static String nationNames(Collection<Nation> nations, boolean color) {

        StringJoiner names = new StringJoiner(", ", "[", "]");

        for (Nation nation : nations)
            names.add(nationName(nation, color));

        return names.toString();

    }

    private static String styled(String text, String style, boolean color) {
        return color ? style + text + ANSI_RESET : text;
    }

}