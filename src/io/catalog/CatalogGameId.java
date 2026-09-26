package io.catalog;

import java.util.Objects;
import java.util.UUID;


/**
 * Stable local identity for one catalog entry.
 */
public record CatalogGameId(
        UUID value  // at present, just UUID
) {

    public CatalogGameId {
        Objects.requireNonNull(value, "value");
    }

    public static CatalogGameId newId() {
        return new CatalogGameId(UUID.randomUUID());
    }

}