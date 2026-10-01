package io.catalog.diplobn;

import io.catalog.CatalogGame;
import io.catalog.GameCatalog;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;
import io.catalog.SourceFingerprints;
import parsing.diplobn.DiploBNDownloadedGame;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;


/**
 * Imports one DiploBN game into a GameCatalog.
 *
 * <p>Preserves canonical source JSON, updates catalog metadata from the
 * manifest, compares imported movement phases with local adjudication,
 * and records the resulting analysis.</p>
 */
public final class DiploBNCatalogImporter
        implements DiploBNGameImporter {


    // Core state \\

    private final GameCatalog catalog;
    private final DiploBNGameClient gameClient;
    private final Clock clock;


    // Constructors \\

    public DiploBNCatalogImporter(GameCatalog catalog) {

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

        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.gameClient = Objects.requireNonNull(gameClient, "gameClient");
        this.clock = Objects.requireNonNull(clock, "clock");

    }


    // Import entry point \\

    @Override
    public DiploBNGameCatalogFileImporter.ImportedGame importGame(
            DiploBNGameCatalogFileImporter.ManifestEntry entry
    ) throws IOException, InterruptedException {

        Objects.requireNonNull(entry, "entry");

        DiploBNDownloadedGame downloaded =
                gameClient.downloadGamePage(entry.url());

        DiploBNGame game = downloaded.game();

        GameSourceReference source = new GameSourceReference(
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
                SourceFingerprints.sha256Hex(downloaded.sourceJson()),
                game.phases().size()
        );

        catalogGame = catalog.updateMetadata(
                catalogGame.id(),
                entry.notes(),
                entry.tags()
        );

        catalogGame = catalog.recordAnalysis(
                catalogGame.id(),
                DiploBNAnalysis.analyze(game, clock.instant())
        );

        return new DiploBNGameCatalogFileImporter.ImportedGame(
                catalogGame.id().value().toString(),
                downloaded.gameId(),
                downloaded.canonicalUrl(),
                game
        );

    }

}