package io.catalog.diplobn;

import io.catalog.CatalogAnalysis;
import parsing.diplobn.DiploBNAdjudicationComparator;
import parsing.diplobn.DiploBNGame;

import java.time.Instant;
import java.util.Objects;


/**
 * Produces catalog analysis from one imported DiploBN game.
 */
public final class DiploBNAnalysis {


    private static final String ANALYZER = "DiploBNAdjudicationComparator";
    private static final String ANALYZER_REVISION =
            "JReferee-ineffective-convoy-v1";


    private DiploBNAnalysis() {  }


    public static CatalogAnalysis analyze(
            DiploBNGame game,
            Instant analyzedAt
    ) {

        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(analyzedAt, "analyzedAt");

        int comparedOrderCount = 0;
        int matchingOrderCount = 0;
        int compatibilityDifferenceCount = 0;
        int definiteMismatchCount = 0;

        for (var phase : game.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparator comparison =
                    DiploBNAdjudicationComparator.compare(phase);

            comparedOrderCount += comparison.comparedCount();
            matchingOrderCount += comparison.matchingCount();
            compatibilityDifferenceCount +=
                    comparison.compatibilityDifferenceCount();
            definiteMismatchCount += comparison.mismatchingCount();

        }

        return new CatalogAnalysis(
                ANALYZER,
                ANALYZER_REVISION,
                analyzedAt,
                comparedOrderCount,
                matchingOrderCount,
                compatibilityDifferenceCount,
                definiteMismatchCount
        );

    }


}
