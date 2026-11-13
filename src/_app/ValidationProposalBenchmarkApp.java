package _app;

import _app.PolicyProposalExperimentApp.Method;
import _app.PolicyProposalExperimentApp.Observation;
import _app.PolicyProposalExperimentApp.Result;
import _app.PolicyProposalExperimentApp.Work;
import agents.policy.GruMovementPolicy;
import io.training.*;
import io.training.ValidationPositions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static _app.PolicyProposalBenchmarkApp.*;

/**
 * Paired proposal experiments on frozen validation boards.
 * Summaries give each source game equal weight within a reporting group.
 */
public final class ValidationProposalBenchmarkApp {

    private static final String SCHEMA = "jreferee-validation-benchmark-v1";
    private static final String USAGE = "Usage: ValidationProposalBenchmarkApp [--dataset <v4-directory> | --positions <positions.json>] "
            + "[--select-only] [--per-stratum <1..10>] [--selection-seed <long>] [--runs <2..1000>] [--seed <long>] "
            + "[--output <new-directory>] [--proposal-model <policy.bin>] [--anchor-model <policy.bin>] [--gru-model <model.bin>] "
            + "[--candidates <1..256>] [--iterations <positive-int>] [--rollouts <positive-int>] "
            + "[--rollout-candidates <positive-int>] [--opponent-samples <at-least-two>]";
    private record Key(String group, String power, Method method) { }
    public record Estimate(int games, double mean, Double standardError) { }
    private static final class Totals {
        private final double[] sums = new double[6];
        private final long[] counts = new long[7];
        private final Set<String> boards = new HashSet<>();
        private int rows;
        private boolean sampled = true;
        private void add(String board, double[] values, long[] counters, boolean hasSamples) {
            boards.add(board);
            rows++;
            sampled &= hasSamples;
            for (int i = 0; i < sums.length; i++) sums[i] += values[i];
            for (int i = 0; i < counts.length; i++) counts[i] += counters[i];
        }
        private double mean(int index) { return sums[index] / rows; }
    }

    private ValidationProposalBenchmarkApp() { }

