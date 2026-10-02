package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.YearPooledSelectionEvaluation;

import java.nio.file.Path;


public final class ReferenceFilteringExperimentApp {

    private ReferenceFilteringExperimentApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: ReferenceFilteringExperimentApp [database-path]");

        Path database = (args.length == 0
                ? Path.of("data", "diplobn-catalog.sqlite")
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        YearPooledSelectionEvaluation.evaluate(
                corpus.games(CorpusPartition.TRAINING),
                corpus.games(CorpusPartition.VALIDATION),
                System.out,
                YearPooledSelectionEvaluation.Experiment.REFERENCE_FILTERING);

    }

}