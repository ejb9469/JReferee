package io.catalog;


/**
 * Imports games into a catalog from one configured source.
 *
 * <p>Implementations may obtain source data from a manifest file, an external
 * game service, an archive export, or another catalog.</p>
 *
 * @param <R> immutable import report produced by the importer
 */
public interface CatalogImporter<R> {


    /**
     * Imports all games available from this importer's configured source.
     *
     * @return immutable report describing successful and failed imports
     */
    R importGames();

}