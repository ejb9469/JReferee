package testing;

import _app.OpeningCatalogApp;
import analysis.openings.Classifier;
import domain.Nation;
import game.GameMoment;
import game.GamePhase;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Map;


public class OpeningCatalogTestCase implements TestCase {

    public static final String NAME = "Opening catalog";

    private static final String ENGLAND_ORDERS = """
            "England": [
                ["Lon", ["m", "NTH"]],
                ["Edi", ["m", "NWG"]],
                ["Lvp", ["m", "Yor"]]
            ]
            """;

    private static final String INCOMPLETE_ORDERS = """
            "England": [
                ["Lon", ["m", "NTH"]],
                ["Edi", ["m", "NWG"]]
            ]
            """;

    private TestEvaluation evaluation;


    @Override
    public void eval(boolean print) {

        TestEvaluation.Builder checks = new TestEvaluation.Builder();

        checkExtraction(checks);
        checkReport(checks);

        this.evaluation = checks.build();

        if (print)
            printEval();

    }


    // Extraction \\

    private static void checkExtraction(TestEvaluation.Builder checks) {

        DiploBNParser parser = new DiploBNParser();

        String spring = phase(19011, "AwaitingOrders", ENGLAND_ORDERS);
        String retreat = phase(19011, "AwaitingRetreats", "");
        String fall = phase(19012, "AwaitingOrders", "");

        DiploBNGame game = parser.parse(game(spring));
        DiploBNPhase opening = game.phases().getFirst();

        Classifier classifier = new Classifier(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                opening.board(),
                opening.movementOrders()
        );

        Map<Nation, String> expected = Map.of(
                Nation.ENGLAND, classifier.signature(Nation.ENGLAND));

        checks.expect(
                "Complete England opening extracted",
                expected,
                OpeningCatalogApp.openings(game)
        );

        checks.expect(
                "Selection uses phase identity, not list position",
                expected,
                OpeningCatalogApp.openings(parser.parse(game(fall, retreat, spring)))
        );

        checks.expect(
                "Incomplete orders do not become holds",
                Map.of(),
                OpeningCatalogApp.openings(parser.parse(game(
                        phase(19011, "AwaitingOrders", INCOMPLETE_ORDERS))))
        );

        checks.expect(
                "Empty submissions produce no complete openings",
                Map.of(),
                OpeningCatalogApp.openings(parser.parse(game(
                        phase(19011, "AwaitingOrders", ""))))
        );

        checks.expect(
                "Retreat snapshot is not an opening",
                true,
                rejects(() -> OpeningCatalogApp.openings(parser.parse(game(retreat))))
        );

        checks.expect(
                "Missing Spring movement rejected",
                true,
                rejects(() -> OpeningCatalogApp.openings(parser.parse(game(fall))))
        );

        checks.expect(
                "Duplicate Spring movement snapshots rejected",
                true,
                rejects(() -> OpeningCatalogApp.openings(parser.parse(game(spring, spring))))
        );

        // Same timestamp, wrong board: the classifier must still reject it.
        String nonstandard = spring.replace(
                "[\"F\", \"Lon\"]", "[\"F\", \"NTH\"]");

        checks.expect(
                "Nonstandard Spring position rejected",
                true,
                rejects(() -> OpeningCatalogApp.openings(parser.parse(game(nonstandard))))
        );

    }


    // Report \\

