package _app;

import _app.PolicyProposalExperimentApp.*;
import agents.generation.PolicyActionGenerator;
import domain.Nation;
import io.json.JsonEscaper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Paired seed repetitions of the opening and contact proposal experiments.
 * Each repetition contributes one value to a per-power comparison.
 */
public final class PolicyProposalBenchmarkApp {

    public static final String SCHEMA = "jreferee-proposal-benchmark-v1";
    public static final double TIE_TOLERANCE = 1e-12;
    private static final String USAGE = "Usage: PolicyProposalBenchmarkApp [--runs <2..1000>] [--seed <long>] "
            + "[--output <new-directory>] [--proposal-model <policy.bin>] [--anchor-model <policy.bin>] "
            + "[--gru-model <model.bin>] [--candidates <1..256>] [--iterations <positive-int>] "
            + "[--rollouts <positive-int>] [--rollout-candidates <positive-int>] [--opponent-samples <at-least-two>]";

    public record Seeds(long pool, long search, long rollout, long opponents) { }
    public record Estimate(double mean, double standardError) { }
    private record Trial(int repetition, Seeds seeds, Position position, Result result) { }

    private PolicyProposalBenchmarkApp() { }

    public static List<Seeds> schedule(int runs, long seed) {
        if (runs < 2 || runs > 1000) throw new IllegalArgumentException("Runs must be between 2 and 1000");
        List<Seeds> result = new ArrayList<>();
        result.add(new Seeds(seed, seed, seed, Math.addExact(seed, 10)));
        Random random = new Random(seed);
        for (int run = 1; run < runs; run++)
            result.add(new Seeds(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()));
        return List.copyOf(result);
    }

