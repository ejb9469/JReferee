package _app;

import analysis.CorpusPartition;
import analysis.RouteCorpus;
import analysis.ScenarioWidthComparison;
import game.record.GameRecord;

import java.nio.file.Path;
import java.util.List;


public final class RouteCorpusApp {

    private static final Path DEFAULT_DATABASE =
            Path.of("data", "diplobn-catalog.sqlite");


    private RouteCorpusApp() {  }


    public static void main(String[] args) throws Exception {

        Path database = DEFAULT_DATABASE;
        boolean validate = false;
        boolean diagnostics = false;

        boolean databaseSpecified = false;

        for (int index = 0; index < args.length; index++) {

            switch (args[index]) {

                case "--database" -> {

                    if (databaseSpecified
                            || index + 1 >= args.length
                            || args[index + 1].isBlank()
                            || args[index + 1].startsWith("--"))
                        throw usage();

                    database = Path.of(args[++index]);
                    databaseSpecified = true;

                }

                case "--validate" -> {

                    if (validate)
                        throw usage();

                    validate = true;

                }

                case "--diagnostics" -> {

                    if (diagnostics)
                        throw usage();

                    diagnostics = true;

                }

                default -> throw usage();

            }

        }

        database = database.toAbsolutePath().normalize();

        System.out.println("Database: " + database);
        System.out.println("Opening read-only; replaying source histories...");
        System.out.println();

        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        if (diagnostics && !corpus.diagnostics().isEmpty()) {

            System.out.println();
            System.out.println("REJECTION DETAILS");

            long retainedNotes = 0;

            for (String diagnostic : corpus.diagnostics()) {

                if (diagnostic.startsWith("IMPORT_NOTE ")) {
                    retainedNotes++;
                    continue;
                }

                System.out.println(diagnostic);

            }

            System.out.println();
            System.out.println(
                    "Accepted-game provenance notes retained but not printed: "
                            + retainedNotes);

        }

        if (!validate) {

            System.out.println();
            System.out.println("Import inspection complete.");
            System.out.println("Add --validate to run scenario-width comparisons.");

            return;

        }

        List<GameRecord> training = corpus.games(CorpusPartition.TRAINING);
        List<GameRecord> validation = corpus.games(CorpusPartition.VALIDATION);

        if (training.isEmpty() || validation.isEmpty()) {

            System.out.println();
            System.out.println(
                    "Validation not run: training and validation must both contain accepted games.");
            System.out.println(
                    "Do not move reserved test games into validation merely to fill the split.");

            return;

        }

        System.out.println();
        System.out.println("Running validation at scenario limits 8, 32, and 128...");
        System.out.println();

        ScenarioWidthComparison comparison =
                new ScenarioWidthComparison(4, 4, 8, 3);

        var reports = comparison.compare(training, validation, 8, 32, 128);

        comparison.printReport(reports, System.out);

        System.out.println();
        System.out.println("Reserved test records were not used for training or evaluation.");

    }

    private static IllegalArgumentException usage() {

        return new IllegalArgumentException(
                "Usage: RouteCorpusApp [--database <path>] [--diagnostics] [--validate]");

    }

}