package _app;

import agents.encoding.EncodingPurpose;
import agents.policy.LinearMovementPolicy;
import io.training.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Explicit held-out evaluation of a saved movement policy.
 */
public final class PolicyEvaluationApp {

    private PolicyEvaluationApp() { }

    public static void main(String[] args) throws Exception {
        Path datasetPath = Path.of("data", "training-movement-v4");
        Path model = Path.of("data", "movement-linear-v1", "policy.bin");
        Path output = Path.of("data", "movement-linear-v1", "evaluation.json");
        EncodingPurpose split = EncodingPurpose.VALIDATION;
        int cache = JsonlMovementDataset.DEFAULT_TOKEN_CACHE;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--help")) { System.out.println(usage()); return; }
            if (!seen.add(args[index]) || index + 1 >= args.length) throw new IllegalArgumentException(usage());
            switch (args[index]) {
                case "--dataset" -> datasetPath = Path.of(args[++index]);
                case "--model" -> model = Path.of(args[++index]);
                case "--output" -> output = Path.of(args[++index]);
                case "--split" -> split = EncodingPurpose.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--token-cache" -> cache = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException(usage());
            }
        }
        if (split == EncodingPurpose.TRAIN) throw new IllegalArgumentException("Use validation or test for held-out evaluation");
        if (Files.exists(output)) throw new IllegalArgumentException("Output file already exists: " + output);
        var dataset = new JsonlMovementDataset(datasetPath, cache);
        var policy = LinearMovementPolicy.load(model);
        String modelHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model)));
        var result = PolicyEvaluation.evaluate(dataset, split, policy);
        String report = "{\"schema\":\"jreferee-policy-evaluation-v1\",\"complete\":true,\"model_schema\":\""
                + LinearMovementPolicy.SCHEMA + "\",\"dataset_index_sha256\":\"" + dataset.fingerprint()
                + "\",\"model_sha256\":\"" + modelHash
                + "\",\"filter\":" + dataset.filter().toJson() + ",\"token_cache\":" + cache
                + ",\"result\":" + result.toJson() + "}\n";
        Files.writeString(output, report, StandardOpenOption.CREATE_NEW);
        System.out.printf(Locale.ROOT, "%s: NLL %.6f; uniform NLL %.6f; order accuracy %.4f%n",
                split, result.overall().orderNll(), result.overall().uniformLoss() / result.overall().orders(),
                (double) result.overall().correctOrders() / result.overall().orders());
        System.out.println("Report: " + output.toAbsolutePath());
    }

    private static String usage() {
        return "Usage: PolicyEvaluationApp [--dataset <v4-directory>] [--model <policy.bin>] "
                + "[--split <validation|test>] [--output <new-json-file>] [--token-cache <nonnegative-int>]";
    }
}