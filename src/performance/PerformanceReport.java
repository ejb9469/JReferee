package performance;

import io.training.SelfPlayFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static io.training.SelfPlayFiles.object;

/**
 * Immutable timing sections and new-directory JSON/CSV publication.
 * The completion receipt is published last; no existing output is replaced.
 */
public class PerformanceReport {


    public static final String SCHEMA = "jreferee-agent-performance-v1";

    private PerformanceReport() { }

    public record Section(
            String name, List<PerformanceStats.Measurement> timings,
            Map<String, Long> counters
    ) {
        public Section {
            Objects.requireNonNull(name, "name");
            if (name.isBlank())
                throw new IllegalArgumentException("Section name must not be blank");
            timings = List.copyOf(timings);
            counters = Collections.unmodifiableMap(new TreeMap<>(counters));
            for (var entry : counters.entrySet())
                if (entry.getKey().isBlank() || entry.getValue() < 0)
                    throw new IllegalArgumentException("Invalid work counter");
        }
    }

    public static void write(
            Path directory, Map<String, Object> context, List<Section> sections
    ) throws IOException {

        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(context, "context");
        sections = List.copyOf(sections);
        Set<String> names = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder timings = new StringBuilder(
                "section,operation,calls,total_nanos,mean_millis,min_nanos,max_nanos\n");
        StringBuilder work = new StringBuilder("section,counter,value\n");

        for (Section section : sections) {
            if (!names.add(section.name()))
                throw new IllegalArgumentException("Duplicate section: " + section.name());
            List<Map<String, Object>> measurements = new ArrayList<>();
            for (var measurement : section.timings()) {
                measurements.add(object(
                        "operation", measurement.name(), "calls", measurement.calls(),
                        "total_nanos", measurement.totalNanos(),
                        "mean_millis", measurement.averageMillis(),
                        "min_nanos", measurement.minNanos(), "max_nanos", measurement.maxNanos()));
                timings.append(quote(section.name())).append(',').append(quote(measurement.name()))
                        .append(',').append(measurement.calls()).append(',').append(measurement.totalNanos())
                        .append(',').append(Double.toString(measurement.averageMillis()))
                        .append(',').append(measurement.minNanos()).append(',')
                        .append(measurement.maxNanos()).append('\n');
            }
            section.counters().forEach((name, value) -> work.append(quote(section.name()))
                    .append(',').append(quote(name)).append(',').append(value).append('\n'));
            rows.add(object("section", section.name(), "timings", measurements,
                    "counters", section.counters()));
        }

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put("timings.csv", timings.toString().getBytes(StandardCharsets.UTF_8));
        payloads.put("work.csv", work.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> receipts = new LinkedHashMap<>();
        payloads.forEach((name, bytes) -> receipts.put(name,
                object("bytes", bytes.length, "sha256", hash(bytes))));
        String report = SelfPlayFiles.json(object(
                "schema", SCHEMA, "complete", true,
                "timing_contract", "ELAPSED_INCLUSIVE_NANOSECONDS",
                "nested_timings_overlap", true, "counts_include_failed_attempts", true,
                "context", context, "sections", rows, "files", receipts));

        Files.createDirectory(directory);
        for (var entry : payloads.entrySet())
            Files.write(directory.resolve(entry.getKey()), entry.getValue(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.writeString(directory.resolve("performance.pending"), report, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(directory.resolve("performance.pending"), directory.resolve("performance.json"),
                StandardCopyOption.ATOMIC_MOVE);

    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String hash(byte[] bytes) {

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }

    }

}