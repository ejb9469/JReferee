package performance;

import io.json.JsonAccess;
import io.json.JsonReader;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks for section isolation, nested timings and report receipts.
 */
public final class PerformanceReportSelfCheck {


    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private PerformanceReportSelfCheck() { }

    public static void main(String[] args) throws Exception {

        AtomicLong clock = new AtomicLong(-100);
        PerformanceStats stats = new PerformanceStats(true, clock::get);
        try (var outer = stats.measure("outer,\"quoted\"")) {
            clock.addAndGet(5);
            try (var inner = stats.measure("inner")) {
                clock.addAndGet(7);
            }
            clock.addAndGet(3);
        }
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("requests", 2L);
        var section = new PerformanceReport.Section("measured", stats.snapshot(), counters);
        counters.put("requests", 100L);
        try (var later = stats.measure("later")) {
            clock.addAndGet(1);
        }
        require(section.timings().size() == 2 && section.counters().get("requests") == 2,
                "Report sections must be detached snapshots");
        require(section.timings().getFirst().totalNanos() == 15
                        && section.timings().get(1).totalNanos() == 7,
                "Nested durations must remain inclusive");
        try {
            section.counters().clear();
            throw new AssertionError("Mutable counter snapshot");
        } catch (UnsupportedOperationException expected) { }

        Path temporary = Files.createTempDirectory("jreferee-performance-report-");
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            Path output = temporary.resolve("report");
            List<PerformanceReport.Section> sections = List.of(
                    new PerformanceReport.Section("warmup", List.of(), Map.of()), section);
            PerformanceReport.write(output, Map.of("repeats", 1), sections);
            var report = JSON.object(JsonReader.read(Files.readString(output.resolve("performance.json"))), "$");
            require(PerformanceReport.SCHEMA.equals(report.get("schema"))
                            && Boolean.TRUE.equals(report.get("complete"))
                            && Boolean.TRUE.equals(report.get("nested_timings_overlap")),
                    "Completion contract is missing");
            var rows = JSON.array(report.get("sections"), "sections");
            var measured = JSON.object(rows.get(1), "measured");
            var measurements = JSON.array(measured.get("timings"), "timings");
            var first = JSON.object(measurements.getFirst(), "measurement");
            require(number(first.get("total_nanos")) == 15 && number(first.get("calls")) == 1,
                    "JSON timing quantities changed");
            String csv = Files.readString(output.resolve("timings.csv"));
            require(csv.contains("\"measured\",\"outer,\"\"quoted\"\"\",1,15,1.5E-5,15,15\n")
                            && Files.readString(output.resolve("work.csv"))
                            .contains("\"measured\",\"requests\",2\n"),
                    "CSV escaping, units or locale differs");
            var files = JSON.object(report.get("files"), "files");
            for (String name : List.of("timings.csv", "work.csv")) {
                var receipt = JSON.object(files.get(name), name);
                byte[] bytes = Files.readAllBytes(output.resolve(name));
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                require(number(receipt.get("bytes")) == bytes.length && hash.equals(receipt.get("sha256")),
                        "Published receipt differs for " + name);
            }
            byte[] original = Files.readAllBytes(output.resolve("performance.json"));
            try {
                PerformanceReport.write(output, Map.of(), sections);
                throw new AssertionError("Existing output was replaced");
            } catch (FileAlreadyExistsException expected) { }
            require(Arrays.equals(original, Files.readAllBytes(output.resolve("performance.json"))),
                    "Existing completion receipt changed");
            Path rejected = temporary.resolve("rejected");
            try {
                PerformanceReport.write(rejected, Map.of(), List.of(section, section));
                throw new AssertionError("Duplicate sections were accepted");
            } catch (IllegalArgumentException expected) { }
            require(!Files.exists(rejected), "Invalid report left output behind");
            try {
                PerformanceReport.write(rejected, Map.of("invalid", Double.NaN), sections);
                throw new AssertionError("Nonfinite context was accepted");
            } catch (IllegalArgumentException expected) { }
            require(!Files.exists(rejected), "Invalid JSON left a completion receipt");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
        System.out.println("PerformanceReportSelfCheck passed.");

    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}