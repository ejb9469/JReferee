package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.StageCoverageEvaluation;

import java.nio.file.Path;


public final class StageCoverageApp {

    private StageCoverageApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: StageCoverageApp [database-path]");

        Path database = (args.length == 0
                ? Path.of("data", "diplobn-catalog.sqlite")
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        StageCoverageEvaluation evaluation =
                new StageCoverageEvaluation(4, 4, 8, 32, 3);

        var report = evaluation.evaluate(
                corpus.games(CorpusPartition.TRAINING),
                corpus.games(CorpusPartition.VALIDATION),
                System.out);

        report.print(System.out);

        System.out.println();
        System.out.println("Reserved test records were not passed to the evaluator.");
        System.out.println("Results apply to the accepted replay-compatible corpus only.");

    }

}