package _app;

import io.training.NeuralSelfPlayCache;
import io.training.ValueDatasetJson;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Converts replay-validated self-play archives without training a model.
 */
public final class SelfPlayCacheApp {

    private SelfPlayCacheApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length < 3)
            throw new IllegalArgumentException(
                    "Usage: SelfPlayCacheApp <new-output> <split-seed> <archive> [archive ...]");

        Path output = Path.of(args[0]);
        long seed = Long.parseLong(args[1]);
        var archives = Arrays.stream(args).skip(2).map(Path::of).toList();

        System.out.println("BEGIN SELF-PLAY CACHE");
        System.out.println("Output: " + output.toAbsolutePath().normalize());
        System.out.println("Derived data only. No training or TEST access.");
        var report = ValueDatasetJson.parse(
                NeuralSelfPlayCache.write(archives, output, seed, System.out::println));
        System.out.println("Games=" + ValueDatasetJson.count(report, "games")
                + " policy_examples=" + ValueDatasetJson.count(report, "policy_examples")
                + " value_positions=" + ValueDatasetJson.count(report, "value_positions"));
        System.out.println("Dataset fingerprint: "
                + ValueDatasetJson.text(report, "dataset_fingerprint"));
        System.out.println("END SELF-PLAY CACHE");

    }

}