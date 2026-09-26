package io.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * Application service for indexing externally sourced and locally archived
 * Diplomacy games.
 *
 * <p>It owns catalog-level identity, metadata, tagging, payload replacement,
 * and verification status. Provider-specific downloading and parsing belong to
 * importer adapters outside this class.</p>
 */
public class GameCatalog
        implements AutoCloseable {


    private final CatalogStore store;
    private final Clock clock;


    public GameCatalog(CatalogStore store) {
        this(store, Clock.systemUTC());
    }

    public GameCatalog(CatalogStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }


    /**
     * Creates or refreshes one entry, deduplicated by source and external key.
     */
    public CatalogGame catalog(
            GameSourceReference source,
            String title,
            String competition,
            String sourcePayload,
            String sourcePayloadSha256,
            int sourcePhaseCount
    ) {

        Objects.requireNonNull(source, "source");

        Instant now = clock.instant();

        Optional<CatalogGame> existing = store.findBySource(
                source.source(),
                source.externalKey());

        CatalogGame entry;

        if (existing.isPresent()) {
            CatalogGame prior = existing.get();

            CatalogMetadata refreshedMetadata =
                    new CatalogMetadata(
                            title == null
                                    ? prior.metadata().title()
                                    : title,
                            competition == null
                                    ? prior.metadata().competition()
                                    : competition,
                            prior.metadata().notes(),
                            prior.metadata().tags(),
                            prior.metadata().firstImportedAt(),
                            now
                    );

            entry = new CatalogGame(
                    prior.id(),
                    source,
                    refreshedMetadata,
                    sourcePayload,
                    sourcePayloadSha256,
                    sourcePhaseCount,
                    prior.latestAnalysis(),
                    prior.createdAt(),
                    now
            );
        } else {
            entry = new CatalogGame(
                    CatalogGameId.newId(),
                    source,
                    CatalogMetadata.importedNow(
                            title,
                            competition,
                            now),
                    sourcePayload,
                    sourcePayloadSha256,
                    sourcePhaseCount,
                    null,
                    now,
                    now
            );
        }

        return store.upsert(entry);

    }

    /**
     * Replaces locally managed notes and tags while retaining source metadata,
     * source payload, source identity, and existing analysis.
     */
    public CatalogGame updateMetadata(
            CatalogGameId id,
            String notes,
            List<String> tags
    ) {

        CatalogGame prior = require(id);

        CatalogMetadata metadata = new CatalogMetadata(
                prior.metadata().title(),
                prior.metadata().competition(),
                notes,
                Objects.requireNonNull(tags, "tags"),
                prior.metadata().firstImportedAt(),
                prior.metadata().lastRefreshedAt()
        );

        CatalogGame updated = new CatalogGame(
                prior.id(),
                prior.source(),
                metadata,
                prior.sourcePayload(),
                prior.sourcePayloadSha256(),
                prior.sourcePhaseCount(),
                prior.latestAnalysis(),
                prior.createdAt(),
                clock.instant()
        );

        return store.upsert(updated);

    }

    /**
     * Stores a new analysis while retaining the original source snapshot.
     */
    public CatalogGame recordAnalysis(
            CatalogGameId id,
            CatalogAnalysis analysis
    ) {

        CatalogGame prior = require(id);

        CatalogGame updated = new CatalogGame(
                prior.id(),
                prior.source(),
                prior.metadata(),
                prior.sourcePayload(),
                prior.sourcePayloadSha256(),
                prior.sourcePhaseCount(),
                Objects.requireNonNull(analysis, "analysis"),
                prior.createdAt(),
                clock.instant()
        );

        return store.upsert(updated);

    }

    public Optional<CatalogGame> find(
            CatalogGameId id
    ) {
        return store.findById(
                Objects.requireNonNull(id, "id"));
    }

    public Optional<CatalogGame> find(
            GameSource source,
            String externalKey
    ) {
        return store.findBySource(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(externalKey, "externalKey"));
    }

    public List<CatalogGame> all() {
        return store.findAll();
    }

    public List<CatalogGame> tagged(String tag) {
        return store.findByTag(
                Objects.requireNonNull(tag, "tag"));
    }

    public List<CatalogGame> withDefiniteMismatches() {
        return store.findWithDefiniteMismatches();
    }

    private CatalogGame require(CatalogGameId id) {
        return find(id).orElseThrow(
                () -> new IllegalArgumentException(
                        "Catalog game was not found: " + id));
    }


    @Override
    public void close() {
        store.close();
    }


}