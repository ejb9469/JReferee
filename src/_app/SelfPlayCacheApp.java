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

        String usage = "Usage: SelfPlayCacheApp [--workers <positive-int>] "
                + "<new-output> <split-seed> <archive> [archive ...]";
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(usage);
            return;
        }
        int first = 0, workers = 1;
        if (args.length > 0 && args[0].equals("--workers")) {
            if (args.length < 5)
                throw new IllegalArgumentException(usage);
            workers = Integer.parseInt(args[1]);
            first = 2;
        }
        if (args.length - first < 3 || workers < 1 || args[first].startsWith("--"))
            throw new IllegalArgumentException(usage);

        Path output = Path.of(args[first]);
        long seed = Long.parseLong(args[first + 1]);
        var archives = Arrays.stream(args).skip(first + 2L).map(Path::of).toList();

        System.out.println("BEGIN SELF-PLAY CACHE");
        System.out.println("Output: " + output.toAbsolutePath().normalize());
        System.out.println("Replay/cache workers: " + workers);
        System.out.println("Derived data only. No training or TEST access.");
        var report = ValueDatasetJson.parse(
                NeuralSelfPlayCache.write(archives, output, seed, workers, System.out::println));
        System.out.println("Games=" + ValueDatasetJson.count(report, "games")
                + " policy_examples=" + ValueDatasetJson.count(report, "policy_examples")
                + " value_positions=" + ValueDatasetJson.count(report, "value_positions"));
        System.out.println("Dataset fingerprint: "
                + ValueDatasetJson.text(report, "dataset_fingerprint"));
        System.out.println("END SELF-PLAY CACHE");

    }

}