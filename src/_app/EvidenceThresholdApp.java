package _app;

import analysis.CorpusPartition;
import analysis.HeldOutEvaluation;
import analysis.HeldOutReport;
import analysis.RouteCorpus;
import analysis.ScenarioCoverageEvaluation;
import analysis.ScenarioCoverageReport;
import game.record.GameRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public final class EvidenceThresholdApp {

    private static final int MAXIMUM_SUFFIX = 4;
    private static final int CHOICES_PER_UNIT = 4;
    private static final int PLANS_PER_NATION = 8;
    private static final int SCENARIO_LIMIT = 32;

    private static final Path DEFAULT_DATABASE =
            Path.of("data", "diplobn-catalog.sqlite");


    private EvidenceThresholdApp() {  }


    public static void main(String[] args) throws Exception {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: EvidenceThresholdApp [database-path]");

        Path database = (args.length == 0
                ? DEFAULT_DATABASE
                : Path.of(args[0]))
                .toAbsolutePath()
                .normalize();

        System.out.println("Database: " + database);
        System.out.println("Loading corpus read-only...");
        System.out.println();

        // Import once; all thresholds use these same accepted records.
        RouteCorpus corpus = RouteCorpus.load(database);
        corpus.printReport(System.out);

        List<GameRecord> training =
                corpus.games(CorpusPartition.TRAINING);

        List<GameRecord> validation =
                corpus.games(CorpusPartition.VALIDATION);

        if (training.isEmpty() || validation.isEmpty())
            throw new IllegalStateException(
                    "Training and validation must both contain accepted games");

        System.out.println();
        System.out.println("EVIDENCE THRESHOLD VALIDATION");
        System.out.println();
        System.out.println("Maximum route suffix:       " + MAXIMUM_SUFFIX);
        System.out.println("Choices per unit / top-K:   " + CHOICES_PER_UNIT);
        System.out.println("Plans per nation:           " + PLANS_PER_NATION);
        System.out.println("Opponent scenario limit:    " + SCENARIO_LIMIT);
        System.out.println("Minimum observations:       1, 2, 3");
        System.out.println();

        List<Result> results = new ArrayList<>();

        for (long minimum : new long[] {1, 2, 3}) {

            System.out.println(
                    "Evaluating minimum observations = " + minimum + "...");

            HeldOutReport imitation = new HeldOutEvaluation(
                    MAXIMUM_SUFFIX,
                    CHOICES_PER_UNIT,
                    CHOICES_PER_UNIT,
                    PLANS_PER_NATION,
                    minimum
            ).evaluate(training, validation);

            ScenarioCoverageReport scenarios = new ScenarioCoverageEvaluation(
                    MAXIMUM_SUFFIX,
                    CHOICES_PER_UNIT,
                    PLANS_PER_NATION,
                    SCENARIO_LIMIT,
                    minimum
            ).evaluate(training, validation);

            Result result = new Result(minimum, imitation, scenarios);

            if (!results.isEmpty())
                requireSameEligibility(results.getFirst(), result);

            results.add(result);

            // Detailed reports retain exact numerators and denominators.
            System.out.println();
            System.out.println("=== MINIMUM OBSERVATIONS " + minimum + " ===");
            System.out.println();
            System.out.println(imitation);
            System.out.println(scenarios);

        }

        printComparison(results);

        System.out.println();
        System.out.println(
                "Reserved test records were not passed to either evaluator.");
        System.out.println(
                "Results apply only to the accepted replay-compatible corpus.");

    }


    private static void requireSameEligibility(Result first, Result next) {

        HeldOutReport a = first.imitation();
        HeldOutReport b = next.imitation();

        ScenarioCoverageReport x = first.scenarios();
        ScenarioCoverageReport y = next.scenarios();

        if (a.submittedDecisions() != b.submittedDecisions()
                || a.eligibleNationalTurns() != b.eligibleNationalTurns()
                || x.perspectives() != y.perspectives()
                || x.incompleteLabels() != y.incompleteLabels()
                || x.eligiblePerspectives() != y.eligiblePerspectives())
            throw new IllegalStateException(
                    "Changing the evidence threshold changed label eligibility");

        // Prediction and generation coverage are expected to be able to change.
    }


    private static void printComparison(List<Result> results) {

        System.out.println();
        System.out.println("INDIVIDUAL ORDERS");
        System.out.println();

        System.out.printf(
                "%-5s %-12s %-12s %-14s %-12s %-14s%n",
                "Min",
                "Coverage",
                "Top1/all",
                "Top1/covered",
                "Top4/all",
                "Top4/covered");

        for (Result result : results) {

            HeldOutReport report = result.imitation();

            System.out.printf(
                    "%-5d %-12s %-12s %-14s %-12s %-14s%n",
                    result.minimum(),
                    percent(report.predictedDecisions(), report.submittedDecisions()),
                    percent(report.topOneHits(), report.submittedDecisions()),
                    percent(report.topOneHits(), report.predictedDecisions()),
                    percent(report.topKHits(), report.submittedDecisions()),
                    percent(report.topKHits(), report.predictedDecisions()));

        }

        System.out.println();
        System.out.println("NATIONAL CANDIDATE PLANS");
        System.out.println();

        System.out.printf(
                "%-5s %-12s %-12s %-16s%n",
                "Min", "Coverage", "Recall/all", "Recall/covered");

        for (Result result : results) {

            HeldOutReport report = result.imitation();

            System.out.printf(
                    "%-5d %-12s %-12s %-16s%n",
                    result.minimum(),
                    percent(report.generatedNationalTurns(),
                            report.eligibleNationalTurns()),
                    percent(report.nationalPlanHits(),
                            report.eligibleNationalTurns()),
                    percent(report.nationalPlanHits(),
                            report.generatedNationalTurns()));

        }

        System.out.println();
        System.out.println("COMPLETE OPPONENT SCENARIOS");
        System.out.println();

        System.out.printf(
                "%-5s %-12s %-12s %-16s %-14s %-12s%n",
                "Min",
                "Coverage",
                "Recall/all",
                "Recall/covered",
                "Top1/covered",
                "Scenarios");

        for (Result result : results) {

            ScenarioCoverageReport report = result.scenarios();

            System.out.printf(
                    "%-5d %-12s %-12s %-16s %-14s %-12d%n",
                    result.minimum(),
                    percent(report.generatedPerspectives(),
                            report.eligiblePerspectives()),
                    percent(report.scenarioHits(),
                            report.eligiblePerspectives()),
                    percent(report.scenarioHits(),
                            report.generatedPerspectives()),
                    percent(report.topOneHits(),
                            report.generatedPerspectives()),
                    report.generatedScenarios());

        }

        System.out.println();
        System.out.println(
                "'All' uses every eligible observation, including those without predictions.");
        System.out.println(
                "'Covered' uses only observations with predictions or generated candidates.");
        System.out.println(
                "Covered subsets can differ between thresholds; their accuracies "
                        + "are not paired comparisons.");
        System.out.println(
                "National and opponent recall require exact complete-order matches.");

    }

    private static String percent(long numerator, long denominator) {

        if (denominator == 0)
            return "n/a";

        return String.format(
                Locale.ROOT, "%.2f%%", 100.0 * numerator / denominator);

    }

    private record Result(
            long minimum,
            HeldOutReport imitation,
            ScenarioCoverageReport scenarios
    ) {  }

}