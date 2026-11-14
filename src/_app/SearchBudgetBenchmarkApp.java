package _app;

import agents.encoding.MovementEncoding;
import agents.execution.ProposalInspector.Method;
import domain.Nation;
import io.training.ValidationPositions;
import io.training.ValidationPositions.Position;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static _app.PolicyProposalBenchmarkApp.*;

/**
 * Frozen-validation search budgets with shared pools and fresh payoff tables.
 */
public final class SearchBudgetBenchmarkApp {

    public static final String SCHEMA = "jreferee-search-budget-benchmark-v1";
    private static final String USAGE = "Usage: SearchBudgetBenchmarkApp --positions <positions.json> "
            + "[--proposal-model <policy.bin>] [--anchor-model <policy.bin>] [--gru-model <model.bin>] "
            + "[--methods <comma-separated-methods>] [--checkpoints <increasing-iterations>] "
            + "[--runs <2..1000>] [--seed <long>] [--candidates <1..64>] [--rollouts <1..8>] "
            + "[--rollout-candidates <1..64>] [--opponent-samples <at-least-two>] [--output <new-directory>]";
    private static final List<String> ARTIFACTS = List.of("positions.json", "seeds.csv", "opponents.csv", "candidates.csv",
            "fresh.csv", "policies.csv", "observations.csv", "diagnostics.csv", "work.csv", "summary.csv", "paired.csv", "games.csv");

