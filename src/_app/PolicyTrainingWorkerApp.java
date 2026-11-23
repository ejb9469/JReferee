package _app;

import jobs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static _app.WorkerAppSupport.*;

/**
 * Runs one explicitly configured CPU policy fit. No automatic admission or promotion.
 */
public final class PolicyTrainingWorkerApp {

    private PolicyTrainingWorkerApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: PolicyTrainingWorkerApp <worker.properties>");
        Configuration configuration = read(Path.of(args[0]));
        run(new PolicyTrainingWorker(configuration.runtime()), configuration.request(), System.out);

    }

    record Configuration(PythonProcessRuntime runtime, PolicyTrainingRequest request) { }

    static Configuration read(Path file) throws IOException {

        file = file.toAbsolutePath().normalize();
        Path base = file.getParent();
        Properties properties = load(file);
        PythonProcessRuntime runtime = new PythonProcessRuntime(
                base.resolve(take(properties, "python")), pinned(properties, base, "sources"));
        int count = Integer.parseInt(take(properties, "cache.count"));
        if (count < 1 || count > 1024)
            throw new IllegalArgumentException("cache.count must be in 1..1024");
        List<FileVerificationRequest> caches = new ArrayList<>();
        for (int index = 0; index < count; index++)
            caches.add(pinned(properties, base, "cache." + index));
        FileVerificationRequest initial = pinned(properties, base, "initial");
        FileVerificationRequest anchor = pinned(properties, base, "anchor");
        long splitSeed = Long.parseLong(take(properties, "split_seed"));
        if (!take(properties, "device").equals("cpu"))
            throw new IllegalArgumentException("This launcher supports only device=cpu");
        PolicyTrainingSettings settings = new PolicyTrainingSettings(
                Integer.parseInt(take(properties, "epochs")),
                Integer.parseInt(take(properties, "batch_size")),
                Integer.parseInt(take(properties, "candidate_batch_size")),
                Integer.parseInt(take(properties, "max_options")),
                Double.parseDouble(take(properties, "learning_rate")),
                Double.parseDouble(take(properties, "weight_decay")),
                Double.parseDouble(take(properties, "gradient_clip")),
                Long.parseLong(take(properties, "seed")),
                Integer.parseInt(take(properties, "threads")));
        PolicyTrainingRequest request = new PolicyTrainingRequest(
                caches, initial, anchor, splitSeed, settings, base.resolve(take(properties, "output")));
        finish(properties, request.directory());
        return new Configuration(runtime, request);

    }

    static PolicyTrainingResult run(
            Worker<PolicyTrainingRequest, PolicyTrainingResult> worker,
            PolicyTrainingRequest request, PrintStream output
    ) throws InterruptedException, ExecutionException {

        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        output.println("Attempt: " + request.directory());
        output.println("Caches: " + request.cacheManifests().size() + "; split seed: " + request.splitSeed());
        output.println("Initial policy: " + request.initialPolicy().file());
        output.println("Device: cpu; threads: " + request.settings().threads()
                + "; training seed: " + request.settings().seed());
        output.println("Verification log: " + request.directory().resolve("verify/process.log"));
        output.println("Training log: " + request.directory().resolve("fit/process.log"));
        PolicyTrainingResult result = WorkerAppSupport.run(
                "policy-training-worker-shutdown", worker, request, output);
        output.println("Checkpoint: " + result.checkpoint().file());
        output.println("Checkpoint SHA-256: " + result.checkpoint().sha256());
        output.println("Training report SHA-256: " + result.trainingReport().sha256());
        output.println("Selected epoch: " + result.selectedEpoch());
        output.println("Validation cross-entropy: " + result.validationCrossEntropy());
        output.println("Completed worker attempt: " + result.directory());
        output.println("No value training, TEST access, export or promotion.");
        return result;

    }

}