package analysis;

import game.BoardState;

import java.util.Objects;


public enum PredictionPolicy {

    ORIGINAL,
    REFERENCE_FILTERED,
    YEAR_POOLED_SELECTION,
    YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED;


    public RoutePrediction apply(
            BoardState board,
            RoutePrediction original,
            RoutePrediction yearPooled,
            long minimumObservations) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(original, "original");

        if (minimumObservations < 1)
            throw new IllegalArgumentException("Minimum observations must be positive");

        RoutePrediction selected = switch (this) {
            case ORIGINAL -> original;
            case REFERENCE_FILTERED ->
                    ReferenceFilteredPrediction.apply(board, original);
            case YEAR_POOLED_SELECTION ->
                    YearPooledSelection.select(
                            original,
                            Objects.requireNonNull(yearPooled, "yearPooled"),
                            minimumObservations);
            case YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED ->
                    ReferenceFilteredPrediction.apply(
                            board,
                            YearPooledSelection.select(
                                    original,
                                    Objects.requireNonNull(yearPooled, "yearPooled"),
                                    minimumObservations));
        };

        return selected;

    }

}
