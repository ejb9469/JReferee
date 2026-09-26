package io.catalog;

import java.util.List;
import java.util.Optional;


/**
 * Persistence boundary for catalog entries.
 */
public interface CatalogStore
        extends AutoCloseable {


    /**
     * Inserts a new entry or replaces the stored payload and metadata for an
     * entry with the same external source identity.
     */
    CatalogGame upsert(CatalogGame game);

    Optional<CatalogGame> findById(CatalogGameId id);

    Optional<CatalogGame> findBySource(
            GameSource source,
            String externalKey
    );

    List<CatalogGame> findAll();

    List<CatalogGame> findByTag(String tag);

    List<CatalogGame> findWithDefiniteMismatches();


    void delete(CatalogGameId id);


    @Override
    void close();  // child must override


}