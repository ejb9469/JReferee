package io.catalog;

import java.time.Instant;
import java.util.Objects;


/**
 * Complete catalog entry, including a replayable source payload.
 *
 * <p>The payload is deliberately stored as text rather than as a provider's
 * parsed object graph. This preserves the original source snapshot and allows
 * later parser/adjudicator versions to re-import it.</p>
 */
public record CatalogGame(
        CatalogGameId id,
        GameSourceReference source,
        CatalogMetadata metadata,
        String sourcePayload,
        String sourcePayloadSha256,
        int sourcePhaseCount,
        CatalogAnalysis latestAnalysis,
        Instant createdAt,
        Instant updatedAt
) {

    public CatalogGame {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(sourcePayload, "sourcePayload");
        Objects.requireNonNull(sourcePayloadSha256, "sourcePayloadSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (sourcePayload.isBlank())
            throw new IllegalArgumentException("sourcePayload must not be blank");

        if (sourcePayloadSha256.isBlank())
            throw new IllegalArgumentException("sourcePayloadSha256 must not be blank");

        if (sourcePhaseCount < 0)
            throw new IllegalArgumentException("sourcePhaseCount must not be negative");

    }

}