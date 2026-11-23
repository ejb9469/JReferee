package jobs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * One fixed-model batch in an isolated JVM. No retry, resume or seed allocation.
 */
public final class SelfPlayWorker implements Worker<SelfPlayRequest, SelfPlayResult> {

    private final JavaProcessRuntime runtime;
    private final Worker<JavaProcessRequest, ProcessResult> processWorker;

    public SelfPlayWorker(JavaProcessRuntime runtime) {
        this(runtime, new JavaProcessWorker());
    }

    SelfPlayWorker(
            JavaProcessRuntime runtime,
            Worker<JavaProcessRequest, ProcessResult> processWorker
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.processWorker = Objects.requireNonNull(processWorker, "processWorker");
    }

    @Override
    public SelfPlayResult execute(SelfPlayRequest request, Workplace workplace) throws Exception {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workplace, "workplace");
        workplace.checkCancelled();
        if (Files.exists(request.directory(), LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(request.directory().toString());
        verifyModels(request, workplace);
        workplace.checkCancelled();
        Files.createDirectory(request.directory());
        Path batch = request.directory().resolve("batch");
        List<String> arguments = List.of(
                request.value().map(file -> file.file().toString()).orElse("baseline"),
                request.anchor().file().toString(), request.policy().file().toString(),
                Integer.toString(request.lastYear()), Integer.toString(request.candidates()),
                Integer.toString(request.iterations()), Long.toString(request.firstSeed()),
                Integer.toString(request.games()), Long.toString(request.splitSeed()), batch.toString());
        Properties receipt = requestReceipt(request, workplace);
        store(request.directory().resolve("request.properties"), receipt);
        processWorker.execute(new JavaProcessRequest(
                runtime, "_app.SelfPlayBatchApp", arguments, request.directory()), workplace);

        workplace.checkCancelled();
        verifyModels(request, workplace);
        SelfPlayResult result = SelfPlayReceipts.verify(request, workplace);
        receipt.setProperty("dataset", result.datasetFingerprint());
        receipt.setProperty("batch.manifest.sha256", result.batchManifest().sha256());
        receipt.setProperty("cache.manifest.sha256", result.cacheManifest().sha256());
        receipt.setProperty("complete", "true");
        workplace.checkCancelled();
        Path pending = request.directory().resolve("worker-complete.pending");
        store(pending, receipt);
        Files.move(pending, request.directory().resolve("worker-complete.properties"),
                StandardCopyOption.ATOMIC_MOVE);
        workplace.reportProgress("Verified batch: " + batch);
        return result;

    }

    private static void verifyModels(SelfPlayRequest request, Workplace workplace)
            throws IOException, InterruptedException {
        FileVerificationWorker verifier = new FileVerificationWorker();
        if (request.value().isPresent())
            verifier.execute(request.value().orElseThrow(), workplace);
        verifier.execute(request.anchor(), workplace);
        verifier.execute(request.policy(), workplace);
    }

    private Properties requestReceipt(SelfPlayRequest request, Workplace workplace) {

        Properties receipt = new Properties();
        receipt.setProperty("schema", "jreferee-self-play-worker-v1");
        receipt.setProperty("job", workplace.jobId().toString());
        receipt.setProperty("java", runtime.javaExecutable().toString());
        receipt.setProperty("heap_mib", Integer.toString(runtime.heapMiB()));
        for (int index = 0; index < runtime.classPath().size(); index++)
            describe(receipt, "classpath." + index, runtime.classPath().get(index));
        request.value().ifPresentOrElse(file -> describe(receipt, "value", file),
                () -> receipt.setProperty("value", "baseline"));
        describe(receipt, "anchor", request.anchor());
        describe(receipt, "policy", request.policy());
        receipt.setProperty("last_year", Integer.toString(request.lastYear()));
        receipt.setProperty("candidates", Integer.toString(request.candidates()));
        receipt.setProperty("iterations", Integer.toString(request.iterations()));
        receipt.setProperty("first_seed", Long.toString(request.firstSeed()));
        receipt.setProperty("games", Integer.toString(request.games()));
        receipt.setProperty("split_seed", Long.toString(request.splitSeed()));
        return receipt;

    }

    private static void describe(Properties receipt, String name, FileVerificationRequest file) {
        receipt.setProperty(name + ".file", file.file().toString());
        receipt.setProperty(name + ".sha256", file.expectedSha256());
    }

    private static void store(Path path, Properties receipt) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            receipt.store(writer, "Self-play worker");
        }
    }

}