    public static Estimate grouped(Map<String, List<Double>> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("No source games");
        double[] means = new TreeMap<>(values).values().stream().mapToDouble(rows -> {
            if (rows.isEmpty() || rows.stream().anyMatch(value -> !Double.isFinite(value)))
                throw new IllegalArgumentException("Empty or nonfinite game values");
            return rows.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        }).toArray();
        if (means.length == 1) return new Estimate(1, means[0], null);
        var estimate = PolicyProposalBenchmarkApp.estimate(means);
        return new Estimate(means.length, estimate.mean(), estimate.standardError());
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) { System.out.println(USAGE); return; }
        Path dataset = Path.of("data/training-movement-v4"), positions = null;
        Path output = Path.of("data/movement-validation-benchmark-v1");
        int perStratum = 2, runs = 3;
        long selectionSeed = 7, seed = 7;
        boolean selectOnly = false;
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("--proposal-model", "data/movement-linear-v1/policy.bin");
        settings.put("--gru-model", "data/movement-gru-java-v1/model.bin");
        settings.put("--candidates", "8"); settings.put("--iterations", "16");
        settings.put("--rollouts", "1"); settings.put("--rollout-candidates", "8"); settings.put("--opponent-samples", "16");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!seen.add(flag)) throw new IllegalArgumentException(USAGE);
            if (flag.equals("--select-only")) { selectOnly = true; continue; }
            if (++i == args.length) throw new IllegalArgumentException(USAGE);
            String value = args[i];
            switch (flag) {
                case "--dataset" -> dataset = Path.of(value);
                case "--positions" -> positions = Path.of(value);
                case "--output" -> output = Path.of(value);
                case "--per-stratum" -> perStratum = Integer.parseInt(value);
                case "--selection-seed" -> selectionSeed = Long.parseLong(value);
                case "--runs" -> runs = Integer.parseInt(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--anchor-model" -> settings.put(flag, value);
                default -> {
                    if (!settings.containsKey(flag)) throw new IllegalArgumentException(USAGE);
                    settings.put(flag, value);
                }
            }
        }
        if (positions != null && (seen.contains("--dataset") || seen.contains("--per-stratum") || seen.contains("--selection-seed")))
            throw new IllegalArgumentException("A frozen position set cannot be reselected");
        settings.putIfAbsent("--anchor-model", settings.get("--proposal-model"));
        for (String flag : List.of("--candidates", "--iterations", "--rollouts", "--rollout-candidates", "--opponent-samples")) {
            int value = Integer.parseInt(settings.get(flag));
            if (value < (flag.equals("--opponent-samples") ? 2 : 1) || flag.equals("--candidates") && value > 256)
                throw new IllegalArgumentException(USAGE);
        }
        List<Seeds> schedule = schedule(runs, seed);
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        Selection selection = positions == null
                ? ValidationPositions.select(new JsonlMovementDataset(dataset), perStratum, selectionSeed)
                : ValidationPositions.load(positions);
        System.out.printf(Locale.ROOT, "Selected %d boards from %d games; scanned %d validation examples, %d boards; excluded %d.%n",
                selection.positions().size(), selection.positions().stream().map(Position::game).distinct().count(),
                selection.examples(), selection.boards(), selection.excluded());
        for (String cell : selection.availableBoards().keySet()) {
            long kept = selection.positions().stream().filter(position -> position.stratum().equals(cell)).count();
            System.out.printf(Locale.ROOT, "%s selected=%d available_boards=%d available_games=%d%n",
                    cell, kept, selection.availableBoards().get(cell), selection.availableGames().get(cell));
        }
        if (!selectOnly) {
            var gru = GruMovementPolicy.load(Path.of(settings.get("--gru-model")));
            if (!gru.datasetIndexSha256().equals(selection.datasetHash()))
                throw new IOException("GRU checkpoint and validation dataset differ");
            for (String flag : List.of("--proposal-model", "--anchor-model"))
                if (!Files.isRegularFile(Path.of(settings.get(flag)))) throw new IOException("Missing model: " + settings.get(flag));
        }
        Files.createDirectory(output);
        Path selected = output.resolve("positions.json");
        ValidationPositions.save(selection, selected);
        if (selectOnly) { System.out.println("Saved fixed positions to " + selected + ". No model or dataset TEST evaluation."); return; }
        List<Path> artifacts = new ArrayList<>(List.of(selected));
        Path seeds = output.resolve("seeds.csv"), observations = output.resolve("observations.csv"), work = output.resolve("work.csv");
        artifacts.addAll(List.of(seeds, observations, work));
        try (var writer = writer(seeds)) {
            csv(writer, "repetition", "pool", "search", "rollout", "opponents");
            for (int run = 0; run < schedule.size(); run++) {
                Seeds s = schedule.get(run); csv(writer, run, s.pool(), s.search(), s.rollout(), s.opponents());
            }
        }
        Map<Key, Map<String, Totals>> totals = new LinkedHashMap<>();
        Map<String, String> hashes = null;
        long start = System.nanoTime();
        int completed = 0, target = Integer.parseInt(settings.get("--candidates"));
        try (var rows = writer(observations); var costs = writer(work)) {
            csv(rows, "position", "source", "game", "stratum", "repetition", "method", "power", "candidates",
                    "samples", "duplicates", "own_supports", "bad_supports", "own_convoys", "bad_convoys",
                    "mismatch_actions", "mismatch_mass", "log_anchor_mass", "fresh_anchor", "fresh_search", "opponent_se", "fresh_best", "generation_seconds");
            csv(costs, "position", "repetition", "method", "generation_seconds", "search_seconds", "fresh_seconds",
                    "search_requests", "search_simulations", "search_evaluations", "search_failures",
                    "fresh_requests", "fresh_simulations", "fresh_evaluations", "fresh_failures");
            for (Position position : selection.positions())
                for (int run = 0; run < runs; run++) {
                    Seeds s = schedule.get(run);
                    var command = new ArrayList<String>();
                    settings.forEach((key, value) -> { command.add(key); command.add(value); });
                    command.addAll(List.of("--pool-seed", Long.toString(s.pool()), "--search-seed", Long.toString(s.search()),
                            "--rollout-seed", Long.toString(s.rollout()), "--opponent-seed", Long.toString(s.opponents())));
                    Path log = output.resolve(String.format(Locale.ROOT, "run-%04d.txt", completed));
                    System.out.printf(Locale.ROOT, "[%d/%d] %s %s repetition=%d%n", ++completed,
                            selection.positions().size() * runs, position.rank(), position.stratum(), run);
                    Result result;
                    try (var out = new PrintStream(Files.newOutputStream(log, StandardOpenOption.CREATE_NEW), false, StandardCharsets.UTF_8)) {
                        out.println("Validation position=" + position.rank() + "; stratum=" + position.stratum());
                        result = PolicyProposalExperimentApp.runPosition(position.state(), command.toArray(String[]::new), out);
                    }
                    artifacts.add(log);
                    if (!selection.datasetHash().equals(result.hashes().get("dataset_index_sha256")))
                        throw new IOException("Loaded GRU and validation dataset differ");
                    if (hashes == null) hashes = result.hashes();
                    else if (!hashes.equals(result.hashes())) throw new IOException("Models changed between runs");
                    audit(result, Integer.parseInt(settings.get("--opponent-samples")));
                    for (Observation row : result.observations())
                        csv(rows, position.rank(), position.source(), position.gameKey(), position.stratum(), run, row.method(), row.power(),
                                row.candidates(), row.policySamples(), row.policyDuplicates(), row.ownSupports(), row.badOwnSupports(),
                                row.ownConvoys(), row.badOwnConvoys(), row.mismatchedActions(), row.mismatchMass(), row.logAnchorMass(), row.freshAnchor(),
                                row.freshSearch(), row.freshSearchError(), row.freshBest(), row.generationSeconds());
                    for (Work cost : result.work()) {
                        csv(costs, position.rank(), run, cost.method(), cost.generationSeconds(), cost.searchSeconds(), cost.freshSeconds(),
                                cost.search().requests(), cost.search().simulations(), cost.search().evaluations(), cost.search().failures(),
                                cost.fresh().requests(), cost.fresh().simulations(), cost.fresh().evaluations(), cost.fresh().failures());
                        List<Observation> method = result.observations().stream().filter(row -> row.method() == cost.method()).toList();
                        double[] all = new double[6]; long[] allCounts = new long[7];
                        boolean sampled = true;
                        for (Observation row : method) {
                            double[] values = {row.freshSearch(), row.freshAnchor(), row.freshBest(),
                                    (double) row.mismatchedActions() / row.candidates(), row.mismatchMass(), row.generationSeconds() * 1000};
                            long[] counts = {row.policySamples() == null ? 0 : row.policySamples(),
                                    row.policyDuplicates() == null ? 0 : row.policyDuplicates(), row.ownSupports(), row.badOwnSupports(),
                                    row.ownConvoys(), row.badOwnConvoys(), row.candidates() < target ? 1 : 0};
                            add(totals, position, row.power().name(), row.method(), values, counts, row.policySamples() != null);
                            sampled &= row.policySamples() != null;
                            for (int i = 0; i < 5; i++) all[i] += values[i] / method.size();
                            for (int i = 0; i < 7; i++) allCounts[i] += counts[i];
                        }
                        all[5] = cost.generationSeconds() * 1000;
                        add(totals, position, "ALL", cost.method(), all, allCounts, sampled);
                    }
                    rows.flush(); costs.flush();
                }
        }
        Path summary = output.resolve("summary.csv"), paired = output.resolve("paired.csv"), games = output.resolve("games.csv");
        summaries(totals, summary, paired, games);
        artifacts.addAll(List.of(summary, paired, games));
        Map<String, String> files = new TreeMap<>();
        for (Path file : artifacts) files.put(file.getFileName().toString(), hash(file));
        String report = "{\"schema\":\"" + SCHEMA + "\",\"complete\":true,\"test_evaluated\":false,\"runs\":" + runs
                + ",\"seed\":" + seed + ",\"boards\":" + selection.positions().size() + ",\"games\":"
                + selection.positions().stream().map(Position::game).distinct().count()
                + ",\"dataset_sha256\":\"" + selection.datasetHash() + "\",\"validation_sha256\":\"" + selection.validationHash()
                + "\",\"seconds\":" + (System.nanoTime() - start) / 1e9 + ",\"settings\":" + strings(settings)
                + ",\"hashes\":" + strings(hashes) + ",\"files\":" + strings(files) + "}\n";
        Path pending = output.resolve("benchmark.pending");
        Files.writeString(pending, report, StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("benchmark.json"), StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Saved " + output + ". Uncertainty is grouped by source game; single-game errors are empty.");
    }

    private static void audit(Result result, int samples) {
        Set<String> keys = new HashSet<>();
        for (Observation row : result.observations())
            if (!result.root().aliveNations().contains(row.power()) || !keys.add(row.method() + ":" + row.power()) || row.candidates() < 1)
                throw new IllegalStateException("Invalid experiment cell");
        if (keys.size() != Method.values().length * result.root().aliveNations().size() || result.work().size() != Method.values().length)
            throw new IllegalStateException("Incomplete experiment");
        for (Work work : result.work()) {
            long candidates = result.observations().stream().filter(row -> row.method() == work.method()).mapToLong(Observation::candidates).sum();
            if (work.search().failures() != 0 || work.fresh().failures() != 0 || work.fresh().requests() != candidates * samples
                    || work.search().requests() != work.search().evaluations() || work.fresh().requests() != work.fresh().evaluations())
                throw new IllegalStateException("Invalid evaluation accounting");
        }
    }

    private static void add(Map<Key, Map<String, Totals>> totals, Position position, String power, Method method,
                            double[] values, long[] counts, boolean sampled) {
        for (String group : position.groups())
            totals.computeIfAbsent(new Key(group, power, method), key -> new TreeMap<>())
                    .computeIfAbsent(position.game(), key -> new Totals()).add(position.rank(), values, counts, sampled);
    }

    private static Estimate metric(Map<String, Totals> games, int metric) {
        Map<String, List<Double>> values = new TreeMap<>();
        games.forEach((game, totals) -> values.put(game, List.of(totals.mean(metric))));
        return grouped(values);
    }

    private static void summaries(Map<Key, Map<String, Totals>> totals, Path summary, Path paired, Path games) throws IOException {
        try (var out = writer(summary); var pairs = writer(paired); var clusters = writer(games)) {
            csv(out, "group", "power", "method", "games", "boards", "board_seed_rows", "fresh_search_mean", "game_se",
                    "fresh_anchor_mean", "fresh_best_mean", "mismatch_fraction_mean", "mismatch_mass_mean", "generation_ms_mean",
                    "policy_samples", "policy_duplicates", "own_supports", "bad_supports", "own_convoys", "bad_convoys", "short_pools");
            csv(pairs, "group", "power", "method", "baseline", "games", "mean_delta", "game_se", "positive_games", "zero_games", "negative_games");
            csv(clusters, "group", "power", "method", "source", "game", "boards", "board_seed_rows", "fresh_search_mean");
            for (var entry : totals.entrySet()) {
                Key key = entry.getKey(); Map<String, Totals> group = entry.getValue();
                Estimate estimate = metric(group, 0);
                boolean sampled = group.values().stream().allMatch(value -> value.sampled);
                long[] counts = new long[7];
                for (var game : group.entrySet()) {
                    String[] identity = game.getKey().split("\n", -1); Totals value = game.getValue();
                    csv(clusters, key.group(), key.power(), key.method(), identity[0], identity[1], value.boards.size(), value.rows, value.mean(0));
                    for (int i = 0; i < counts.length; i++) counts[i] += value.counts[i];
                }
                csv(out, key.group(), key.power(), key.method(), estimate.games(),
                        group.values().stream().mapToInt(value -> value.boards.size()).sum(),
                        group.values().stream().mapToInt(value -> value.rows).sum(), estimate.mean(), estimate.standardError(),
                        metric(group, 1).mean(), metric(group, 2).mean(), metric(group, 3).mean(), metric(group, 4).mean(), metric(group, 5).mean(),
                        sampled ? counts[0] : null, sampled ? counts[1] : null, counts[2], counts[3], counts[4], counts[5], counts[6]);
                for (Method baseline : Method.values()) {
                    if (baseline.ordinal() >= key.method().ordinal()) break;
                    var reference = totals.get(new Key(key.group(), key.power(), baseline));
                    if (reference == null || !reference.keySet().equals(group.keySet())) throw new IllegalStateException("Unpaired source games");
                    Map<String, List<Double>> differences = new TreeMap<>();
                    int positive = 0, zero = 0;
                    for (String game : group.keySet()) {
                        double delta = group.get(game).mean(0) - reference.get(game).mean(0);
                        differences.put(game, List.of(delta));
                        if (delta > TIE_TOLERANCE) positive++;
                        if (Math.abs(delta) <= TIE_TOLERANCE) zero++;
                    }
                    Estimate delta = grouped(differences);
                    csv(pairs, key.group(), key.power(), key.method(), baseline, delta.games(), delta.mean(), delta.standardError(),
                            positive, zero, delta.games() - positive - zero);
                }
            }
        }
    }
}