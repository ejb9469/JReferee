package io.catalog.diplobn;

import io.GameImporter;


/**
 * Imports one DiploBN game request into the game catalog.
 *
 * <p>Implementations own DiploBN download, canonical GameID extraction,
 * source-payload preservation, comparison, and catalog persistence.</p>
 */
@FunctionalInterface
public interface DiploBNGameImporter
        extends GameImporter<
        DiploBNGameCatalogFileImporter.ManifestEntry,
        DiploBNGameCatalogFileImporter.ImportedGame> {
}