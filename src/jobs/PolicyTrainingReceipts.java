package jobs;

import io.json.JsonAccess;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Checks identities and completed-fit receipts. Python validates tensors and numeric caches.
 */
final class PolicyTrainingReceipts {

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));
    private static final List<String> SPLITS = List.of("TRAIN", "VALIDATION");

    private PolicyTrainingReceipts() { }

    static PolicyTrainingInputs inputs(PolicyTrainingRequest request, Workplace workplace)
            throws IOException, InterruptedException {

        FileVerificationWorker verifier = new FileVerificationWorker();
        verifier.execute(request.initialPolicy(), workplace);
        verifier.execute(request.anchor(), workplace);
        Path parent = request.directory().getParent();
        require(parent != null && Files.isDirectory(parent), "Attempt parent must already exist");
        Path output = parent.toRealPath().resolve(request.directory().getFileName());
        Map<String, Map<String, Object>> datasets = new TreeMap<>();
        Map<String, Long> roots = new LinkedHashMap<>();
        SPLITS.forEach(split -> roots.put(split, 0L));
        for (var pinned : request.cacheManifests()) {
            ReceiptDocument document = read(pinned.file(), workplace);
            require(document.receipt().sha256().equals(pinned.expectedSha256()),
                    "Cache manifest hash differs: " + pinned.file());
            require(!output.startsWith(pinned.file().getParent().toRealPath()),
                    "Keep training output outside input caches");
            Map<String, Object> manifest = document.object();
            equal(manifest, "schema", "jreferee-self-play-cache-v1");
            equal(manifest, "complete", true);
            equal(manifest, "test_opened", false);
            equal(manifest, "byte_order", "LITTLE_ENDIAN");
            equal(manifest, "split_seed", Long.toString(request.splitSeed()));
            String fingerprint = text(manifest, "dataset_fingerprint");
            require(fingerprint.matches("[0-9a-f]{64}"), "Invalid cache fingerprint");
            require(datasets.putIfAbsent(fingerprint, Map.of(
                    "dataset_fingerprint", fingerprint,
                    "cache_manifest_sha256", pinned.expectedSha256())) == null, "Duplicate cache");
            Map<String, Object> splits = object(manifest.get("policy_splits"));
            require(splits.keySet().equals(Set.copyOf(SPLITS)), "Unexpected policy splits");
            long total = 0;
            for (String split : SPLITS) {
                long examples = 0;
                for (Object shard : JSON.array(splits.get(split), split))
                    examples = Math.addExact(examples, count(object(shard), "examples"));
                roots.put(split, Math.addExact(roots.get(split), examples));
                total = Math.addExact(total, examples);
            }
            equal(manifest, "policy_examples", total);
        }
        require(roots.values().stream().allMatch(count -> count > 0),
                "Training requires nonempty TRAIN and VALIDATION; do not reassign games");
        return new PolicyTrainingInputs(List.copyOf(datasets.values()), Map.copyOf(roots));

    }

    static void unchanged(PolicyTrainingInputs expected, PolicyTrainingRequest request, Workplace workplace)
            throws IOException, InterruptedException {
        require(inputs(request, workplace).equals(expected), "Training inputs changed");
    }

    static PolicyTrainingResult result(
            PolicyTrainingInputs inputs, PolicyTrainingRequest request, Workplace workplace
    ) throws IOException, InterruptedException {

        Path directory = request.directory().resolve("model");
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing model output directory");
        ReceiptDocument document = read(directory.resolve("training.json"), workplace);
        Map<String, Object> report = document.object();
        equal(report, "schema", "jreferee-self-play-policy-training-v1");
        equal(report, "model_schema", "jreferee-type-conditioned-gru-v1");
        equal(report, "complete", true);
        for (String key : List.of("anchor_updated", "value_trained", "test_evaluated",
                "promotion_performed", "optimizer_resumed"))
            equal(report, key, false);
        equal(report, "anchor_sha256", request.anchor().expectedSha256());
        equal(object(report.get("initial_policy")), "sha256", request.initialPolicy().expectedSha256());
        equal(report, "self_play_split_seed", Long.toString(request.splitSeed()));
        equal(report, "datasets", inputs.datasets());
        equal(report, "policy_roots", inputs.roots());
        equal(report, "settings", request.settings().values());
        equal(report, "objective", "mean_over_roots(sum_candidate_q_times_negative_sum_conditional_logp)");
        equal(report, "selection", "minimum_validation_root_cross_entropy_including_epoch_zero; strict_improvement");
        Map<String, Object> versions = object(report.get("versions"));
        for (String key : List.of("python", "numpy", "torch"))
            require(!text(versions, key).isBlank(), "Missing runtime version: " + key);

        double initial = validation(report.get("initial_validation"), inputs);
        double best = initial;
        int selected = 0;
        List<Object> epochs = JSON.array(report.get("epochs"), "epochs");
        require(epochs.size() == request.settings().epochs(), "Incomplete epoch history");
        long steps = (inputs.roots().get("TRAIN") - 1) / request.settings().batchSize() + 1;
        for (int index = 0; index < epochs.size(); index++) {
            Map<String, Object> epoch = object(epochs.get(index));
            equal(epoch, "epoch", index + 1L);
            equal(epoch, "roots", inputs.roots().get("TRAIN"));
            equal(epoch, "optimizer_steps", steps);
            double score = validation(epoch.get("validation"), inputs);
            if (score < best) {
                best = score;
                selected = index + 1;
            }
        }
        equal(report, "best_epoch", (long) selected);
        require(number(report, "best_validation_cross_entropy") == best, "Selected score differs");
        equal(report, "improved_over_initial", best < initial);
        double readback = validation(report.get("selected_validation"), inputs);
        require(Math.abs(readback - best) <= Math.max(1e-7, 1e-6 * Math.max(readback, best)),
                "Selected checkpoint readback differs");

        Path checkpoint = directory.resolve("policy.pt");
        require(Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(checkpoint) > 0 && Files.size(checkpoint) <= 64L * 1024 * 1024,
                "Missing or oversized policy checkpoint");
        var verified = new FileVerificationWorker().execute(new FileVerificationRequest(
                checkpoint, text(report, "checkpoint_sha256")), workplace);
        return new PolicyTrainingResult(request.directory(),
                request.directory().resolve("verify/process.log"), request.directory().resolve("fit/process.log"),
                verified, document.receipt(), selected, readback);

    }

    private static double validation(Object value, PolicyTrainingInputs inputs) throws IOException {
        Map<String, Object> metric = object(value);
        equal(metric, "split", "VALIDATION");
        equal(metric, "roots", inputs.roots().get("VALIDATION"));
        return number(metric, "cross_entropy");
    }

    private static ReceiptDocument read(Path path, Workplace workplace) throws IOException, InterruptedException {
        workplace.checkCancelled();
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Not a regular file: " + path);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            require(bytes.length <= 16 * 1024 * 1024, "Metadata exceeds 16 MiB: " + path);
            return new ReceiptDocument(path, bytes);
        }
    }

    private static Map<String, Object> object(Object value) {
        return JSON.object(value, "$");
    }

    private static String text(Map<String, Object> value, String name) {
        return JSON.string(JSON.required(value, name, "$"), "$." + name);
    }

    private static long count(Map<String, Object> value, String name) throws IOException {
        if (value.get(name) instanceof Long number && number >= 0)
            return number;
        throw new IOException("Invalid count: " + name);
    }

    private static double number(Map<String, Object> value, String name) throws IOException {
        if (value.get(name) instanceof Number number
                && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0)
            return number.doubleValue();
        throw new IOException("Invalid measurement: " + name);
    }

    private static void equal(Map<String, Object> value, String name, Object expected) throws IOException {
        require(value.containsKey(name) && Objects.equals(value.get(name), expected), "Unexpected " + name);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition)
            throw new IOException(message);
    }

}