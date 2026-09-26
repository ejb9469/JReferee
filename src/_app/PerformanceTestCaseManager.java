package _app;

import domain.Constants;
import parsing.datc.DATCFileParser;
import parsing.datc.DATCProcessorFileParser;
import performance.PerformanceStats;
import testing.CatalogTestCase;
import testing.DiploBNParserTestCase;

import java.util.Locale;

/**
 * Optional developer entry point with a compact performance footer.
 * Run from the repository root so fixture paths resolve normally.
 */
public final class PerformanceTestCaseManager
        extends TestCaseManager {

    private PerformanceTestCaseManager() {   }

    public static void main(String[] args) {
        PerformanceStats stats = PerformanceStats.forDevelopment();

        try (var wholeRun = stats.measure("Whole run (inclusive)")) {
            System.out.println();
            Constants.printTimestamp();

            TestCaseManager manager = new TestCaseManager(true);

            try (var loading = stats.measure("Load adjudication fixtures")) {
                manager.addAdjudicatorTestCases(
                        new DATCFileParser().parseManyFiles());
            }

            System.out.println("\n----------------------------------------\n");

            try (var loading = stats.measure("Load phase fixtures")) {
                manager.addProcessorTestCases(
                        new DATCProcessorFileParser().parseManyFiles());
            }

            manager.addParserTestCase(new DiploBNParserTestCase());
            manager.addParserTestCase(new CatalogTestCase());

            System.out.println("\n----------------------------------------\n");

            switch (TestCaseManager.MODE) {
                case 0 -> {
                    try (var suite = stats.measure("Justice test suite")) {
                        manager.runJusticeTests();
                    }
                }
                case 1 -> {
                    try (var suite = stats.measure("Judge simulation suite")) {
                        manager.runJudgeSimulationTests();
                    }
                }
                default -> throw new IllegalStateException(
                        "Unsupported test mode: " + TestCaseManager.MODE);
            }

            if (!manager.getProcessorTestCases().isEmpty()) {
                try (var suite = stats.measure("Phase processor suite")) {
                    manager.runProcessorTests();
                }
            }

            if (!manager.getParserTestCases().isEmpty()) {
                try (var suite = stats.measure("Parser/catalog suite")) {
                    manager.runParserTests();
                }
            }

            Constants.printTimestamp();
        } finally {
            // Also reports recorded timings if a suite throws.
            // The original exception continues to propagate.
            stats.printTo(System.err);

            if (stats.isEnabled()) {
                Runtime runtime = Runtime.getRuntime();
                long committed = runtime.totalMemory();
                long used = committed - runtime.freeMemory();

                System.err.printf(
                        Locale.ROOT,
                        "[perf] JVM heap now: %.1f MiB used / %.1f MiB committed"
                                + " (approximate; not allocation volume)%n",
                        used / 1_048_576.0,
                        committed / 1_048_576.0);
            }
        }
    }
}