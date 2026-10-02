package analysis;

import java.util.Locale;


public record HeldOutReport(
        int trainingGames,
        int testGames,
        int topK,
        long submittedDecisions,
        long predictedDecisions,
        long topOneHits,
        long topKHits,
        long exactPredictions,
        long suffixPredictions,
        long positionPredictions,
        long eligibleNationalTurns,
        long generatedNationalTurns,
        long nationalPlanHits
) {

    public HeldOutReport {

        if (trainingGames < 1 || testGames < 1 || topK < 1)
            throw new IllegalArgumentException("Games and top-K must be positive");

        if (submittedDecisions < 0
                || predictedDecisions < 0
                || topOneHits < 0
                || topKHits < 0
                || exactPredictions < 0
                || suffixPredictions < 0
                || positionPredictions < 0
                || eligibleNationalTurns < 0
                || generatedNationalTurns < 0
                || nationalPlanHits < 0)
            throw new IllegalArgumentException("Evaluation counts must not be negative");

        if (topOneHits > topKHits
                || topKHits > predictedDecisions
                || predictedDecisions > submittedDecisions)
            throw new IllegalArgumentException("Inconsistent decision counts");

        long breakdown = Math.addExact(
                Math.addExact(exactPredictions, suffixPredictions), positionPredictions);

        if (breakdown != predictedDecisions)
            throw new IllegalArgumentException("Prediction basis counts do not match coverage");

        if (nationalPlanHits > generatedNationalTurns
                || generatedNationalTurns > eligibleNationalTurns)
            throw new IllegalArgumentException("Inconsistent national-turn counts");

    }


    @Override
    public String toString() {

        return String.format(Locale.ROOT, """
                HELD-OUT IMITATION EVALUATION

                Training games: %d
                Test games:     %d

                Submitted unit decisions: %d
                Prediction coverage:      %s
                Top-1, all decisions:      %s
                Top-%d, all decisions:     %s
                Top-1, predicted only:     %s

                Prediction basis:
                    Exact:    %d
                    Suffix:   %d
                    Position: %d

                Complete national turns:  %d
                Candidate-plan coverage:  %s
                Actual plan in beam:      %s
                Actual plan, covered only:%s
                """,
                trainingGames,
                testGames,
                submittedDecisions,
                ratio(predictedDecisions, submittedDecisions),
                ratio(topOneHits, submittedDecisions),
                topK,
                ratio(topKHits, submittedDecisions),
                ratio(topOneHits, predictedDecisions),
                exactPredictions,
                suffixPredictions,
                positionPredictions,
                eligibleNationalTurns,
                ratio(generatedNationalTurns, eligibleNationalTurns),
                ratio(nationalPlanHits, eligibleNationalTurns),
                ratio(nationalPlanHits, generatedNationalTurns));

    }

    private static String ratio(long numerator, long denominator) {

        if (denominator == 0)
            return "n/a (0 eligible observations)";

        return String.format(Locale.ROOT, "%.2f%% (%d/%d)",
                100.0 * numerator / denominator, numerator, denominator);

    }

}