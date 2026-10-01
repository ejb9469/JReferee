package _app;

import domain.Constants;
import io.catalog.CatalogAnalysis;
import io.catalog.CatalogGame;
import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.diplobn.DiploBNCatalogImporter;
import io.catalog.diplobn.DiploBNGameCatalogFileImporter;
import io.persistence.SQLiteCatalogStore;
import parsing.diplobn.DiploBNAdjudicationComparator;
import parsing.diplobn.DiploBNParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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

        Path databasePath = arguments.databasePath()
                .toAbsolutePath()
                .normalize();

        Constants.printTimestamp();

        try {

            if (arguments.manifestPath() == null
                    && !Files.isRegularFile(databasePath))
                throw new IllegalStateException(
                        "Database not found: " + databasePath);

            try (GameCatalog catalog = new GameCatalog(
                    new SQLiteCatalogStore(databasePath)))
            {
                printHeader(
                        arguments.manifestPath(),
                        databasePath);

                DiploBNGameCatalogFileImporter.ImportReport report = null;

                if (arguments.manifestPath() != null) {

                    DiploBNCatalogImporter gameImporter =
                            new DiploBNCatalogImporter(catalog);

                    DiploBNGameCatalogFileImporter fileImporter =
                            new DiploBNGameCatalogFileImporter(
                                    arguments.manifestPath(),
                                    gameImporter);

                    report = fileImporter.importGames();

                }

                if (Thread.currentThread().isInterrupted())
                    return;

                printReport(report, catalog);

                Constants.printTimestamp();
            }

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

        Path manifestPath = null;
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
                  DBNGameCatalogApp [manifest-path]

                  DBNGameCatalogApp
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
                "Manifest: " + (manifestPath == null
                        ? "<not supplied>"
                        : manifestPath));

        System.out.println(
                "Database: " + databasePath);

        System.out.println();

    }

    private static void printReport(
            DiploBNGameCatalogFileImporter.ImportReport report,
            GameCatalog catalog
    ) {

        List<ReportEntry> entries = new ArrayList<>();
        Map<String, Integer> manifestIndexes = new HashMap<>();

        int nextIndex = 0;

        if (report != null) {

            nextIndex = report.attemptedCount();

            for (DiploBNGameCatalogFileImporter.Success success
                    : report.successes()) {
                manifestIndexes.putIfAbsent(
                        success.importedGame().catalogId(),
                        success.manifestIndex());
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

        }

        for (CatalogGame game : catalog.all()) {

            Integer index = manifestIndexes.get(
                    game.id().value().toString());

            if (index == null)
                index = nextIndex++;

            entries.add(
                    new ReportEntry(
                            index,
                            game,
                            null
                    )
            );

        }

        entries.sort(
                Comparator.comparing(ReportEntry::sortIndex)
        );

        DiploBNParser parser = new DiploBNParser();

        for (ReportEntry entry : entries) {

            if (Thread.currentThread().isInterrupted())
                return;

            if (entry.failure() != null) {
                printFailure(entry.failure());
                continue;
            }

            CatalogGame game = entry.game();

            if (game.source().source() == GameSource.DIPLOBN) {

                CatalogAnalysis analysis;

                try {
                    analysis = analyze(game, parser);
                } catch (RuntimeException exception) {
                    printFailure(
                            new DiploBNGameCatalogFileImporter.Failure(
                                    entry.index(),
                                    game.source().canonicalUri().toString(),
                                    describe(exception),
                                    exception
                            )
                    );
                    continue;
                }

                game = catalog.recordAnalysis(
                        game.id(),
                        analysis);

            }

            printSuccess(entry.index(), game);

        }

        printTotals(report, catalog);

    }

    private static CatalogAnalysis analyze(
            CatalogGame catalogGame,
            DiploBNParser parser
    ) {

        var game = parser.parse(catalogGame.sourcePayload());

        int comparedOrderCount = 0;
        int matchingOrderCount = 0;
        int compatibilityDifferenceCount = 0;
        int definiteMismatchCount = 0;

        for (var phase : game.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparator comparison =
                    DiploBNAdjudicationComparator.compare(phase);

            comparedOrderCount += comparison.comparedCount();
            matchingOrderCount += comparison.matchingCount();
            compatibilityDifferenceCount +=
                    comparison.compatibilityDifferenceCount();
            definiteMismatchCount += comparison.mismatchingCount();

        }

        return new CatalogAnalysis(
                "DiploBNAdjudicationComparator",
                "JReferee-ineffective-convoy-v1",
                Instant.now(),
                comparedOrderCount,
                matchingOrderCount,
                compatibilityDifferenceCount,
                definiteMismatchCount
        );

    }

    private static void printSuccess(
            int index,
            CatalogGame catalogGame
    ) {

        System.out.printf(
                "[%d] %sIMPORTED%s GameID=%s%n",
                index,
                Constants.ANSI_GREEN,
                Constants.ANSI_RESET,
                catalogGame.source().externalKey());

        System.out.printf(
                "    URL:         %s%n",
                catalogGame.source().canonicalUri());

        if (!catalogGame.metadata().tags().isEmpty()) {
            System.out.printf(
                    "    Tags:        %s%n",
                    String.join(
                            ", ",
                            catalogGame.metadata().tags()));
        }

        if (catalogGame.metadata().notes() != null) {
            System.out.printf(
                    "    Notes:       %s%n",
                    catalogGame.metadata().notes());
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

        int attempted = report == null
                ? 0
                : report.attemptedCount();

        int successful = report == null
                ? 0
                : report.successes().size();

        int failed = report == null
                ? 0
                : report.failures().size();

        System.out.println("----------------------------------------");

        System.out.printf(
                "MANIFEST ENTRIES:          %d%n",
                attempted);

        System.out.printf(
                "SUCCESSFUL IMPORTS:        %s[%d]%s%n",
                Constants.ANSI_GREEN,
                successful,
                Constants.ANSI_RESET);

        System.out.printf(
                "FAILED IMPORTS:            %s[%d]%s%n",
                failed == 0
                        ? Constants.ANSI_GREEN
                        : Constants.ANSI_RED,
                failed,
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

    private static String describe(
            Exception exception
    ) {
        return exception.getClass().getSimpleName()
                + (exception.getMessage() == null
                ? ""
                : ": " + exception.getMessage());
    }


    private record Arguments(
            Path manifestPath,
            Path databasePath
    ) {
    }

    private record ReportEntry(
            Integer index,
            CatalogGame game,
            DiploBNGameCatalogFileImporter.Failure failure
    ) {

        private int sortIndex() {
            return index == null
                    ? Integer.MAX_VALUE
                    : index;
        }

    }

}