    public static Estimate estimate(double[] values) {
        if (values.length < 2) throw new IllegalArgumentException("At least two repetitions are required");
        double mean = 0, squared = 0;
        int count = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite observation");
            double delta = value - mean;
            mean += delta / ++count;
            squared += delta * (value - mean);
        }
        return new Estimate(mean, Math.sqrt(squared / (count * (count - 1.0))));
    }

    public static Estimate paired(double[] values, double[] baseline) {
        if (values.length != baseline.length) throw new IllegalArgumentException("Unpaired repetitions");
        double[] differences = new double[values.length];
        for (int index = 0; index < values.length; index++) differences[index] = values[index] - baseline[index];
        return estimate(differences);
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(USAGE);
            System.out.println("Defaults: 20 paired seed sets, base seed 7, both positions, all seven methods and unchanged budgets.");
            return;
        }
        int runs = 20;
        long seed = 7;
        Path output = Path.of("data/movement-proposal-benchmark-v1");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("--proposal-model", "data/movement-linear-v1/policy.bin");
        settings.put("--gru-model", "data/movement-gru-java-v1/model.bin");
        settings.put("--candidates", "8");
        settings.put("--iterations", "16");
        settings.put("--rollouts", "1");
        settings.put("--rollout-candidates", "8");
        settings.put("--opponent-samples", "16");
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            if (!seen.add(flag) || index + 1 == args.length) throw new IllegalArgumentException(USAGE);
            String value = args[++index];
            switch (flag) {
                case "--runs" -> runs = Integer.parseInt(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--output" -> output = Path.of(value);
                case "--anchor-model" -> settings.put(flag, value);
                default -> {
                    if (!settings.containsKey(flag)) throw new IllegalArgumentException(USAGE);
                    settings.put(flag, value);
                }
            }
        }
        settings.putIfAbsent("--anchor-model", settings.get("--proposal-model"));
        for (String flag : List.of("--candidates", "--iterations", "--rollouts", "--rollout-candidates", "--opponent-samples")) {
            int value = Integer.parseInt(settings.get(flag));
            if (value < (flag.equals("--opponent-samples") ? 2 : 1)
                    || flag.equals("--candidates") && value > PolicyActionGenerator.MAX_CANDIDATES)
                throw new IllegalArgumentException(USAGE);
        }
        List<Seeds> seeds = schedule(runs, seed);
        for (String flag : List.of("--proposal-model", "--anchor-model", "--gru-model"))
            if (!Files.isRegularFile(Path.of(settings.get(flag)))) throw new IOException("Missing model: " + settings.get(flag));
        Files.createDirectory(output);
        List<Path> artifacts = new ArrayList<>();
        Path seedFile = output.resolve("seeds.csv"), observations = output.resolve("observations.csv"), work = output.resolve("work.csv");
        artifacts.addAll(List.of(seedFile, observations, work));
        try (var writer = writer(seedFile)) {
            csv(writer, "repetition", "pool", "search", "rollout", "opponents");
            for (int run = 0; run < runs; run++) {
                Seeds s = seeds.get(run);
                csv(writer, run, s.pool(), s.search(), s.rollout(), s.opponents());
            }
        }
        List<Trial> trials = new ArrayList<>();
        Map<String, String> hashes = null;
        long start = System.nanoTime();
        try (var rows = writer(observations); var costs = writer(work)) {
            csv(rows, "repetition", "position", "method", "power", "candidates", "policy_samples", "policy_duplicates",
                    "own_supports", "bad_own_supports", "own_convoys", "bad_own_convoys", "mismatched_actions",
                    "mismatch_mass", "log_anchor_mass", "fresh_anchor", "fresh_search", "fresh_search_opponent_se",
                    "fresh_best", "generation_seconds");
            csv(costs, "repetition", "position", "method", "generation_seconds", "search_seconds", "fresh_seconds",
                    "search_requests", "search_simulations", "search_evaluations", "search_failures",
                    "fresh_requests", "fresh_simulations", "fresh_evaluations", "fresh_failures");
            for (int run = 0; run < runs; run++)
                for (Position position : Position.values()) {
                    Seeds s = seeds.get(run);
                    List<String> command = new ArrayList<>();
                    settings.forEach((key, value) -> { command.add(key); command.add(value); });
                    command.addAll(List.of("--position", position.name(), "--pool-seed", Long.toString(s.pool()),
                            "--search-seed", Long.toString(s.search()), "--rollout-seed", Long.toString(s.rollout()),
                            "--opponent-seed", Long.toString(s.opponents())));
                    Path log = output.resolve(String.format(Locale.ROOT, "run-%03d-%s.txt", run, position.name().toLowerCase(Locale.ROOT)));
                    Result result;
                    System.out.printf(Locale.ROOT, "[%d/%d] %s pool=%d search=%d rollout=%d opponents=%d%n",
                            run + 1, runs, position, s.pool(), s.search(), s.rollout(), s.opponents());
                    try (var out = new PrintStream(Files.newOutputStream(log, StandardOpenOption.CREATE_NEW), false, StandardCharsets.UTF_8)) {
                        result = PolicyProposalExperimentApp.run(command.toArray(String[]::new), out);
                    }
                    artifacts.add(log);
                    if (hashes == null) hashes = result.hashes();
                    else if (!hashes.equals(result.hashes())) throw new IOException("Models changed between benchmark runs");
                    audit(result, Integer.parseInt(settings.get("--opponent-samples")));
                    trials.add(new Trial(run, s, position, result));
                    for (Observation row : result.observations())
                        csv(rows, run, position, row.method(), row.power(), row.candidates(), row.policySamples(), row.policyDuplicates(),
                                row.ownSupports(), row.badOwnSupports(), row.ownConvoys(), row.badOwnConvoys(), row.mismatchedActions(),
                                row.mismatchMass(), row.logAnchorMass(), row.freshAnchor(), row.freshSearch(), row.freshSearchError(),
                                row.freshBest(), row.generationSeconds());
                    for (Work cost : result.work())
                        csv(costs, run, position, cost.method(), cost.generationSeconds(), cost.searchSeconds(), cost.freshSeconds(),
                                cost.search().requests(), cost.search().simulations(), cost.search().evaluations(), cost.search().failures(),
                                cost.fresh().requests(), cost.fresh().simulations(), cost.fresh().evaluations(), cost.fresh().failures());
                    rows.flush();
                    costs.flush();
                }
        }
        Path summary = output.resolve("summary.csv"), comparisons = output.resolve("paired.csv");
        summaries(trials, Integer.parseInt(settings.get("--candidates")), summary, comparisons);
        artifacts.addAll(List.of(summary, comparisons));
        StringBuilder receipt = new StringBuilder("{\n\"schema\":");
        JsonEscaper.appendString(receipt, SCHEMA);
        receipt.append(",\n\"complete\":true,\"test_evaluated\":false,\"runs\":").append(runs)
                .append(",\"base_seed\":").append(seed).append(",\"positions\":[\"OPENING\",\"CONTACT\"],")
                .append("\n\"paired_tie_tolerance\":").append(TIE_TOLERANCE).append(',')
                .append("\n\"seconds\":").append((System.nanoTime() - start) / 1e9).append(",\n\"java_version\":");
        JsonEscaper.appendString(receipt, System.getProperty("java.version"));
        receipt.append(",\n\"settings\":").append(strings(settings)).append(",\n\"hashes\":").append(strings(hashes))
                .append(",\n\"files\":[");
        for (int index = 0; index < artifacts.size(); index++) {
            Path file = artifacts.get(index);
            if (index != 0) receipt.append(',');
            receipt.append("{\"name\":");
            JsonEscaper.appendString(receipt, file.getFileName().toString());
            receipt.append(",\"bytes\":").append(Files.size(file)).append(",\"sha256\":");
            JsonEscaper.appendString(receipt, hash(file));
            receipt.append('}');
        }
        receipt.append("]}\n");
        Path pending = output.resolve("benchmark.pending");
        Files.writeString(pending, receipt, StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("benchmark.json"), StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Saved " + output + ". Compare paired seed-level differences; these are fixture diagnostics, not win rates.");
    }

    private static void audit(Result result, int samples) {
        Set<String> keys = new HashSet<>();
        for (Observation row : result.observations())
            if (!keys.add(row.method() + ":" + row.power()) || row.candidates() < 1)
                throw new IllegalStateException("Duplicate or empty benchmark cell");
        if (keys.size() != Method.values().length * Nation.values().length || result.work().size() != Method.values().length)
            throw new IllegalStateException("Incomplete benchmark run");
        for (Work work : result.work()) {
            long candidates = result.observations().stream().filter(row -> row.method() == work.method())
                    .mapToLong(Observation::candidates).sum();
            if (work.search().failures() != 0 || work.fresh().failures() != 0
                    || work.fresh().requests() != candidates * samples
                    || work.search().requests() != work.search().evaluations()
                    || work.fresh().requests() != work.fresh().evaluations())
                throw new IllegalStateException("Unexpected root evaluation accounting");
        }
    }

    private static void summaries(List<Trial> trials, int target, Path summary, Path comparisons) throws IOException {
        try (var out = writer(summary); var pairs = writer(comparisons)) {
            csv(out, "position", "power", "method", "runs", "fresh_search_mean", "fresh_search_seed_se",
                    "fresh_anchor_mean", "fresh_best_mean", "mismatch_fraction_mean", "mismatch_mass_mean",
                    "generation_ms_mean", "short_pools", "policy_samples", "policy_duplicates",
                    "own_supports", "bad_own_supports", "own_convoys", "bad_own_convoys");
            csv(pairs, "position", "power", "method", "baseline", "runs", "mean_delta", "seed_se",
                    "positive_runs", "zero_runs", "negative_runs");
            List<Nation> powers = new ArrayList<>(Arrays.asList(Nation.values()));
            powers.add(null);
            for (Position position : Position.values()) {
                List<Trial> selected = trials.stream().filter(trial -> trial.position() == position).toList();
                for (Nation power : powers) {
                    String label = power == null ? "ALL" : power.name();
                    for (Method method : Method.values()) {
                        double[] search = series(selected, method, power, Observation::freshSearch);
                        Estimate estimate = estimate(search);
                        List<Observation> rows = selected.stream().flatMap(trial -> matching(trial, method, power).stream()).toList();
                        boolean sampled = rows.stream().allMatch(row -> row.policySamples() != null);
                        double generation = power == null
                                ? selected.stream().flatMap(trial -> trial.result().work().stream())
                                .filter(cost -> cost.method() == method).mapToDouble(Work::generationSeconds).average().orElseThrow()
                                : rows.stream().mapToDouble(Observation::generationSeconds).average().orElseThrow();
                        csv(out, position, label, method, selected.size(), estimate.mean(), estimate.standardError(),
                                estimate(series(selected, method, power, Observation::freshAnchor)).mean(),
                                estimate(series(selected, method, power, Observation::freshBest)).mean(),
                                estimate(series(selected, method, power, row -> (double) row.mismatchedActions() / row.candidates())).mean(),
                                estimate(series(selected, method, power, Observation::mismatchMass)).mean(), generation * 1000,
                                rows.stream().filter(row -> row.candidates() < target).count(),
                                sampled ? rows.stream().mapToLong(Observation::policySamples).sum() : null,
                                sampled ? rows.stream().mapToLong(Observation::policyDuplicates).sum() : null,
                                rows.stream().mapToInt(Observation::ownSupports).sum(),
                                rows.stream().mapToInt(Observation::badOwnSupports).sum(),
                                rows.stream().mapToInt(Observation::ownConvoys).sum(),
                                rows.stream().mapToInt(Observation::badOwnConvoys).sum());
                        if (power == null)
                            System.out.printf(Locale.ROOT, "%s %-12s fresh=%.8f seed_se=%.8f generation_ms=%.3f%n",
                                    position, method, estimate.mean(), estimate.standardError(), generation * 1000);
                        for (Method baseline : Method.values()) {
                            if (baseline.ordinal() >= method.ordinal()) break;
                            double[] reference = series(selected, baseline, power, Observation::freshSearch);
                            Estimate delta = paired(search, reference);
                            int positive = 0, zero = 0;
                            for (int index = 0; index < search.length; index++) {
                                double difference = search[index] - reference[index];
                                if (difference > TIE_TOLERANCE) positive++;
                                if (Math.abs(difference) <= TIE_TOLERANCE) zero++;
                            }
                            csv(pairs, position, label, method, baseline, selected.size(), delta.mean(), delta.standardError(),
                                    positive, zero, search.length - positive - zero);
                        }
                    }
                }
            }
        }
    }

    private static List<Observation> matching(Trial trial, Method method, Nation power) {
        return trial.result().observations().stream()
                .filter(row -> row.method() == method && (power == null || row.power() == power)).toList();
    }

    private static double[] series(List<Trial> trials, Method method, Nation power, ToDoubleFunction<Observation> metric) {
        return trials.stream().mapToDouble(trial -> matching(trial, method, power).stream()
                .mapToDouble(metric).average().orElseThrow()).toArray();
    }

    static BufferedWriter writer(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static void csv(Writer out, Object... fields) throws IOException {
        List<String> values = new ArrayList<>();
        for (Object field : fields) {
            if (field instanceof Double number && !Double.isFinite(number)) throw new IOException("Nonfinite report value");
            String value = field == null ? "" : field.toString();
            values.add(value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                    ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
        }
        out.write(String.join(",", values) + "\n");
    }

    static String strings(Map<String, String> values) {
        return new TreeMap<>(values).entrySet().stream().map(entry -> {
            StringBuilder out = new StringBuilder();
            JsonEscaper.appendString(out, entry.getKey());
            out.append(':');
            JsonEscaper.appendString(out, entry.getValue());
            return out.toString();
        }).collect(Collectors.joining(",", "{", "}"));
    }

    static String hash(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}