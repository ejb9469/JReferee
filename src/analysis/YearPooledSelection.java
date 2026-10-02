package analysis;

import java.util.Objects;


public final class YearPooledSelection {

    private YearPooledSelection() {  }


    public static RoutePrediction select(
            RoutePrediction original,
            RoutePrediction yearPooled,
            long minimumObservations) {

        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(yearPooled, "yearPooled");

        if (minimumObservations < 1)
            throw new IllegalArgumentException(
                    "Minimum observations must be positive");

        boolean replaceable = switch (original.basis()) {
            case "EXACT", "SUFFIX", "POSITION" -> true;
            default -> false;
        };

        if (!replaceable
                || original.counts().isEmpty()
                || yearPooled.observations() < minimumObservations)
            return original;

        if (!yearPooled.basis().equals("POSITION_YEAR_POOLED"))
            throw new IllegalArgumentException(
                    "Expected year-pooled position evidence");

        return yearPooled;

    }

}