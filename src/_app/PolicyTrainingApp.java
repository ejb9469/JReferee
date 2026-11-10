package _app;

import agents.policy.LinearMovementPolicy;
import io.training.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Trains the small movement baseline without changing an agent's defaults.
 */
public final class PolicyTrainingApp {

    private PolicyTrainingApp() { }

    public static void main(String[] args) throws Exception {
        Path datasetPath = Path.of("data", "training-movement-v4");
        Path output = Path.of("data", "movement-linear-v1");
        var defaults = new PolicyTraining.Settings();
        int epochs = defaults.epochs(), dimensions = defaults.dimensions(), buffer = defaults.shuffleBuffer();
        int cache = JsonlMovementDataset.DEFAULT_TOKEN_CACHE;
        long seed = defaults.shuffleSeed();
        double rate = defaults.learningRate();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) { System.out.println(usage()); return; }
            if (!seen.add(args[index]) || index + 1 >= args.length) throw new IllegalArgumentException(usage());
            switch (args[index]) {
                case "--dataset" -> datasetPath = Path.of(args[++index]);
                case "--output" -> output = Path.of(args[++index]);
                case "--epochs" -> epochs = Integer.parseInt(args[++index]);
                case "--dimensions" -> dimensions = Integer.parseInt(args[++index]);
                case "--learning-rate" -> rate = Double.parseDouble(args[++index]);
                case "--shuffle-buffer" -> buffer = Integer.parseInt(args[++index]);
                case "--shuffle-seed" -> seed = Long.parseLong(args[++index]);
                case "--token-cache" -> cache = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException(usage());
            }
        }
        var settings = new PolicyTraining.Settings(epochs, dimensions, rate, buffer, seed);
        var dataset = new JsonlMovementDataset(datasetPath, cache);
        if (Files.exists(output)) throw new IllegalArgumentException("Output directory already exists: " + output);
        Files.createDirectory(output);
        var result = PolicyTraining.fit(dataset, settings, epoch -> System.out.printf(Locale.ROOT,
                "Epoch %d: online NLL %.6f; validation NLL %.6f; order accuracy %.4f%n",
                epoch.number(), epoch.onlineOrderNll(), epoch.validation().overall().orderNll(),
                (double) epoch.validation().overall().correctOrders() / epoch.validation().overall().orders()));
        Path model = output.resolve("policy.bin");
        result.model().save(model);
        LinearMovementPolicy.load(model);
        String modelHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model)));
        String report = "{\"schema\":\"jreferee-policy-training-v1\",\"complete\":true,\"model_schema\":\""
                + LinearMovementPolicy.SCHEMA + "\",\"features\":\"" + LinearMovementPolicy.FEATURES
                + "\",\"model_sha256\":\"" + modelHash
                + "\",\"dataset_index_sha256\":\"" + dataset.fingerprint() + "\",\"filter\":" + dataset.filter().toJson()
                + ",\"settings\":" + settings.toJson() + ",\"token_cache\":" + cache
                + ",\"test_evaluated\":false,\"training\":" + result.toJson() + "}\n";
        Files.writeString(output.resolve("training.json"), report, StandardOpenOption.CREATE_NEW);
        System.out.println("Saved validation-selected epoch " + result.bestEpoch() + " to " + model.toAbsolutePath());
        if (result.bestValidation().overall().orderNll() >= result.initialValidation().overall().orderNll())
            System.out.println("WARNING: the trained model did not beat the uniform-mask validation NLL.");
        System.out.println("Test was not evaluated. Keep training.json and this console output.");
    }

    private static String usage() {
        return "Usage: PolicyTrainingApp [--dataset <v4-directory>] [--output <new-directory>] "
                + "[--epochs <positive-int>] [--dimensions <power-of-two>] [--learning-rate <positive-double>] "
                + "[--shuffle-buffer <positive-int>] [--shuffle-seed <long>] [--token-cache <nonnegative-int>]";
    }
}