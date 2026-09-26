package io.catalog;

import java.net.URI;
import java.util.Objects;


/**
 * Provider-specific identity for a cataloged game.
 *
 * <p>The {@code externalKey} is the provider's stable identity. For DiploBN,
 * it is the decimal GameID extracted from the supplied game link.</p>
 */
public record GameSourceReference(
        GameSource source,
        String externalKey,
        URI canonicalUri,
        URI submittedUri
) {

    public GameSourceReference {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(externalKey, "externalKey");
        Objects.requireNonNull(canonicalUri, "canonicalUri");

        if (externalKey.isBlank())
            throw new IllegalArgumentException("externalKey must not be blank");


    }

}