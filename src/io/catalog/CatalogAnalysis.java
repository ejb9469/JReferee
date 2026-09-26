package io.catalog;

import java.time.Instant;
import java.util.Objects;


/**
 * Latest reproducible local analysis stored for a catalog entry.
 *
 * <p>These counts are intentionally generic enough for a first catalog
 * version while preserving DiploBN comparison semantics. A null analysis
 * means the game has been cataloged but not locally analyzed.</p>
 */
public record CatalogAnalysis(
        String analyzer,
        String analyzerRevision,
        Instant analyzedAt,
        int comparedOrderCount,
        int matchingOrderCount,
        int compatibilityDifferenceCount,
        int definiteMismatchCount
) {

    public CatalogAnalysis {

        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(analyzerRevision, "analyzerRevision");
        Objects.requireNonNull(analyzedAt, "analyzedAt");

        if (comparedOrderCount < 0
                || matchingOrderCount < 0
                || compatibilityDifferenceCount < 0
                || definiteMismatchCount < 0)
            throw new IllegalArgumentException("analysis counts must not be negative");

    }

    /**
     * A known compatibility difference is acceptable; only unexplained
     * mismatches fail a catalog verification.
     */
    public boolean passesVerification() {
        return definiteMismatchCount == 0;
    }

}