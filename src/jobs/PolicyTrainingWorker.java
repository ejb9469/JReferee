package jobs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * One CPU policy fit, preceded by verification. No retry, resume, export or promotion.
 */
public final class PolicyTrainingWorker
        implements Worker<PolicyTrainingRequest, PolicyTrainingResult> {

    private final PythonProcessRuntime runtime;
    private final Worker<PythonProcessRequest, ProcessResult> processWorker;

    public PolicyTrainingWorker(PythonProcessRuntime runtime) {
        this(runtime, new PythonProcessWorker());
    }

    PolicyTrainingWorker(
            PythonProcessRuntime runtime,
            Worker<PythonProcessRequest, ProcessResult> processWorker
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.processWorker = Objects.requireNonNull(processWorker, "processWorker");
    }

    @Override
    public PolicyTrainingResult execute(PolicyTrainingRequest request, Workplace workplace) throws Exception {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workplace, "workplace");
        workplace.checkCancelled();
        if (Files.exists(request.directory(), LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(request.directory().toString());
        PythonProcessWorker.verify(runtime, workplace);
        var inputs = PolicyTrainingReceipts.inputs(request, workplace);
        workplace.checkCancelled();
        Files.createDirectory(request.directory());
        Properties receipt = requestReceipt(request, workplace);
        store(request.directory().resolve("request.properties"), receipt);

        List<String> common = new ArrayList<>(List.of("--cache"));
        request.cacheManifests().forEach(file -> common.add(file.file().getParent().toString()));
        common.addAll(List.of("--initial-policy", request.initialPolicy().file().toString(),
                "--anchor", request.anchor().file().toString()));
        new TreeMap<>(request.settings().values()).forEach((key, value) -> {
            common.add("--" + key.replace('_', '-'));
            common.add(value.toString());
        });
        common.add("-v");

        run("verify", common, List.of("--verify-only"), request, workplace);
        PolicyTrainingReceipts.unchanged(inputs, request, workplace);
        run("fit", common, List.of("--output", request.directory().resolve("model").toString()),
                request, workplace);
        PolicyTrainingReceipts.unchanged(inputs, request, workplace);
        PolicyTrainingResult result = PolicyTrainingReceipts.result(inputs, request, workplace);

        receipt.setProperty("complete", "true");
        receipt.setProperty("checkpoint.sha256", result.checkpoint().sha256());
        receipt.setProperty("training.report.sha256", result.trainingReport().sha256());
        receipt.setProperty("selected.epoch", Integer.toString(result.selectedEpoch()));
        receipt.setProperty("validation.cross_entropy", Double.toString(result.validationCrossEntropy()));
        workplace.checkCancelled();
        Path pending = request.directory().resolve("worker-complete.pending");
        store(pending, receipt);
        Files.move(pending, request.directory().resolve("worker-complete.properties"),
                StandardCopyOption.ATOMIC_MOVE);
        workplace.reportProgress("Verified policy training: " + request.directory());
        return result;

    }

    private void run(
            String stage, List<String> common, List<String> suffix, PolicyTrainingRequest request, Workplace workplace
    ) throws Exception {
        workplace.checkCancelled();
        Path directory = Files.createDirectory(request.directory().resolve(stage));
        List<String> arguments = new ArrayList<>(common);
        arguments.addAll(suffix);
        processWorker.execute(new PythonProcessRequest(
                runtime, "neural.self_play_train", arguments, directory), workplace);
    }

    private Properties requestReceipt(PolicyTrainingRequest request, Workplace workplace) {
        Properties receipt = new Properties();
        receipt.setProperty("schema", "jreferee-policy-training-worker-v1");
        receipt.setProperty("job", workplace.jobId().toString());
        receipt.setProperty("python", runtime.pythonExecutable().toString());
        describe(receipt, "sources", runtime.sources());
        describe(receipt, "initial", request.initialPolicy());
        describe(receipt, "anchor", request.anchor());
        receipt.setProperty("split_seed", Long.toString(request.splitSeed()));
        receipt.setProperty("cache.count", Integer.toString(request.cacheManifests().size()));
        for (int index = 0; index < request.cacheManifests().size(); index++)
            describe(receipt, "cache." + index, request.cacheManifests().get(index));
        request.settings().values().forEach((key, value) -> receipt.setProperty(key, value.toString()));
        return receipt;
    }

    private static void describe(Properties receipt, String name, FileVerificationRequest file) {
        receipt.setProperty(name + ".file", file.file().toString());
        receipt.setProperty(name + ".sha256", file.expectedSha256());
    }

    private static void store(Path path, Properties receipt) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            receipt.store(writer, "Policy training worker");
        }
    }

}