    private static void checkReport(TestEvaluation.Builder checks) {

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("SQLite JDBC driver is required for this test", exception);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            // Only the columns read by the report are needed here.
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

            String spring = phase(19011, "AwaitingOrders", ENGLAND_ORDERS);
            String complete = game(spring);

            insert(connection, "one", "DIPLOBN", complete);
            insert(connection, "two", "DIPLOBN", complete);
            insert(connection, "partial", "DIPLOBN",
                    game(phase(19011, "AwaitingOrders", INCOMPLETE_ORDERS)));
            insert(connection, "ambiguous", "DIPLOBN", game(spring, spring));
            insert(connection, "unsupported", "OTHER", "{}");
            insert(connection, "missing", "DIPLOBN",
                    game(phase(19012, "AwaitingOrders", "")));
            insert(connection, "malformed", "DIPLOBN", "{");

            String report = report(connection);

            checks.expect("Scanned game count", true,
                    report.contains("Games scanned: 7"));
            checks.expect("Represented game count", true,
                    report.contains("Games represented: 2"));
            checks.expect("Skipped game count", true,
                    report.contains("Games skipped: 5"));
            checks.expect("National opening count", true,
                    report.contains("Complete national openings: 2"));
            checks.expect("Identical openings grouped", true,
                    report.contains("ENGLAND: 2 complete opening(s), 1 distinct"));
            checks.expect("First source game retained", true,
                    report.contains("DIPLOBN/one [one]"));
            checks.expect("Second source game retained", true,
                    report.contains("DIPLOBN/two [two]"));

            checks.expect(
                    "Repeated reports are identical",
                    report,
                    report(connection)
            );

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM catalog_game")) {

                rows.next();
                checks.expect("Catalog rows unchanged", 7, rows.getInt(1));

            }

        } catch (SQLException exception) {
            throw new IllegalStateException("Opening catalog test failed", exception);
        }

    }

    private static void insert(Connection connection, String id, String source,
                               String payload) throws SQLException {

        String sql = """
                INSERT INTO catalog_game (
                    catalog_id, source_type, external_key, source_payload
                )
                VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, id);
            statement.setString(2, source);
            statement.setString(3, id);
            statement.setString(4, payload);
            statement.executeUpdate();

        }

    }

    private static String report(Connection connection) throws SQLException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            OpeningCatalogApp.printReport(connection, output);
        }

        return buffer.toString(StandardCharsets.UTF_8);

    }


    // Fixtures \\

    private static String game(String... phases) {
        return "{\"GamePhases\":[" + String.join(",", phases) + "]}";
    }

    private static String phase(int phase, String status, String orders) {
        return """
                {
                    "Phase": %d,
                    "Status": "%s",
                    "SupplyCenters": {
                        "England": ["Edi", "Lon", "Lvp"],
                        "France": ["Bre", "Mar", "Par"],
                        "Germany": ["Ber", "Kie", "Mun"],
                        "Italy": ["Nap", "Rom", "Ven"],
                        "Austria": ["Bud", "Tri", "Vie"],
                        "Russia": ["Mos", "Sev", "Stp", "War"],
                        "Turkey": ["Ank", "Con", "Smy"]
                    },
                    "Units": {
                        "England": [
                            ["F", "Edi"], ["F", "Lon"], ["A", "Lvp"]
                        ],
                        "France": [
                            ["F", "Bre"], ["A", "Mar"], ["A", "Par"]
                        ],
                        "Germany": [
                            ["A", "Ber"], ["F", "Kie"], ["A", "Mun"]
                        ],
                        "Italy": [
                            ["F", "Nap"], ["A", "Rom"], ["A", "Ven"]
                        ],
                        "Austria": [
                            ["A", "Bud"], ["F", "Tri"], ["A", "Vie"]
                        ],
                        "Russia": [
                            ["A", "Mos"], ["F", "Sev"],
                            ["F", ["Stp", "sc"]], ["A", "War"]
                        ],
                        "Turkey": [
                            ["F", "Ank"], ["A", "Con"], ["A", "Smy"]
                        ]
                    },
                    "Orders": {%s}
                }
                """.formatted(phase, status, orders);
    }

    private static boolean rejects(Runnable action) {

        try {
            action.run();
        } catch (IllegalArgumentException exception) {
            return true;
        }

        return false;

    }


    // TestCase \\

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getEval() {
        return evaluation == null ? null : evaluation.format(NAME);
    }

    @Override
    public int getScore() {
        return evaluation == null ? 0 : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null ? 0 : evaluation.size();
    }

    @Override
    public void printEval() {
        System.out.println(evaluation == null ? "UNEVALUATED " + NAME : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), NAME);
    }

    public static void main(String[] args) {

        OpeningCatalogTestCase test = new OpeningCatalogTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Opening catalog tests failed");

    }

}