package _app;

import jobs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static _app.WorkerAppSupport.*;

/**
 * Runs one explicitly configured self-play job,
 *  without retries or seed allocation.
 */
public final class SelfPlayWorkerApp {

    private SelfPlayWorkerApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: SelfPlayWorkerApp <worker.properties>");
        Configuration configuration = read(Path.of(args[0]));
        run(new SelfPlayWorker(configuration.runtime()), configuration.request(), System.out);

    }

    record Configuration(JavaProcessRuntime runtime, SelfPlayRequest request) { }

    static Configuration read(Path file) throws IOException {

        file = file.toAbsolutePath().normalize();
        return read(load(file), file.getParent());

    }

    static Configuration read(Properties properties, Path base) throws IOException {

        Path java = base.resolve(take(properties, "java"));
        int heap = Integer.parseInt(take(properties, "heap_mib"));
        int count = Integer.parseInt(take(properties, "classpath.count"));
        if (count < 1)
            throw new IllegalArgumentException("classpath.count must be positive");
        List<FileVerificationRequest> classPath = new ArrayList<>();
        for (int index = 0; index < count; index++)
            classPath.add(pinned(properties, base, "classpath." + index));
        String valuePath = take(properties, "value.file");
        Optional<FileVerificationRequest> value = valuePath.equals("baseline")
                ? Optional.empty()
                : Optional.of(new FileVerificationRequest(base.resolve(valuePath),
                take(properties, "value.sha256")));
        FileVerificationRequest anchor = pinned(properties, base, "anchor");
        FileVerificationRequest policy = pinned(properties, base, "policy");
        SelfPlayRequest request = new SelfPlayRequest(
                value, anchor, policy,
                Integer.parseInt(take(properties, "last_year")),
                Integer.parseInt(take(properties, "candidates")),
                Integer.parseInt(take(properties, "iterations")),
                Long.parseLong(take(properties, "first_seed")),
                Integer.parseInt(take(properties, "games")),
                Long.parseLong(take(properties, "split_seed")),
                base.resolve(take(properties, "output")));
        finish(properties, request.directory());
        return new Configuration(new JavaProcessRuntime(java, classPath, heap), request);

    }

    static SelfPlayResult run(
            Worker<SelfPlayRequest, SelfPlayResult> worker,
            SelfPlayRequest request, PrintStream output
    ) throws InterruptedException, ExecutionException {

        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        output.println("Attempt: " + request.directory());
        output.println("Seeds: " + request.firstSeed() + ".."
                + Math.addExact(request.firstSeed(), request.games() - 1L));
        output.println("Child log: " + request.directory().resolve("process.log"));
        SelfPlayResult result = WorkerAppSupport.run(
                "self-play-worker-shutdown", worker, request, output);
        output.println("Verified dataset: " + result.datasetFingerprint());
        output.println("Batch manifest SHA-256: " + result.batchManifest().sha256());
        output.println("Cache manifest SHA-256: " + result.cacheManifest().sha256());
        output.println("Completed worker attempt: " + result.directory());
        return result;

    }

}