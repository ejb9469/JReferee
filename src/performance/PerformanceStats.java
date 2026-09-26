package performance;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Lightweight, caller-owned timings for development.
 *
 * Single-threaded: create a separate instance for each run/thread.
 * Use stable category names, not a unique name for every invocation.
 * Stores aggregates only; individual measurements are not retained.
 *
 * Timings include exceptional exits. "Calls" means timed attempts,
 * not successful operations. Nested timings are inclusive.
 */
public final class PerformanceStats {


    private static final Scope NOOP = () -> { };

    private final boolean enabled;
    private final LongSupplier clock;
    private final Map<String, Aggregate> measurements = new LinkedHashMap<>();

    public PerformanceStats(boolean enabled) {
        this(enabled, System::nanoTime);
    }

    // Package-private clock injection for deterministic self-checks.
    PerformanceStats(boolean enabled, LongSupplier clock) {
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PerformanceStats forDevelopment() {
        return new PerformanceStats(
                !"false".equalsIgnoreCase(
                        System.getProperty("jreferee.perf", "true")));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Times a scope until its first close().
     * Disabled scopes perform no clock reads or measurement allocations.
     */
    public Scope measure(String name) {
        if (!enabled)
            return NOOP;

        Objects.requireNonNull(name, "name");
        if (name.isBlank())
            throw new IllegalArgumentException("name must not be blank");

        Aggregate aggregate = measurements.computeIfAbsent(
                name, ignored -> new Aggregate());

        return new Timer(aggregate);
    }

    public List<Measurement> snapshot() {
        List<Measurement> result = new ArrayList<>();

        measurements.forEach((name, value) -> {
            if (value.calls > 0) {
                result.add(new Measurement(
                        name, value.calls, value.totalNanos,
                        value.minNanos, value.maxNanos));
            }
        });

        return List.copyOf(result);
    }

    /**
     * Prints only when enabled. Does not close the caller's stream.
     */
    public void printTo(PrintStream output) {
        if (!enabled)
            return;

        Objects.requireNonNull(output, "output");
        List<Measurement> snapshot = snapshot();

        output.println();
        output.println("[perf] Elapsed timings (ms; nested rows overlap)");

        if (snapshot.isEmpty()) {
            output.println("[perf] No completed measurements.");
            return;
        }

        output.printf(
                "%-30s %7s %12s %12s %12s %12s%n",
                "Operation", "Calls", "Total", "Average", "Min", "Max");

        for (Measurement measurement : snapshot) {
            output.printf(
                    Locale.ROOT,
                    "%-30s %7d %12.3f %12.3f %12.3f %12.3f%n",
                    measurement.name(),
                    measurement.calls(),
                    measurement.totalNanos() / 1_000_000.0,
                    measurement.averageMillis(),
                    measurement.minNanos() / 1_000_000.0,
                    measurement.maxNanos() / 1_000_000.0);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record Measurement(
            String name,
            long calls,
            long totalNanos,
            long minNanos,
            long maxNanos
    ) {
        public double averageMillis() {
            return calls == 0 ? 0.0 : totalNanos / (double) calls / 1_000_000.0;
        }
    }

    private static final class Aggregate {
        private long calls;
        private long totalNanos;
        private long minNanos = Long.MAX_VALUE;
        private long maxNanos;

        private void record(long elapsedNanos) {
            calls++;
            totalNanos += elapsedNanos;
            minNanos = Math.min(minNanos, elapsedNanos);
            maxNanos = Math.max(maxNanos, elapsedNanos);
        }
    }

    private final class Timer implements Scope {
        private final Aggregate aggregate;
        private final long started;
        private boolean closed;

        private Timer(Aggregate aggregate) {
            this.aggregate = aggregate;
            this.started = clock.getAsLong();
        }

        @Override
        public void close() {
            if (closed)
                return;

            closed = true;
            aggregate.record(clock.getAsLong() - started);
        }
    }


}