package _app;

import domain.Constants;
import io.catalog.CatalogAnalysis;
import io.catalog.CatalogGame;
import io.catalog.CatalogGameId;
import io.catalog.GameCatalog;
import io.catalog.diplobn.DiploBNCatalogImporter;
import io.catalog.diplobn.DiploBNGameCatalogFileImporter;
import io.persistence.SQLiteCatalogStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;


/**
 * Console entry point for importing DiploBN games listed in a catalog manifest.
 *
 * <p>Without command-line arguments, this application reads:</p>
 *
 * <pre>
 * src/resources/save_games/diplobn-games.json
 * </pre>
 *
 * <p>and stores imported games in:</p>
 *
 * <pre>
 * data/diplobn-catalog.sqlite
 * </pre>
 *
 * <p>Supply one command-line argument to use another manifest file:</p>
 *
 * <pre>
 * DiploBNGameCatalogApp path/to/diplobn-games.json
 * </pre>
 *
 * <p>Use explicit options to control either location:</p>
 *
 * <pre>
 * DiploBNGameCatalogApp
 *     --database data/diplobn-catalog.sqlite
 *     --manifest src/resources/save_games/diplobn-games.json
 * </pre>
 */
public final class DBNGameCatalogApp {


    private static final Path DEFAULT_DATABASE_PATH = Path.of(
            "data",
            "diplobn-catalog.sqlite");


    private DBNGameCatalogApp() {
    }


    public static void main(String[] args) {

        Arguments arguments;

        try {
            arguments = argumentsFrom(args);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return;
        }

        Constants.printTimestamp();

        try (SQLiteCatalogStore store =
                     new SQLiteCatalogStore(
                             arguments.databasePath());
             GameCatalog catalog = new GameCatalog(store))
        {
            DiploBNCatalogImporter gameImporter =
                    new DiploBNCatalogImporter(catalog);

            DiploBNGameCatalogFileImporter fileImporter =
                    new DiploBNGameCatalogFileImporter(
                            arguments.manifestPath(),
                            gameImporter);

            printHeader(
                    arguments.manifestPath(),
                    arguments.databasePath());

            DiploBNGameCatalogFileImporter.ImportReport report =
                    fileImporter.importGames();

            printReport(report, catalog);

            Constants.printTimestamp();

        } catch (IllegalStateException exception) {
            System.err.println();
            System.err.println(
                    "Catalog import failed: "
                            + exception.getMessage());
            System.err.println();
        }

    }


    private static Arguments argumentsFrom(
            String[] args
    ) {

        Path manifestPath =
                DiploBNGameCatalogFileImporter
                        .DEFAULT_MANIFEST_PATH;

        Path databasePath = DEFAULT_DATABASE_PATH;

        if (args.length == 0)
            return new Arguments(
                    manifestPath,
                    databasePath);

        if (args.length == 1
                && !args[0].startsWith("--"))
            return new Arguments(
                    Path.of(args[0]),
                    databasePath);

        for (int index = 0; index < args.length; index++) {

            String argument = args[index];

            if (argument.equals("--manifest")) {
                manifestPath = optionPath(
                        args,
                        ++index,
                        "--manifest");

                continue;
            }

            if (argument.equals("--database")) {
                databasePath = optionPath(
                        args,
                        ++index,
                        "--database");

                continue;
            }

            throw usage();
        }

        return new Arguments(
                manifestPath,
                databasePath);

    }

