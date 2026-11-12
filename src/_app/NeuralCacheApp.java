package _app;

import io.training.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Converts a completed movement export to a numeric training cache.
 * All splits are copied without fitting or evaluating a model.
 */
public final class NeuralCacheApp {

    private NeuralCacheApp() { }

    public static void main(String[] args) throws Exception {
        Path datasetPath = Path.of("data", "training-movement-v4");
        Path output = Path.of("data", "movement-neural-cache-v1");
        var defaults = new NeuralMovementCache.Settings();
        int examples = defaults.shardExamples(), options = defaults.shardOptions();
        int cache = JsonlMovementDataset.DEFAULT_TOKEN_CACHE;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) { System.out.println(usage()); return; }
            if (!seen.add(args[index]) || index + 1 >= args.length)
                throw new IllegalArgumentException(usage());
            switch (args[index]) {
                case "--dataset" -> datasetPath = Path.of(args[++index]);
                case "--output" -> output = Path.of(args[++index]);
                case "--shard-examples" -> examples = Integer.parseInt(args[++index]);
                case "--shard-options" -> options = Integer.parseInt(args[++index]);
                case "--token-cache" -> cache = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException(usage());
            }
        }
        var settings = new NeuralMovementCache.Settings(examples, options);
        var dataset = new JsonlMovementDataset(datasetPath, cache);
        System.out.println("BEGIN NEURAL MOVEMENT CACHE");
        System.out.println("Dataset SHA-256=" + dataset.fingerprint());
        System.out.println("Filter=" + dataset.filter().toJson());
        var report = NeuralMovementCache.write(dataset, dataset.filter(), output, settings, split ->
                System.out.printf(Locale.ROOT,
                        "%s: examples=%d slots=%d options=%d shards=%d bytes=%d max_units=%d max_options_per_slot=%d seconds=%.3f read_seconds=%.3f source_sha256=%s%n",
                        split.split(), split.examples(), split.slots(), split.options(), split.shards().size(), split.bytes(),
                        split.maxUnits(), split.maxOptionsPerSlot(), split.elapsedNanos() / 1e9,
                        split.readNanos() / 1e9, split.sourceSha256()));
        System.out.println("Specification SHA-256=" + report.specification().sha256());
        System.out.println("Saved " + output.toAbsolutePath().normalize().resolve("manifest.json"));
        System.out.println("All three splits were converted. No model was trained or evaluated.");
        System.out.println("Keep the source dataset unchanged. Send manifest.json, specification.json and this console output.");
        System.out.println("END NEURAL MOVEMENT CACHE");
    }

    private static String usage() {
        return "Usage: NeuralCacheApp [--dataset <v4-directory>] [--output <new-directory>] "
                + "[--shard-examples <1..1000000>] [--shard-options <1..100000000>] [--token-cache <nonnegative-int>]";
    }

}