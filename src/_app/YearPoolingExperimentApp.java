package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.StageCoverageEvaluation;
import game.record.GameRecord;

import java.nio.file.Path;
import java.util.List;


public final class YearPoolingExperimentApp {

    private YearPoolingExperimentApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: YearPoolingExperimentApp [database-path]");

        Path database = (args.length == 0
                ? Path.of("data", "diplobn-catalog.sqlite")
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        List<GameRecord> training =
                corpus.games(CorpusPartition.TRAINING);

        List<GameRecord> validation =
                corpus.games(CorpusPartition.VALIDATION);

        System.out.println();
        System.out.println("=== BASELINE: YEAR-SPECIFIC CONTEXT ===");

        var baseline = new StageCoverageEvaluation(
                4, 4, 8, 32, 3, false
        ).evaluate(training, validation, System.out);

        baseline.print(System.out);

        System.out.println();
        System.out.println("=== EXPERIMENT: FINAL CROSS-YEAR POSITION FALLBACK ===");

        var pooled = new StageCoverageEvaluation(
                4, 4, 8, 32, 3, true
        ).evaluate(training, validation, System.out);

        pooled.print(System.out);

        System.out.println();
        System.out.println("CROSS-YEAR FALLBACK COMPARISON");
        System.out.println("Only game year is pooled, and only after baseline abstention.");

        compare(
                "UNIT DECISIONS",
                baseline.unitCounts(), pooled.unitCounts(),
                "Eligible",
                "Predicted",
                "Actual in distribution",
                "Actual retained");

        compare(
                "NATIONAL TURNS",
                baseline.nationalCounts(), pooled.nationalCounts(),
                "Eligible",
                "All units predicted",
                "All actual orders in distributions",
                "All actual choices retained",
                "Actual plan retained");

        compare(
                "OPPONENT PERSPECTIVES",
                baseline.opponentCounts(), pooled.opponentCounts(),
                "Eligible",
                "All opponents predicted",
                "All actual orders in distributions",
                "All actual choices retained",
                "All actual national plans retained",
                "Actual scenario retained");

        System.out.printf(
                "%nGenerated scenarios: baseline=%d pooled=%d delta=%+d%n",
                baseline.generatedScenarios(),
                pooled.generatedScenarios(),
                pooled.generatedScenarios() - baseline.generatedScenarios());

        System.out.println();
        System.out.println(
                "Existing qualifying predictions are not replaced.");
        System.out.println(
                "Pooling uses training games only, including their different in-game years.");
        System.out.println(
                "This is a held-out-game experiment, not a chronological deployment test.");
        System.out.println(
                "Reserved test games were not passed to either evaluator.");

    }


    private static void compare(
            String title,
            List<Long> baseline,
            List<Long> pooled,
            String... labels) {

        if (baseline.size() != pooled.size() || baseline.size() != labels.length)
            throw new IllegalStateException("Incompatible diagnostic reports");

        if (!baseline.getFirst().equals(pooled.getFirst()))
            throw new IllegalStateException(
                    "The experiment changed observation eligibility");

        System.out.println();
        System.out.println(title);
        System.out.printf("%-38s %10s %10s %10s%n",
                "Stage", "Baseline", "Pooled", "Delta");

        for (int index = 0; index < labels.length; index++) {

            long before = baseline.get(index);
            long after = pooled.get(index);

            /*
             * This fallback only fills absent predictions.
             * Previously generated candidate sets should remain unchanged.
             */
            if (after < before)
                throw new IllegalStateException(
                        title + ": unexpected regression at " + labels[index]);

            System.out.printf(
                    "%-38s %10d %10d %+10d%n",
                    labels[index], before, after, after - before);

        }

    }

}