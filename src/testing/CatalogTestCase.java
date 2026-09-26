package testing;

import io.catalog.CatalogAnalysis;
import io.catalog.CatalogGame;
import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;
import io.catalog.diplobn.DiploBNGameCatalogFileImporter;
import io.catalog.diplobn.DiploBNGameImporter;
import io.persistence.InMemoryCatalogStore;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNParser;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static testing.AdjudicatorTestCase.TESTCASE_PREFIX;


/**
 * Dependency-free coverage for catalog storage, metadata refresh behavior, and
 * DiploBN catalog-manifest imports.
 */
public final class CatalogTestCase
        implements TestCase {


    public static final String NAME = "Game Catalog";

    private static final Instant FIXED_TIME = Instant.parse(
            "2026-09-26T00:00:00Z");

    private static final String TEST_GAME_SOURCE = """
            {
              "Competition": "Catalog test fixture",
              "GameLabel": "Catalog fixture game",
              "URL": "https://diplobn.com/",
              "GamePhases": [
                {
                  "Phase": 19011,
                  "Status": "AwaitingOrders",
                  "SupplyCenters": {},
                  "Units": {},
                  "Orders": {}
                }
              ]
            }
            """;


    private TestEvaluation evaluation;
    private String eval;


    public CatalogTestCase() {
    }


    @Override
    public void eval(boolean print) {

        TestEvaluation.Builder checks =
                new TestEvaluation.Builder();

        checkInMemoryCatalogStore(checks);
        checkManifestImportContinuation(checks);

        this.evaluation = checks.build();
        this.eval = evaluation.format(NAME);

        if (print)
            printEval();

    }


    private void checkInMemoryCatalogStore(
            TestEvaluation.Builder checks
    ) {

        InMemoryCatalogStore store =
                new InMemoryCatalogStore();

        GameCatalog catalog = new GameCatalog(
                store,
                Clock.fixed(
                        FIXED_TIME,
                        ZoneOffset.UTC));

        GameSourceReference originalSource =
                new GameSourceReference(
                        GameSource.DIPLOBN,
                        "12990",
                        URI.create(
                                "https://diplobn.com/game/?GameID=12990"),
                        URI.create(
                                "https://diplobn.com/game/?GameID=12990")
                );

        CatalogGame imported = catalog.catalog(
                originalSource,
                "Original game label",
                "Original competition",
                "{\"GamePhases\":[]}",
                "original-sha256",
                3
        );

        CatalogGame metadataUpdated = catalog.updateMetadata(
                imported.id(),
                "Regression import",
                List.of(
                        "regression",
                        "convoy")
        );

        CatalogGame analyzed = catalog.recordAnalysis(
                metadataUpdated.id(),
                new CatalogAnalysis(
                        "CatalogTestCase",
                        "test",
                        FIXED_TIME,
                        12,
                        9,
                        2,
                        1
                )
        );

        checks.expect(
                "Catalog find by ID",
                analyzed,
                catalog.find(analyzed.id()).orElse(null)
        );

        checks.expect(
                "Catalog find by source",
                analyzed,
                catalog.find(
                        GameSource.DIPLOBN,
                        "12990").orElse(null)
        );

        checks.expect(
                "Catalog all count",
                1,
                catalog.all().size()
        );

        checks.expect(
                "Catalog tag query",
                List.of(analyzed),
                catalog.tagged("convoy")
        );

        checks.expect(
                "Catalog definite mismatch query",
                List.of(analyzed),
                catalog.withDefiniteMismatches()
        );

        GameSourceReference refreshedSource =
                new GameSourceReference(
                        GameSource.DIPLOBN,
                        "12990",
                        URI.create(
                                "https://diplobn.com/game/?GameID=12990"),
                        URI.create(
                                "https://diplobn.com/game/"
                                        + "?GameID=12990"
                                        + "&source=refresh")
                );

        CatalogGame refreshed = catalog.catalog(
                refreshedSource,
                "Refreshed game label",
                "Refreshed competition",
                "{\"GamePhases\":[{}]}",
                "refreshed-sha256",
                4
        );

        checks.expect(
                "Catalog refresh retains local ID",
                analyzed.id(),
                refreshed.id()
        );

        checks.expect(
                "Catalog refresh updates title",
                "Refreshed game label",
                refreshed.metadata().title()
        );

        checks.expect(
                "Catalog refresh updates competition",
                "Refreshed competition",
                refreshed.metadata().competition()
        );

        checks.expect(
                "Catalog refresh retains local notes",
                "Regression import",
                refreshed.metadata().notes()
        );

        checks.expect(
                "Catalog refresh retains local tags",
                List.of(
                        "regression",
                        "convoy"),
                refreshed.metadata().tags()
        );

        checks.expect(
                "Catalog refresh replaces source payload",
                "{\"GamePhases\":[{}]}",
                refreshed.sourcePayload()
        );

        checks.expect(
                "Catalog refresh preserves analysis",
                analyzed.latestAnalysis(),
                refreshed.latestAnalysis()
        );

        checks.expect(
                "Catalog refresh count remains one",
                1,
                catalog.all().size()
        );

        catalog.close();

    }

    private void checkManifestImportContinuation(
            TestEvaluation.Builder checks
    ) {

        DiploBNGame game = new DiploBNParser().parse(
                TEST_GAME_SOURCE);

        List<DiploBNGameCatalogFileImporter.ManifestEntry>
                receivedEntries = new ArrayList<>();

        DiploBNGameImporter importer = entry -> {

            receivedEntries.add(entry);

            long gameId = gameIdFrom(entry.url());

            return new DiploBNGameCatalogFileImporter.ImportedGame(
                    "catalog-" + gameId,
                    gameId,
                    "https://diplobn.com/game/?GameID="
                            + gameId,
                    game
            );
        };

        DiploBNGameCatalogFileImporter fileImporter =
                new DiploBNGameCatalogFileImporter(
                        Path.of(
                                "catalog-test-manifest.json"),
                        importer
                );

        DiploBNGameCatalogFileImporter.ImportReport report =
                fileImporter.importSource(
                        """
                        {
                          "games": [
                            {
                              "url": "https://diplobn.com/game/?GameID=24680",
                              "tags": ["regression", "convoy"],
                              "notes": "First valid entry."
                            },
                            {
                              "tags": ["malformed"]
                            },
                            {
                              "url": "https://diplobn.com/game/?GameID=24681"
                            }
                          ]
                        }
                        """
                );

        checks.expect(
                "Manifest import attempted all entries",
                3,
                report.attemptedCount()
        );

        checks.expect(
                "Manifest import success count",
                2,
                report.successes().size()
        );

        checks.expect(
                "Manifest import failure count",
                1,
                report.failures().size()
        );

        checks.expect(
                "Manifest malformed entry index",
                Integer.valueOf(1),
                report.failures().getFirst().manifestIndex()
        );

        checks.expect(
                "Manifest imports later valid entry",
                2,
                receivedEntries.size()
        );

        checks.expect(
                "Manifest preserves first-entry tags",
                List.of(
                        "regression",
                        "convoy"),
                receivedEntries.getFirst().tags()
        );

        checks.expect(
                "Manifest preserves first-entry notes",
                "First valid entry.",
                receivedEntries.getFirst().notes()
        );

        checks.expect(
                "Manifest defaults omitted tags",
                List.of(),
                receivedEntries.get(1).tags()
        );

        checks.expect(
                "Manifest defaults omitted notes",
                null,
                receivedEntries.get(1).notes()
        );

        checks.expect(
                "Manifest second imported GameID",
                24681L,
                report.successes()
                        .get(1)
                        .importedGame()
                        .gameId()
        );

        checks.expect(
                "Manifest report records failure",
                false,
                report.completedWithoutFailures()
        );

    }

    private static long gameIdFrom(
            String url
    ) {

        int marker = url.indexOf("GameID=");

        if (marker < 0)
            throw new IllegalArgumentException(
                    "Missing GameID: " + url);

        int valueStart = marker + "GameID=".length();
        int valueEnd = url.indexOf('&', valueStart);

        String value = valueEnd < 0
                ? url.substring(valueStart)
                : url.substring(
                valueStart,
                valueEnd);

        return Long.parseLong(value);

    }


    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getEval() {
        return eval;
    }

    @Override
    public int getScore() {
        return evaluation == null
                ? 0
                : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null
                ? 0
                : evaluation.size();
    }

    @Override
    public void printEval() {

        if (eval == null) {
            System.out.println(
                    "UNEVALUATED "
                            + TESTCASE_PREFIX
                            + getName());
            return;
        }

        System.out.println(eval + "\n\n");

    }

    @Override
    public void printNameAndScore() {
        System.out.printf(
                "[%02d/%02d]\t%s%s%n",
                getScore(),
                getSize(),
                TESTCASE_PREFIX,
                getName()
        );
    }

    @Override
    public String toString() {
        return getName();
    }

}