    private static Path optionPath(
            String[] args,
            int index,
            String option
    ) {

        if (index >= args.length)
            throw new IllegalArgumentException(
                    option + " requires a path");

        String value = args[index];

        if (value.isBlank()
                || value.startsWith("--"))
            throw new IllegalArgumentException(
                    option + " requires a path");

        return Path.of(value);

    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                """
                Usage:
                  DiploBNGameCatalogApp [manifest-path]

                  DiploBNGameCatalogApp
                      [--manifest <manifest-path>]
                      [--database <database-path>]
                """);
    }

    private static void printHeader(
            Path manifestPath,
            Path databasePath
    ) {

        System.out.println();
        System.out.println("DIPLOBN GAME CATALOG IMPORT:");
        System.out.println();

        System.out.println(
                "Manifest: " + manifestPath);

        System.out.println(
                "Database: " + databasePath);

        System.out.println();

    }

    private static void printReport(
            DiploBNGameCatalogFileImporter.ImportReport report,
            GameCatalog catalog
    ) {

        List<ReportEntry> entries = new ArrayList<>();

        for (DiploBNGameCatalogFileImporter.Success success
                : report.successes()) {
            entries.add(
                    new ReportEntry(
                            success.manifestIndex(),
                            success,
                            null
                    )
            );
        }

        for (DiploBNGameCatalogFileImporter.Failure failure
                : report.failures()) {
            entries.add(
                    new ReportEntry(
                            failure.manifestIndex(),
                            null,
                            failure
                    )
            );
        }

        entries.sort(
                Comparator.comparing(ReportEntry::sortIndex)
        );

        for (ReportEntry entry : entries) {
            if (entry.success() != null)
                printSuccess(entry.success(), catalog);
            else
                printFailure(entry.failure());
        }

        printTotals(report, catalog);

    }

    private static void printSuccess(
            DiploBNGameCatalogFileImporter.Success success,
            GameCatalog catalog
    ) {

        DiploBNGameCatalogFileImporter.ImportedGame imported =
                success.importedGame();

        System.out.printf(
                "[%d] %sIMPORTED%s GameID=%d%n",
                success.manifestIndex(),
                Constants.ANSI_GREEN,
                Constants.ANSI_RESET,
                imported.gameId());

        System.out.printf(
                "    URL:         %s%n",
                imported.canonicalUrl());

        printManifestMetadata(success.entry());

        CatalogGame catalogGame = catalogGameOf(
                imported,
                catalog);

        if (catalogGame == null) {
            System.out.println(
                    "    Catalog:     <not available>");

            System.out.println();
            return;
        }

        System.out.printf(
                "    Catalog ID:  %s%n",
                catalogGame.id().value());

        System.out.printf(
                "    Competition: %s%n",
                valueOrMissing(
                        catalogGame.metadata().competition()));

        System.out.printf(
                "    Game label:  %s%n",
                valueOrMissing(
                        catalogGame.metadata().title()));

        System.out.printf(
                "    Phases:      %d%n",
                catalogGame.sourcePhaseCount());

        printAnalysis(catalogGame.latestAnalysis());

        System.out.println();

    }

    private static void printManifestMetadata(
            DiploBNGameCatalogFileImporter.ManifestEntry entry
    ) {

        if (!entry.tags().isEmpty()) {
            System.out.printf(
                    "    Tags:        %s%n",
                    String.join(
                            ", ",
                            entry.tags()));
        }

        if (entry.notes() != null) {
            System.out.printf(
                    "    Notes:       %s%n",
                    entry.notes());
        }

    }

    private static CatalogGame catalogGameOf(
            DiploBNGameCatalogFileImporter.ImportedGame imported,
            GameCatalog catalog
    ) {

        if (imported.catalogId() == null)
            return null;

        try {
            CatalogGameId id = new CatalogGameId(
                    UUID.fromString(
                            imported.catalogId()));

            return catalog.find(id).orElse(null);

        } catch (IllegalArgumentException exception) {
            return null;
        }

    }

    private static void printAnalysis(
            CatalogAnalysis analysis
    ) {

        if (analysis == null) {
            System.out.println(
                    "    Analysis:    <not available>");

            return;
        }

        System.out.printf(
                "    Comparison:  [%d/%d exact matches]%n",
                analysis.matchingOrderCount(),
                analysis.comparedOrderCount());

        System.out.printf(
                "    Compatibility differences: %d%n",
                analysis.compatibilityDifferenceCount());

        if (analysis.definiteMismatchCount() == 0) {
            System.out.printf(
                    "    Verification: %sPASS%s%n",
                    Constants.ANSI_GREEN,
                    Constants.ANSI_RESET);

            return;
        }

        System.out.printf(
                "    Verification: %sFAIL%s "
                        + "(%d definite mismatch%s)%n",
                Constants.ANSI_RED,
                Constants.ANSI_RESET,
                analysis.definiteMismatchCount(),
                analysis.definiteMismatchCount() == 1
                        ? ""
                        : "es");

    }

    private static void printFailure(
            DiploBNGameCatalogFileImporter.Failure failure
    ) {

        String index = failure.manifestIndex() == null
                ? "manifest"
                : failure.manifestIndex().toString();

        System.out.printf(
                "[%s] %sFAILED%s%n",
                index,
                Constants.ANSI_RED,
                Constants.ANSI_RESET);

        if (failure.url() != null) {
            System.out.printf(
                    "    URL:    %s%n",
                    failure.url());
        }

        System.out.printf(
                "    Reason: %s%s%s%n",
                Constants.ANSI_ORANGE,
                failure.message(),
                Constants.ANSI_RESET);

        System.out.println();

    }

    private static void printTotals(
            DiploBNGameCatalogFileImporter.ImportReport report,
            GameCatalog catalog
    ) {

        List<CatalogGame> mismatchingGames =
                catalog.withDefiniteMismatches();

        System.out.println("----------------------------------------");

        System.out.printf(
                "MANIFEST ENTRIES:          %d%n",
                report.attemptedCount());

        System.out.printf(
                "SUCCESSFUL IMPORTS:        %s[%d]%s%n",
                Constants.ANSI_GREEN,
                report.successes().size(),
                Constants.ANSI_RESET);

        System.out.printf(
                "FAILED IMPORTS:            %s[%d]%s%n",
                report.failures().isEmpty()
                        ? Constants.ANSI_GREEN
                        : Constants.ANSI_RED,
                report.failures().size(),
                Constants.ANSI_RESET);

        System.out.printf(
                "CATALOG ENTRIES:           %d%n",
                catalog.all().size());

        System.out.printf(
                "GAMES WITH MISMATCHES:     %s[%d]%s%n",
                mismatchingGames.isEmpty()
                        ? Constants.ANSI_GREEN
                        : Constants.ANSI_RED,
                mismatchingGames.size(),
                Constants.ANSI_RESET);

        System.out.println("----------------------------------------");
        System.out.println();

    }

    private static String valueOrMissing(
            String value
    ) {
        return value == null
                ? "<not supplied>"
                : value;
    }


    private record Arguments(
            Path manifestPath,
            Path databasePath
    ) {
    }

    private record ReportEntry(
            Integer manifestIndex,
            DiploBNGameCatalogFileImporter.Success success,
            DiploBNGameCatalogFileImporter.Failure failure
    ) {

        private int sortIndex() {
            return manifestIndex == null
                    ? Integer.MAX_VALUE
                    : manifestIndex;
        }

    }

}