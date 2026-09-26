package io.catalog.diplobn;

import io.catalog.CatalogAnalysis;
import io.catalog.CatalogGame;
import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;
import parsing.diplobn.DiploBNAdjudicationComparator;
import parsing.diplobn.DiploBNDownloadedGame;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.DiploBNPhase;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;


/**
 * Imports one DiploBN game into a {@link GameCatalog}.
 *
 * <p>The importer preserves canonical source JSON, updates catalog metadata
 * from the manifest, compares imported movement phases with local adjudication,
 * and records the resulting analysis.</p>
 */
public final class DiploBNCatalogImporter
        implements DiploBNGameImporter {


    private static final String ANALYZER =
            "DiploBNAdjudicationComparator";

    private static final String ANALYZER_REVISION =
            "JReferee";


    private final GameCatalog catalog;
    private final DiploBNGameClient gameClient;
    private final Clock clock;


    public DiploBNCatalogImporter(
            GameCatalog catalog
    ) {
        this(
                catalog,
                new DiploBNGameClient(),
                Clock.systemUTC());
    }

    public DiploBNCatalogImporter(
            GameCatalog catalog,
            DiploBNGameClient gameClient,
            Clock clock
    ) {
        this.catalog = Objects.requireNonNull(
                catalog,
                "catalog");

        this.gameClient = Objects.requireNonNull(
                gameClient,
                "gameClient");

        this.clock = Objects.requireNonNull(
                clock,
                "clock");
    }


    @Override
    public DiploBNGameCatalogFileImporter.ImportedGame importGame(
            DiploBNGameCatalogFileImporter.ManifestEntry entry
    ) throws IOException, InterruptedException {

        Objects.requireNonNull(entry, "entry");

        DiploBNDownloadedGame downloaded =
                gameClient.downloadGamePage(entry.url());

        DiploBNGame game = downloaded.game();

        GameSourceReference source =
                new GameSourceReference(
                        GameSource.DIPLOBN,
                        Long.toString(downloaded.gameId()),
                        URI.create(downloaded.canonicalUrl()),
                        URI.create(entry.url())
                );

        CatalogGame catalogGame = catalog.catalog(
                source,
                game.gameLabel(),
                game.competition(),
                downloaded.sourceJson(),
                sha256Of(downloaded.sourceJson()),
                game.phases().size()
        );

        catalogGame = catalog.updateMetadata(
                catalogGame.id(),
                entry.notes(),
                entry.tags()
        );

        ComparisonSummary comparison = compare(game);

        catalogGame = catalog.recordAnalysis(
                catalogGame.id(),
                new CatalogAnalysis(
                        ANALYZER,
                        ANALYZER_REVISION,
                        clock.instant(),
                        comparison.comparedOrderCount(),
                        comparison.matchingOrderCount(),
                        comparison.compatibilityDifferenceCount(),
                        comparison.definiteMismatchCount()
                )
        );

        return new DiploBNGameCatalogFileImporter.ImportedGame(
                catalogGame.id().value().toString(),
                downloaded.gameId(),
                downloaded.canonicalUrl(),
                game
        );

    }


    private static ComparisonSummary compare(
            DiploBNGame game
    ) {

        int comparedOrderCount = 0;
        int matchingOrderCount = 0;
        int coastAmbiguousCount = 0;
        int ineffectiveSupportCount = 0;
        int definiteMismatchCount = 0;

        for (DiploBNPhase phase : game.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparator comparison =
                    DiploBNAdjudicationComparator.compare(phase);

            comparedOrderCount += comparison.comparedCount();
            matchingOrderCount += comparison.matchingCount();
            coastAmbiguousCount += comparison.coastAmbiguousCount();
            ineffectiveSupportCount +=
                    comparison.ineffectiveSupportCount();
            definiteMismatchCount += comparison.mismatchingCount();

        }

        return new ComparisonSummary(
                comparedOrderCount,
                matchingOrderCount,
                coastAmbiguousCount,
                ineffectiveSupportCount,
                definiteMismatchCount
        );

    }

    private static String sha256Of(
            String source
    ) {

        Objects.requireNonNull(source, "source");

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            source.getBytes(
                                    StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }

    }


    private record ComparisonSummary(
            int comparedOrderCount,
            int matchingOrderCount,
            int coastAmbiguousCount,
            int ineffectiveSupportCount,
            int definiteMismatchCount
    ) {

        private int compatibilityDifferenceCount() {
            return coastAmbiguousCount
                    + ineffectiveSupportCount;
        }

    }

}