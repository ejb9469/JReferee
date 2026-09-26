package io.persistence;

import io.catalog.CatalogGame;
import io.catalog.CatalogGameId;
import io.catalog.CatalogStore;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


/**
 * In-memory {@link CatalogStore} implementation for tests and temporary
 * application sessions.
 *
 * <p>This store preserves insertion order for {@link #findAll()} and enforces
 * the same external source identity rule expected from persistent stores:
 * one entry may exist for each source and external key pair.</p>
 */
public class InMemoryCatalogStore
        implements CatalogStore {


    private final Map<CatalogGameId, CatalogGame> gamesById;

    private final Map<SourceIdentity, CatalogGameId>
            gameIdsBySource;

    private boolean closed;


    public InMemoryCatalogStore() {
        this.gamesById = new LinkedHashMap<>();
        this.gameIdsBySource = new LinkedHashMap<>();
        this.closed = false;
    }


    @Override
    public CatalogGame upsert(CatalogGame game) {

        requireOpen();

        Objects.requireNonNull(game, "game");

        SourceIdentity sourceIdentity = SourceIdentity.of(
                game.source());

        CatalogGameId existingId = gameIdsBySource.get(
                sourceIdentity);

        if (existingId != null
                && !existingId.equals(game.id()))
            throw new IllegalArgumentException(
                    "Catalog source identity already belongs to "
                            + existingId);

        CatalogGame previous = gamesById.put(
                game.id(),
                game);

        if (previous != null) {
            SourceIdentity previousIdentity = SourceIdentity.of(
                    previous.source());

            if (!previousIdentity.equals(sourceIdentity))
                gameIdsBySource.remove(
                        previousIdentity,
                        previous.id());
        }

        gameIdsBySource.put(
                sourceIdentity,
                game.id());

        return game;

    }

    @Override
    public Optional<CatalogGame> findById(
            CatalogGameId id
    ) {

        requireOpen();

        return Optional.ofNullable(
                gamesById.get(
                        Objects.requireNonNull(id, "id")));

    }

    @Override
    public Optional<CatalogGame> findBySource(
            GameSource source,
            String externalKey
    ) {

        requireOpen();

        SourceIdentity identity = new SourceIdentity(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(
                        externalKey,
                        "externalKey"));

        CatalogGameId id = gameIdsBySource.get(identity);

        if (id == null)
            return Optional.empty();

        return Optional.ofNullable(
                gamesById.get(id));

    }

    @Override
    public List<CatalogGame> findAll() {

        requireOpen();

        return List.copyOf(
                gamesById.values());

    }

    @Override
    public List<CatalogGame> findByTag(
            String tag
    ) {

        requireOpen();

        Objects.requireNonNull(tag, "tag");

        List<CatalogGame> games = new ArrayList<>();

        for (CatalogGame game : gamesById.values()) {
            if (game.metadata().tags().contains(tag))
                games.add(game);
        }

        return List.copyOf(games);

    }

    @Override
    public List<CatalogGame> findWithDefiniteMismatches() {

        requireOpen();

        List<CatalogGame> games = new ArrayList<>();

        for (CatalogGame game : gamesById.values()) {

            if (game.latestAnalysis() == null)
                continue;

            if (game.latestAnalysis().definiteMismatchCount() > 0)
                games.add(game);
        }

        return List.copyOf(games);

    }

    @Override
    public void delete(
            CatalogGameId id
    ) {

        requireOpen();

        CatalogGame removed = gamesById.remove(
                Objects.requireNonNull(id, "id"));

        if (removed == null)
            return;

        gameIdsBySource.remove(
                SourceIdentity.of(removed.source()),
                removed.id());

    }

    @Override
    public void close() {
        closed = true;
    }


    private void requireOpen() {

        if (closed)
            throw new IllegalStateException(
                    "Catalog store is closed");

    }


    /**
     * Stable in-memory key for provider-specific source identity.
     */
    private record SourceIdentity(
            GameSource source,
            String externalKey
    ) {

        private SourceIdentity {

            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(
                    externalKey,
                    "externalKey");

            if (externalKey.isBlank())
                throw new IllegalArgumentException(
                        "externalKey must not be blank");
        }

        private static SourceIdentity of(
                GameSourceReference source
        ) {

            Objects.requireNonNull(source, "source");

            return new SourceIdentity(
                    source.source(),
                    source.externalKey());
        }

    }

}