package performance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceStatsSelfCheck {

    private PerformanceStatsSelfCheck() { }

    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(-10_000_000L);
        PerformanceStats stats = new PerformanceStats(true, clock::get);

        require(stats.snapshot().isEmpty(), "Initially empty");

        var first = stats.measure("work");
        clock.addAndGet(2_000_000L);
        first.close();

        clock.addAndGet(5_000_000L);
        first.close(); // Must not count twice.

        try (var second = stats.measure("work")) {
            clock.addAndGet(4_000_000L);
        }

        var work = stats.snapshot().getFirst();
        require(work.calls() == 2, "Two calls");
        require(work.totalNanos() == 6_000_000L, "Total");
        require(work.minNanos() == 2_000_000L, "Minimum");
        require(work.maxNanos() == 4_000_000L, "Maximum");
        require(work.averageMillis() == 3.0, "Average and units");

        try (var zero = stats.measure("zero")) {
            // Clock intentionally does not advance.
        }
        require(stats.snapshot().get(1).averageMillis() == 0.0,
                "Zero duration");

        RuntimeException expected = new RuntimeException("expected");

        try {
            try (var failure = stats.measure("exception")) {
                clock.addAndGet(1_000_000L);
                throw expected;
            }
        } catch (RuntimeException actual) {
            require(actual == expected, "Original exception preserved");
        }

        require(stats.snapshot().get(2).totalNanos() == 1_000_000L,
                "Exceptional scope recorded");

        PerformanceStats disabled = new PerformanceStats(false, () -> {
            throw new AssertionError("Disabled timer read the clock");
        });

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(
                bytes, true, StandardCharsets.UTF_8)) {
            try (var ignored = disabled.measure("disabled")) {
                // Disabled scope should be inert.
            }
            disabled.printTo(output);
            require(bytes.size() == 0, "Disabled output is empty");
            require(disabled.snapshot().isEmpty(), "Disabled storage is empty");

            Locale previous = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
                stats.printTo(output);
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, previous);
            }
        }

        String report = bytes.toString(StandardCharsets.UTF_8);
        require(report.contains("3.000"), "Locale-independent decimals");
        require(!report.contains("NaN") && !report.contains("Infinity"),
                "Finite output");

        PerformanceStats fresh = new PerformanceStats(true, clock::get);
        require(fresh.snapshot().isEmpty(), "Runs are independent");

        System.out.println("PerformanceStats self-check passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}