package _app;

import adjudication.Order;
import analysis.openings.Catalog;
import analysis.openings.Opening;
import domain.Nation;
import parsing.diplobn.DiploBNGame;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static domain.Constants.*;


public class OpeningCatalogApp {

    private static final Path DEFAULT_DATABASE_PATH =
            Path.of("data", "diplobn-catalog.sqlite");

    private static final short APPEARANCE_LIMIT = 1;


    private OpeningCatalogApp() {     }


    public static void main(String[] args) throws SQLException, ClassNotFoundException {

        Path databasePath = databasePath(args).toAbsolutePath().normalize();
        Catalog catalog = Catalog.load(databasePath);

        boolean color = System.getenv("NO_COLOR") == null
                && !"dumb".equalsIgnoreCase(System.getenv("TERM"));

        System.out.println();
        System.out.println(styled(
                "OPENING CATALOG",
                ANSI_BOLD + ANSI_CYAN, color));
        System.out.println(styled(
                "Opening catalog: " + databasePath,
                ANSI_DIM, color));
        System.out.println();

        printReport(catalog, System.out, color);

    }

    private static Path databasePath(String[] args) {

        if (args.length == 0)
            return DEFAULT_DATABASE_PATH;

        if (args.length == 2 && args[0].equals("--database") && !args[1].isBlank())
            return Path.of(args[1]);

        throw new IllegalArgumentException(
                "Usage: OpeningCatalogApp [--database <path>]");

    }


    // Existing entry points delegate to the shared catalog. \\

    public static Map<Nation, String> openings(DiploBNGame game) {
        return Catalog.openings(game);
    }

    public static void printReport(Connection connection, PrintStream output) throws SQLException {
        printReport(connection, output, false);
    }

    public static void printReport(Connection connection, PrintStream output,
                                   boolean color) throws SQLException {

        Objects.requireNonNull(output, "output");
        printReport(Catalog.load(connection), output, color);

    }

    public static void printReport(Catalog catalog, PrintStream output, boolean color) {

        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(output, "output");

        for (String diagnostic : catalog.diagnostics())
            output.println(styled(diagnostic,
                    diagnostic.startsWith("SKIP ") ? ANSI_ORANGE : ANSI_YELLOW, color));

        List<Opening> displayed = catalog.distinct(APPEARANCE_LIMIT);
        int displayedAppearances = appearances(catalog, displayed);

        output.println();
        output.println(styled("Games scanned: " + catalog.scanned(), ANSI_CYAN, color));
        output.println(styled("Games represented: " + catalog.represented(), ANSI_GREEN, color));
        output.println(styled(
                "Games skipped: " + catalog.skipped(),
                catalog.skipped() == 0 ? ANSI_DIM : ANSI_ORANGE, color));
        output.println(styled(
                "Complete national openings: " + catalog.nationalOpenings(),
                ANSI_BOLD + ANSI_CYAN, color));

        output.println();
        output.println(styled(
                "Showing openings seen more than " + APPEARANCE_LIMIT + " time(s).",
                ANSI_BOLD, color));
        output.println(styled(
                "Displayed: " + displayed.size() + " distinct opening(s), "
                        + displayedAppearances + " national appearance(s)",
                ANSI_GREEN, color));
        output.println(styled(
                "Hidden: " + (catalog.entries().size() - displayed.size())
                        + " distinct opening(s)",
                ANSI_DIM, color));

        for (Nation nation : Nation.values()) {

            List<Opening> all = catalog.openings(nation);
            List<Opening> selected = catalog.distinct(all, APPEARANCE_LIMIT);

            int total = appearances(catalog, all);
            int shown = appearances(catalog, selected);

            output.printf("%n%s%s%n",
                    nationText(nation.name(), nation, color),
                    styled(
                            ": " + total + " complete opening(s), "
                                    + all.size() + " distinct",
                            ANSI_BOLD, color));

            output.println(styled(
                    "  Showing " + selected.size() + " distinct opening(s), "
                            + shown + " appearance(s)",
                    ANSI_DIM, color));

            if (selected.isEmpty()) {
                output.println(styled("  No openings exceed the appearance limit.", ANSI_DIM, color));
                continue;
            }

            // Catalog order is already descending frequency within each nation.
            for (Opening opening : selected) {

                output.println("  " + styled(
                        catalog.count(opening) + " game(s)",
                        ANSI_BOLD + ANSI_GREEN, color));

                for (Order order : opening.displayOrders())
                    output.println("    " + nationText(order.toString(), nation, color));

                output.println();

            }

        }

    }

    private static int appearances(Catalog catalog, Collection<? extends Opening> openings) {

        int total = 0;

        for (Opening opening : openings)
            total = Math.addExact(total, catalog.count(opening));

        return total;

    }


    // Console styling \\

    private static String nationText(String text, Nation nation, boolean color) {

        String background = nation == Nation.GERMANY ? ANSI_BG_LIGHT : "";

        return styled(text,
                background + ANSI_BOLD + nationColor(nation), color);

    }

    private static String styled(String text, String style, boolean color) {
        return color ? style + text + ANSI_RESET : text;
    }

}