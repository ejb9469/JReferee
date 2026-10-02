package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.StageCoverageEvaluation;

import java.nio.file.Path;
import java.util.List;


public final class OccupancyRelaxationExperimentApp {

    private OccupancyRelaxationExperimentApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: OccupancyRelaxationExperimentApp [database-path]");

        Path database = (args.length == 0
                ? Path.of("data", "diplobn-catalog.sqlite")
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        var training = corpus.games(CorpusPartition.TRAINING);
        var validation = corpus.games(CorpusPartition.VALIDATION);

        System.out.println();
        System.out.println("=== REFERENCE: YEAR-POOLED FALLBACK ===");

        var reference = new StageCoverageEvaluation(
                4, 4, 8, 32, 3, true, false
        ).evaluate(training, validation, System.out);

        reference.print(System.out);

        System.out.println();
        System.out.println("=== EXPERIMENT: ADD OCCUPANCY-RELAXED FALLBACK ===");

        var relaxed = new StageCoverageEvaluation(
                4, 4, 8, 32, 3, true, true
        ).evaluate(training, validation, System.out);

        relaxed.print(System.out);

        compare("UNIT",
                reference.unitCounts(), relaxed.unitCounts());

        compare("NATION",
                reference.nationalCounts(), relaxed.nationalCounts());

        compare("OPPONENT",
                reference.opponentCounts(), relaxed.opponentCounts());

        System.out.printf(
                "%nScenarios: reference=%d relaxed=%d delta=%+d%n",
                reference.generatedScenarios(),
                relaxed.generatedScenarios(),
                relaxed.generatedScenarios() - reference.generatedScenarios());

        System.out.println();
        System.out.println("Stage order is the same as the full reports.");
        System.out.println("Reference predictions are preserved; only abstentions gain a fallback.");
        System.out.println("Candidate-reference diagnostics do not filter or establish legality.");
        System.out.println("Reserved test games were not passed to either evaluator.");

    }

    private static void compare(
            String name,
            List<Long> reference,
            List<Long> relaxed) {

        if (reference.size() != relaxed.size()
                || !reference.getFirst().equals(relaxed.getFirst()))
            throw new IllegalStateException("Changed evaluation eligibility");

        System.out.println();
        System.out.println(name + " COMPARISON");
        System.out.printf("%-8s %12s %12s %12s%n",
                "Stage", "Year-pooled", "Relaxed", "Delta");

        for (int index = 0; index < reference.size(); index++) {

            long before = reference.get(index);
            long after = relaxed.get(index);

            if (after < before)
                throw new IllegalStateException(
                        "Fallback-only experiment regressed at "
                                + name + " stage " + index);

            System.out.printf("%-8d %12d %12d %+12d%n",
                    index, before, after, after - before);

        }

    }

}