    private SearchBudgetBenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) { System.out.println(USAGE); return; }
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("--proposal-model", "data/movement-linear-v1/policy.bin");
        flags.put("--gru-model", "data/movement-gru-java-v1/model.bin");
        flags.put("--methods", "PLAUSIBLE,COHERENT_GRU,COHERENT_GRU_HYBRID");
        flags.put("--checkpoints", "16,64,256");
        flags.put("--runs", "3"); flags.put("--seed", "7"); flags.put("--candidates", "8");
        flags.put("--rollouts", "1"); flags.put("--rollout-candidates", "8"); flags.put("--opponent-samples", "16");
        flags.put("--output", "data/movement-search-budget-benchmark-v1");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!seen.add(flag) || ++i == args.length
                    || !flags.containsKey(flag) && !flag.equals("--anchor-model") && !flag.equals("--positions"))
                throw new IllegalArgumentException(USAGE);
            flags.put(flag, args[i]);
        }
        if (!flags.containsKey("--positions")) throw new IllegalArgumentException(USAGE);
        flags.putIfAbsent("--anchor-model", flags.get("--proposal-model"));
        var options = new SearchBudgetExperiment.Options(Integer.parseInt(flags.get("--candidates")),
                Integer.parseInt(flags.get("--rollouts")), Integer.parseInt(flags.get("--rollout-candidates")),
                Integer.parseInt(flags.get("--opponent-samples")),
                Arrays.stream(flags.get("--checkpoints").split(",", -1)).map(Integer::valueOf).toList(),
                Arrays.stream(flags.get("--methods").split(",", -1)).map(value -> Method.valueOf(value.toUpperCase(Locale.ROOT))).toList());
        long seed = Long.parseLong(flags.get("--seed"));
        List<Seeds> seeds = schedule(Integer.parseInt(flags.get("--runs")), seed);
        Path output = Path.of(flags.get("--output"));
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        var selection = ValidationPositions.load(Path.of(flags.get("--positions")));
        var experiment = SearchBudgetExperiment.load(Path.of(flags.get("--proposal-model")),
                Path.of(flags.get("--anchor-model")), Path.of(flags.get("--gru-model")));
        if (!selection.datasetHash().equals(experiment.models().get("dataset")))
            throw new IOException("GRU checkpoint and frozen validation dataset differ");
        Files.createDirectory(output);
        ValidationPositions.save(selection, output.resolve("positions.json"));
        try (var out = writer(output.resolve("seeds.csv"))) {
            csv(out, "repetition", "pool", "search", "rollout", "opponents");
            for (int i = 0; i < seeds.size(); i++) {
                Seeds s = seeds.get(i); csv(out, i, s.pool(), s.search(), s.rollout(), s.opponents());
            }
        }
        List<SearchBudgetReports.Row> reports = new ArrayList<>();
        long start = System.nanoTime();
        int completed = 0;
        try (var opponents = writer(output.resolve("opponents.csv")); var candidates = writer(output.resolve("candidates.csv"));
             var fresh = writer(output.resolve("fresh.csv")); var policies = writer(output.resolve("policies.csv"));
             var observations = writer(output.resolve("observations.csv")); var diagnostics = writer(output.resolve("diagnostics.csv"));
             var work = writer(output.resolve("work.csv"))) {
            csv(opponents, "position", "repetition", "sample", "power", "orders");
            csv(candidates, "position", "repetition", "method", "power", "pool_sha256", "candidate", "source",
                    "log_anchor", "anchor_probability", "log_gru", "orders");
            csv(fresh, "position", "repetition", "method", "power", "pool_sha256", "candidate", "sample", "value");
            csv(policies, "position", "repetition", "method", "power", "pool_sha256", "iterations", "candidate", "probability", "Q");
            csv(observations, "position", "source", "game", "stratum", "repetition", "method", "power", "pool_sha256",
                    "iterations", "candidates", "fresh_search", "opponent_se", "fresh_anchor", "fresh_best");
            ProposalDiagnostics.header(diagnostics, "position", "source", "game", "stratum", "repetition", "method", "power",
                    "pool_sha256", "iterations", "candidates");
            csv(work, "position", "repetition", "method", "max_iterations", "generation_seconds", "trace_seconds", "fresh_seconds",
                    "requests_per_iteration", "search_requests", "search_simulations", "search_evaluations", "search_failures",
                    "fresh_requests", "fresh_simulations", "fresh_evaluations", "fresh_failures");
            for (Position position : selection.positions())
                for (int repetition = 0; repetition < seeds.size(); repetition++) {
                    System.out.printf(Locale.ROOT, "[%d/%d] %s %s repetition=%d checkpoints=%s%n",
                            ++completed, selection.positions().size() * seeds.size(), position.rank(), position.stratum(),
                            repetition, options.checkpoints());
                    var result = experiment.run(position.state(), options, seeds.get(repetition));
                    Map<Nation, MovementEncoding> encodings = new EnumMap<>(Nation.class);
                    for (Nation power : position.state().aliveNations())
                        encodings.put(power, new MovementEncoding(position.state(), power));
                    for (int sample = 0; sample < result.opponents().size(); sample++)
                        for (var entry : result.opponents().get(sample).entrySet())
                            csv(opponents, position.rank(), repetition, sample, entry.getKey(), String.join("; ",
                                    entry.getValue().orders().stream().map(encodings.get(entry.getKey())::token).toList()));
                    for (var method : result.methods()) {
                        for (var entry : method.pools().entrySet()) {
                            Nation power = entry.getKey();
                            var pool = entry.getValue();
                            var inspection = pool.inspection();
                            var values = method.freshValues().get(power);
                            for (var proposal : inspection.proposals()) {
                                csv(candidates, position.rank(), repetition, method.method(), power, pool.sha256(), proposal.index(),
                                        proposal.action().source(), proposal.action().logPrior(), proposal.anchorPoolProbability(),
                                        proposal.logGru(), String.join("; ", proposal.orders()));
                                List<Double> samples = values.get(proposal.index());
                                for (int sample = 0; sample < samples.size(); sample++)
                                    csv(fresh, position.rank(), repetition, method.method(), power, pool.sha256(),
                                            proposal.index(), sample, samples.get(sample));
                            }
                            double anchor = estimate(SearchBudgetExperiment.mixture(values,
                                    inspection.proposals().stream().map(p -> p.anchorPoolProbability()).toList())).mean();
                            double best = values.stream().mapToDouble(row -> row.stream().mapToDouble(Double::doubleValue)
                                    .average().orElseThrow()).max().orElseThrow();
                            for (var checkpoint : method.history()) {
                                var solved = checkpoint.results().get(power);
                                var probabilities = solved.distribution().probabilities();
                                int iterations = solved.samplesPerCandidate();
                                var estimate = estimate(SearchBudgetExperiment.mixture(values, probabilities));
                                csv(observations, position.rank(), position.source(), position.gameKey(), position.stratum(), repetition,
                                        method.method(), power, pool.sha256(), iterations, pool.candidates().size(),
                                        estimate.mean(), estimate.standardError(), anchor, best);
                                for (int i = 0; i < pool.candidates().size(); i++)
                                    csv(policies, position.rank(), repetition, method.method(), power, pool.sha256(), iterations,
                                            i, probabilities.get(i), solved.meanValues().get(i));
                                var generated = inspection.generation().get(power);
                                var details = ProposalDiagnostics.measure(position.state(), encodings.get(power).catalog(), pool.candidates(),
                                        probabilities, generated == null ? null : generated.learned(),
                                        generated == null ? null : generated.hybrid().orElse(null));
                                ProposalDiagnostics.write(diagnostics, details, position.rank(), position.source(), position.gameKey(),
                                        position.stratum(), repetition, method.method(), power, pool.sha256(), iterations, pool.candidates().size());
                                reports.add(new SearchBudgetReports.Row(position, repetition, method.method(), iterations, power,
                                        estimate.mean(), anchor, best));
                            }
                        }
                        var cost = method.work();
                        csv(work, position.rank(), repetition, method.method(), options.checkpoints().getLast(),
                                cost.generationSeconds(), cost.searchSeconds(), cost.freshSeconds(), cost.requestsPerIteration(),
                                cost.search().requests(), cost.search().simulations(), cost.search().evaluations(), cost.search().failures(),
                                cost.fresh().requests(), cost.fresh().simulations(), cost.fresh().evaluations(), cost.fresh().failures());
                    }
                    opponents.flush(); candidates.flush(); fresh.flush(); policies.flush();
                    observations.flush(); diagnostics.flush(); work.flush();
                }
        }
        SearchBudgetReports.write(reports, options.methods(), options.checkpoints(), output);
        Map<String, String> hashes = new TreeMap<>();
        for (String name : ARTIFACTS) hashes.put(name, hash(output.resolve(name)));
        String receipt = "{\"schema\":\"" + SCHEMA + "\",\"complete\":true,\"test_evaluated\":false,"
                + "\"search_output\":\"AVERAGE\",\"temperature\":\"ADAPTIVE\",\"rules\":\"JUDGE:1:0\","
                + "\"scoring\":\"SUM_OF_SQUARES\",\"continuation\":\"UNIFORM_TO_CURRENT_WINTER\","
                + "\"search_profile\":\"PaperSearchProfile.forState\",\"trace_reused\":true,\"fresh_reused\":true,"
                + "\"runs\":" + seeds.size() + ",\"seed\":" + seed + ",\"boards\":" + selection.positions().size()
                + ",\"games\":" + selection.positions().stream().map(Position::game).distinct().count()
                + ",\"checkpoints\":" + options.checkpoints() + ",\"methods\":"
                + options.methods().stream().map(method -> "\"" + method.name() + "\"").toList()
                + ",\"dataset_sha256\":\"" + selection.datasetHash() + "\",\"validation_sha256\":\"" + selection.validationHash()
                + "\",\"seconds\":" + (System.nanoTime() - start) / 1e9 + ",\"settings\":" + strings(flags)
                + ",\"models\":" + strings(experiment.models()) + ",\"files\":" + strings(hashes) + "}\n";
        Path pending = output.resolve("benchmark.pending");
        Files.writeString(pending, receipt, StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("benchmark.json"), StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Saved " + output + ". Compare paired.csv within methods across budgets and across methods at equal budgets.");
    }
}