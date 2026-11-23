package jobs;

import io.training.SelfPlayFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

import static io.training.SelfPlayFiles.object;

/**
 * Collection metadata binds already-verified worker results to the fixed plan.
 * It does not replace worker payload checks or the later training preflight.
 */
final class SelfPlayCollectionFiles {

    static final String SCHEMA = "jreferee-self-play-collection-v1";

    private SelfPlayCollectionFiles() { }

    static void inputs(SelfPlayCollectionPlan plan, Workplace context) throws IOException, InterruptedException {
        context.checkCancelled();
        if (!Files.isRegularFile(plan.runtime().javaExecutable()))
            throw new NoSuchFileException("Java executable is missing: " + plan.runtime().javaExecutable());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
            if (!System.getenv().getOrDefault(variable, "").isBlank())
                throw new IOException("Unset " + variable + "; worker JVM options must be explicit");
        FileVerificationWorker verifier = new FileVerificationWorker();
        for (var file : plan.runtime().classPath()) {
            verifier.execute(file, context);
            try (JarFile jar = new JarFile(file.file().toFile())) {
                var manifest = jar.getManifest();
                if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null)
                    throw new IOException("Declare all runtime JARs explicitly; manifest Class-Path is unsupported");
            }
        }
        SelfPlayRequest template = plan.template();
        if (template.value().isPresent())
            verifier.execute(template.value().orElseThrow(), context);
        verifier.execute(template.anchor(), context);
        verifier.execute(template.policy(), context);
    }

    static Map<String, Object> plan(SelfPlayCollectionPlan plan) {
        SelfPlayRequest request = plan.template();
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (int index = 0; index < plan.batches(); index++)
            schedule.add(scheduled(plan, index));
        return object("schema", SCHEMA, "initial_position", "STANDARD_SPRING_1901",
                "batch_count", plan.batches(), "games", plan.games(), "parallelism", plan.parallelism(),
                "runtime", object("java", plan.runtime().javaExecutable().toString(),
                        "heap_mib", plan.runtime().heapMiB(),
                        "classpath", plan.runtime().classPath().stream().map(SelfPlayCollectionFiles::pin).toList()),
                "settings", object("value", request.value().map(SelfPlayCollectionFiles::pin).orElse(null),
                        "anchor", pin(request.anchor()), "policy", pin(request.policy()),
                        "last_year", request.lastYear(), "candidates", request.candidates(),
                        "iterations", request.iterations(), "split_seed", Long.toString(request.splitSeed())),
                "schedule", schedule);
    }

    static Map<String, Object> completed(
            SelfPlayCollectionPlan plan, int index, UUID job, SelfPlayResult result,
            List<FileVerificationResult> receipts, Workplace context
    ) throws IOException, InterruptedException {

        context.checkCancelled();
        SelfPlayRequest request = plan.batch(index);
        if (result == null || !request.directory().equals(result.directory())
                || !request.directory().resolve("process.log").equals(result.log())
                || result.datasetFingerprint() == null || !result.datasetFingerprint().matches("[0-9a-f]{64}"))
            throw new IOException("Unexpected result for batch " + index);
        FileVerificationResult batch = expected(result.batchManifest(), request.directory().resolve("batch/manifest.json"));
        FileVerificationResult cache = expected(result.cacheManifest(), request.directory().resolve("batch/cache/manifest.json"));
        verify(batch, context);
        verify(cache, context);
        ReceiptDocument worker = read(request.directory().resolve("worker-complete.properties"));
        Properties properties = new Properties();
        try (Reader reader = new StringReader(worker.text())) {
            properties.load(reader);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("schema", "jreferee-self-play-worker-v1");
        expected.put("complete", "true");
        expected.put("job", job.toString());
        expected.put("java", plan.runtime().javaExecutable().toString());
        expected.put("heap_mib", Integer.toString(plan.runtime().heapMiB()));
        expected.put("last_year", Integer.toString(request.lastYear()));
        expected.put("candidates", Integer.toString(request.candidates()));
        expected.put("iterations", Integer.toString(request.iterations()));
        expected.put("first_seed", Long.toString(request.firstSeed()));
        expected.put("games", Integer.toString(request.games()));
        expected.put("split_seed", Long.toString(request.splitSeed()));
        expected.put("dataset", result.datasetFingerprint());
        expected.put("batch.manifest.sha256", batch.sha256());
        expected.put("cache.manifest.sha256", cache.sha256());
        for (int item = 0; item < plan.runtime().classPath().size(); item++)
            pinned(expected, "classpath." + item, plan.runtime().classPath().get(item));
        if (request.value().isPresent())
            pinned(expected, "value", request.value().orElseThrow());
        else
            expected.put("value", "baseline");
        pinned(expected, "anchor", request.anchor());
        pinned(expected, "policy", request.policy());
        if (!properties.equals(expected))
            throw new IOException("Worker completion receipt differs for batch " + index);
        for (var file : List.of(batch, cache)) {
            Map<String, Object> document = read(file.file()).object();
            if (!Boolean.TRUE.equals(document.get("complete"))
                    || !result.datasetFingerprint().equals(document.get("dataset_fingerprint")))
                throw new IOException("Batch dataset identity differs: " + file.file());
        }
        receipts.addAll(List.of(batch, cache, worker.receipt()));
        Map<String, Object> row = scheduled(plan, index);
        row.putAll(object("job", job.toString(), "dataset_fingerprint", result.datasetFingerprint(),
                "files", object("worker-complete.properties", receipt(worker.receipt()),
                        "batch/manifest.json", receipt(batch), "batch/cache/manifest.json", receipt(cache))));
        return row;

    }

    private static Map<String, Object> scheduled(SelfPlayCollectionPlan plan, int index) {
        SelfPlayRequest request = plan.batch(index);
        return object("batch", index, "directory", request.directory().getFileName().toString(),
                "games", request.games(), "first_game_seed", Long.toString(request.firstSeed()),
                "last_game_seed", Long.toString(Math.addExact(request.firstSeed(), request.games() - 1L)));
    }

    private static Map<String, Object> pin(FileVerificationRequest file) {
        return object("file", file.file().toString(), "sha256", file.expectedSha256());
    }

    private static void pinned(Map<String, String> values, String prefix, FileVerificationRequest file) {
        values.put(prefix + ".file", file.file().toString());
        values.put(prefix + ".sha256", file.expectedSha256());
    }

    static Map<String, Object> receipt(FileVerificationResult file) {
        return object("bytes", file.bytes(), "sha256", file.sha256());
    }

    static FileVerificationResult write(Path file, Map<String, Object> value) throws IOException {
        byte[] bytes = (SelfPlayFiles.json(value) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        return new ReceiptDocument(file, bytes).receipt();
    }

    static void verify(FileVerificationResult expected, Workplace context) throws IOException, InterruptedException {
        var actual = new FileVerificationWorker().execute(
                new FileVerificationRequest(expected.file(), expected.sha256()), context);
        if (actual.bytes() != expected.bytes())
            throw new IOException("Receipt size differs: " + expected.file());
    }

    private static FileVerificationResult expected(FileVerificationResult receipt, Path file) throws IOException {
        if (receipt == null || !receipt.file().equals(file.toRealPath()))
            throw new IOException("Unexpected result receipt path: " + file);
        return receipt;
    }

    private static ReceiptDocument read(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024)
                throw new IOException("Metadata exceeds 16 MiB: " + file);
            return new ReceiptDocument(file, bytes);
        }
    }

}