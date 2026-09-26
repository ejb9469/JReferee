package io.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;


/**
 * User- and import-oriented metadata for a stored game.
 */
public record CatalogMetadata(
        String title,
        String competition,
        String notes,
        List<String> tags,
        Instant firstImportedAt,
        Instant lastRefreshedAt
) {

    public CatalogMetadata {

        tags = List.copyOf(
                Objects.requireNonNull(tags, "tags"));

        Objects.requireNonNull(firstImportedAt, "firstImportedAt");

        Objects.requireNonNull(lastRefreshedAt, "lastRefreshedAt");

    }

    public static CatalogMetadata importedNow(
            String title,
            String competition,
            Instant now
    ) {
        return new CatalogMetadata(
                title,
                competition,
                null,
                List.of(),
                now, now);
    }

}