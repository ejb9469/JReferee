package analysis;

import game.record.GameRecord;

import java.io.PrintStream;
import java.util.*;


public class ScenarioWidthComparison {

    private final int maximumSuffix;
    private final int choicesPerUnit;
    private final int plansPerNation;
    private final long minimumObservations;


    public ScenarioWidthComparison() {
        this(4, 4, 8, 3);
    }

    public ScenarioWidthComparison(
            int maximumSuffix, int choicesPerUnit,
            int plansPerNation, long minimumObservations) {

        if (maximumSuffix < 0)
            throw new IllegalArgumentException("Maximum suffix must not be negative");

        if (choicesPerUnit < 1 || plansPerNation < 1 || minimumObservations < 1)
            throw new IllegalArgumentException("Search and evidence limits must be positive");

        this.maximumSuffix = maximumSuffix;
        this.choicesPerUnit = choicesPerUnit;
        this.plansPerNation = plansPerNation;
        this.minimumObservations = minimumObservations;

    }


    public NavigableMap<Integer, ScenarioCoverageReport> compare(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            int... scenarioLimits) {

        List<GameRecord> training = List.copyOf(
                Objects.requireNonNull(trainingGames, "trainingGames"));
        List<GameRecord> validation = List.copyOf(
                Objects.requireNonNull(validationGames, "validationGames"));

        Objects.requireNonNull(scenarioLimits, "scenarioLimits");

        if (training.isEmpty() || validation.isEmpty())
            throw new IllegalArgumentException("Training and validation games must not be empty");

        if (scenarioLimits.length == 0)
            throw new IllegalArgumentException("Supply at least one scenario limit");

        Set<Integer> limits = new TreeSet<>();

        for (int limit : scenarioLimits) {

            if (limit < 1)
                throw new IllegalArgumentException("Scenario limits must be positive");

            if (!limits.add(limit))
                throw new IllegalArgumentException("Repeated scenario limit: " + limit);

        }

        NavigableMap<Integer, ScenarioCoverageReport> reports = new TreeMap<>();
        ScenarioCoverageReport baseline = null;

        for (int limit : limits) {

            ScenarioCoverageEvaluation evaluator = new ScenarioCoverageEvaluation(
                    maximumSuffix,
                    choicesPerUnit,
                    plansPerNation,
                    limit,
                    minimumObservations);

            // Fresh training per run avoids carrying evaluation state between widths.
            ScenarioCoverageReport report = evaluator.evaluate(training, validation);

            if (baseline == null)
                baseline = report;
            else
                requireSameCoverage(baseline, report);

            reports.put(limit, report);

        }

        return Collections.unmodifiableNavigableMap(reports);

    }

    private static void requireSameCoverage(
            ScenarioCoverageReport first, ScenarioCoverageReport second) {

        if (first.perspectives() != second.perspectives()
                || first.incompleteLabels() != second.incompleteLabels()
                || first.eligiblePerspectives() != second.eligiblePerspectives()
                || first.generatedPerspectives() != second.generatedPerspectives())
            throw new IllegalStateException(
                    "Changing scenario width unexpectedly changed eligibility or evidence coverage");

    }


    // Reports use the same denominators at every width. \\

    public void printReport(
            NavigableMap<Integer, ScenarioCoverageReport> reports,
            PrintStream output) {

        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(output, "output");

        if (reports.isEmpty())
            throw new IllegalArgumentException("No comparison results");

        ScenarioCoverageReport first = reports.firstEntry().getValue();

        output.println("SCENARIO WIDTH VALIDATION");
        output.println();

        output.printf("Training games:             %d%n", first.trainingGames());
        output.printf("Validation games:           %d%n", first.testGames());
        output.printf("Choices per unit:           %d%n", choicesPerUnit);
        output.printf("Plans per opponent nation:  %d%n", plansPerNation);
        output.printf("Minimum observations:       %d%n", minimumObservations);
        output.printf("Eligible perspectives:      %d%n", first.eligiblePerspectives());
        output.printf("Perspectives with evidence: %d%n", first.generatedPerspectives());
        output.printf("Insufficient evidence:      %d%n", first.insufficientEvidence());
        output.printf("Incomplete opponent labels: %d%n", first.incompleteLabels());
        output.println();

        output.printf("%-7s %-15s %-11s %-11s %-11s %-11s%n",
                "Limit", "Scenarios", "Mean size", "Recall", "Top-1", "New hits");

        ScenarioCoverageReport previous = null;

        for (Map.Entry<Integer, ScenarioCoverageReport> entry : reports.entrySet()) {

            ScenarioCoverageReport report = entry.getValue();
            requireSameCoverage(first, report);

            String gain = previous == null ? "-"
                    : String.format(Locale.ROOT, "%+d",
                    report.scenarioHits() - previous.scenarioHits());

            output.printf(Locale.ROOT, "%-7d %-15d %-11s %-11s %-11s %-11s%n",
                    entry.getKey(),
                    report.generatedScenarios(),
                    meanSize(report),
                    percentage(report.scenarioHits(), report.eligiblePerspectives()),
                    percentage(report.topOneHits(), report.eligiblePerspectives()),
                    gain);

            previous = report;

        }

        output.println();
        output.println("Recall includes eligible perspectives with insufficient evidence.");
        output.println("New hits are relative to the preceding width.");
        output.println("Scenarios are candidate sets, not calibrated probability samples.");

    }

    private static String meanSize(ScenarioCoverageReport report) {

        if (report.generatedPerspectives() == 0)
            return "n/a";

        return String.format(Locale.ROOT, "%.2f",
                (double) report.generatedScenarios() / report.generatedPerspectives());

    }

    private static String percentage(long numerator, long denominator) {

        if (denominator == 0)
            return "n/a";

        return String.format(Locale.ROOT, "%.2f%%", 100.0 * numerator / denominator);

    }

}