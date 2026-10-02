package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.YearPooledSelectionEvaluation;

import java.nio.file.Path;


public final class YearPooledSelectionExperimentApp {

    private YearPooledSelectionExperimentApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: YearPooledSelectionExperimentApp [database-path]");

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
                System.out);

    }

}