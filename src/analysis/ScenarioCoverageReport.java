package analysis;

import java.util.Locale;


public record ScenarioCoverageReport(
        int trainingGames,
        int testGames,
        long perspectives,
        long incompleteLabels,
        long eligiblePerspectives,
        long generatedPerspectives,
        long generatedScenarios,
        long topOneHits,
        long scenarioHits
) {

    public ScenarioCoverageReport {

        if (trainingGames < 1 || testGames < 1)
            throw new IllegalArgumentException("Training and test games must be positive");

        if (perspectives < 0
                || incompleteLabels < 0
                || eligiblePerspectives < 0
                || generatedPerspectives < 0
                || generatedScenarios < 0
                || topOneHits < 0
                || scenarioHits < 0)
            throw new IllegalArgumentException("Report counts must not be negative");

        if (Math.addExact(incompleteLabels, eligiblePerspectives) != perspectives)
            throw new IllegalArgumentException("Perspective counts do not agree");

        if (topOneHits > scenarioHits
                || scenarioHits > generatedPerspectives
                || generatedPerspectives > eligiblePerspectives
                || generatedScenarios < generatedPerspectives)
            throw new IllegalArgumentException("Inconsistent scenario counts");

        if (generatedPerspectives == 0 && generatedScenarios != 0)
            throw new IllegalArgumentException("Scenarios exist without generated perspectives");

    }


    public long insufficientEvidence() {
        return eligiblePerspectives - generatedPerspectives;
    }

    @Override
    public String toString() {

        return String.format(Locale.ROOT, """
                HELD-OUT OPPONENT SCENARIO COVERAGE

                Training games: %d
                Test games:     %d

                Nation-phase perspectives: %d
                Incomplete opponent labels:%d
                Eligible perspectives:     %d
                Insufficient evidence:     %d

                Scenario-generation coverage: %s
                Top scenario matches actual:  %s
                Actual scenario anywhere:     %s
                Actual scenario, covered only:%s

                Scenarios generated: %d
                Mean scenarios per covered perspective: %s
                """,
                trainingGames,
                testGames,
                perspectives,
                incompleteLabels,
                eligiblePerspectives,
                insufficientEvidence(),
                ratio(generatedPerspectives, eligiblePerspectives),
                ratio(topOneHits, eligiblePerspectives),
                ratio(scenarioHits, eligiblePerspectives),
                ratio(scenarioHits, generatedPerspectives),
                generatedScenarios,
                generatedPerspectives == 0 ? "n/a"
                        : String.format(Locale.ROOT, "%.2f",
                        (double) generatedScenarios / generatedPerspectives));

    }

    private static String ratio(long numerator, long denominator) {

        if (denominator == 0)
            return "n/a (0 eligible observations)";

        return String.format(Locale.ROOT, "%.2f%% (%d/%d)",
                100.0 * numerator / denominator, numerator, denominator);

    }

}