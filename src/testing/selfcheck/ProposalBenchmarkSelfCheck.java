package testing.selfcheck;

import _app.PolicyProposalBenchmarkApp;
import _app.PolicyProposalExperimentApp;
import agents.encoding.MovementEncoding;
import agents.generation.*;
import agents.policy.*;
import domain.Nation;
import game.*;
import io.json.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Seed pairing, summary statistics, hybrid counters and complete benchmark receipts.
 * Integration checks use small budgets and the supplied frozen models.
 */
public final class ProposalBenchmarkSelfCheck {

    private static int checks;
    private static final JsonAccess JSON = new JsonAccess((path, message) -> new IllegalArgumentException(path + ": " + message));

    private ProposalBenchmarkSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Usage: ProposalBenchmarkSelfCheck [linear-policy.bin gru-model.bin]");
        String linear = args.length == 0 ? "data/movement-linear-v1/policy.bin" : args[0];
        String gru = args.length == 0 ? "data/movement-gru-java-v1/model.bin" : args[1];
        statistics();
        counters();
        Path temporary = Files.createTempDirectory("jreferee-proposal-benchmark-");
        try {
            List<String> settings = List.of("--proposal-model", linear, "--anchor-model", linear, "--gru-model", gru,
                    "--candidates", "2", "--iterations", "2", "--opponent-samples", "2", "--rollout-candidates", "2");
            Path output = temporary.resolve("results");
            var command = new ArrayList<>(settings);
            command.addAll(List.of("--runs", "2", "--output", output.toString()));
            PolicyProposalBenchmarkApp.main(command.toArray(String[]::new));
            receipt(output);
            var observations = table(output.resolve("observations.csv"));
            var summary = table(output.resolve("summary.csv"));
            var pairs = table(output.resolve("paired.csv"));
            require(observations.size() == 196 && summary.size() == 112 && pairs.size() == 336, "Complete result dimensions");
            for (var row : observations) {
                boolean sampled = !Set.of("UNIFORM", "PLAUSIBLE").contains(row.get("method"));
                require(sampled != row.get("policy_samples").isEmpty(), "Unknown sampling counts remain empty");
                if (sampled) require(Long.parseLong(row.get("policy_samples")) >= Long.parseLong(row.get("policy_duplicates")),
                        "Sample accounting");
            }
            for (var row : pairs) {
                double[] values = series(observations, row.get("position"), row.get("power"), row.get("method"));
                double[] baseline = series(observations, row.get("position"), row.get("power"), row.get("baseline"));
                var expected = PolicyProposalBenchmarkApp.paired(values, baseline);
                close(Double.parseDouble(row.get("mean_delta")), expected.mean(), "Paired mean");
                close(Double.parseDouble(row.get("seed_se")), expected.standardError(), "Paired seed error");
                require(row.get("runs").equals("2"), "Powers and opponent samples are not independent repetitions");
                int positive = 0, zero = 0;
                for (int index = 0; index < values.length; index++) {
                    double difference = values[index] - baseline[index];
                    if (difference > PolicyProposalBenchmarkApp.TIE_TOLERANCE) positive++;
                    if (Math.abs(difference) <= PolicyProposalBenchmarkApp.TIE_TOLERANCE) zero++;
                }
                require(Integer.parseInt(row.get("positive_runs")) == positive
                        && Integer.parseInt(row.get("zero_runs")) == zero
                        && Integer.parseInt(row.get("negative_runs")) == values.length - positive - zero, "Numerical ties");
            }
            try (var quiet = new PrintStream(OutputStream.nullOutputStream())) {
                for (String position : List.of("OPENING", "CONTACT")) {
                    var single = new ArrayList<>(settings);
                    single.addAll(List.of("--position", position));
                    var result = PolicyProposalExperimentApp.run(single.toArray(String[]::new), quiet);
                    for (var observation : result.observations()) {
                        var row = observations.stream().filter(value -> value.get("repetition").equals("0")
                                && value.get("position").equals(position) && value.get("method").equals(observation.method().name())
                                && value.get("power").equals(observation.power().name())).findFirst().orElseThrow();
                        close(Double.parseDouble(row.get("fresh_search")), observation.freshSearch(), "Run zero reproduces single experiment");
                        require(row.get("policy_duplicates").equals(observation.policyDuplicates() == null
                                ? "" : observation.policyDuplicates().toString()), "Same-seed duplicate count");
                    }
                }
            }
            expect(FileAlreadyExistsException.class, () -> PolicyProposalBenchmarkApp.main(command.toArray(String[]::new)));
            Path invalid = temporary.resolve("invalid.bin");
            Files.write(invalid, new byte[]{1, 2});
            Path failed = temporary.resolve("failed");
            expect(IOException.class, () -> PolicyProposalBenchmarkApp.main(new String[]{
                    "--proposal-model", linear, "--gru-model", invalid.toString(), "--runs", "2", "--output", failed.toString()}));
            require(!Files.exists(failed.resolve("benchmark.json")), "Failed runs have no completion receipt");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("ProposalBenchmarkSelfCheck passed: " + checks + " checks");
    }

    private static void statistics() {
        var estimate = PolicyProposalBenchmarkApp.estimate(new double[]{1, 2, 3});
        close(estimate.mean(), 2, "Mean");
        close(estimate.standardError(), 1 / Math.sqrt(3), "Standard error across repetitions");
        var paired = PolicyProposalBenchmarkApp.paired(new double[]{1, 2, 3}, new double[]{0, 1, 2});
        close(paired.mean(), 1, "Constant paired advantage");
        close(paired.standardError(), 0, "Pairing preserves covariance");
        var seeds = PolicyProposalBenchmarkApp.schedule(20, 7);
        require(seeds.getFirst().equals(new PolicyProposalBenchmarkApp.Seeds(7, 7, 7, 17)), "Original first seed set");
        require(new HashSet<>(seeds).size() == 20 && seeds.equals(PolicyProposalBenchmarkApp.schedule(20, 7)), "Distinct repeatable schedule");
        require(seeds.subList(0, 2).equals(PolicyProposalBenchmarkApp.schedule(2, 7)), "Run count does not change earlier seeds");
        expect(IllegalArgumentException.class, () -> PolicyProposalBenchmarkApp.schedule(1, 7));
        expect(IllegalArgumentException.class, () -> PolicyProposalBenchmarkApp.estimate(new double[]{1}));
        expect(IllegalArgumentException.class, () -> PolicyProposalBenchmarkApp.estimate(new double[]{1, Double.NaN}));
        expect(IllegalArgumentException.class, () -> PolicyProposalBenchmarkApp.paired(new double[]{1, 2}, new double[]{1}));
    }

    private static void counters() {
        var root = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        var encoding = new MovementEncoding(root, Nation.FRANCE);
        var request = new ActionGenerationRequest(root, Nation.FRANCE, 8, 7);
        var anchor = new LinearMovementPolicy(1024);
        var pure = new PolicyActionGenerator(anchor, anchor).propose(request, encoding);
        var hybrid = new HybridActionGenerator(anchor, anchor).propose(request, encoding);
        require(hybrid.learnedSamples() == pure.samples() && hybrid.learnedDuplicates() == pure.duplicates(),
                "Hybrid retains policy-child counts before selection");
        MovementPolicy point = input -> new MovementPolicy.Prepared() {
            @Override public agents.encoding.MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                input.requirePrefix(prefix);
                double[] values = new double[input.slots().get(prefix.size()).options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                values[0] = 0;
                return values;
            }
        };
        var concentrated = new HybridActionGenerator(point, anchor).propose(request, encoding);
        require(concentrated.learnedAvailable() == 1 && concentrated.learnedSamples() == 256
                && concentrated.learnedDuplicates() == 255, "Draw-budget exhaustion is visible in hybrid counters");
        var custom = new HybridActionGenerator(new UniformActionGenerator(), new PlausibleActionGenerator(),
                anchor, new UniformActionGenerator()).propose(request, encoding);
        require(custom.learnedSamples() == null && custom.learnedDuplicates() == null, "Custom children do not invent sampling counts");
    }

    private static void receipt(Path directory) throws Exception {
        var report = JSON.object(JsonReader.read(Files.readString(directory.resolve("benchmark.json"))), "$");
        require(report.get("schema").equals(PolicyProposalBenchmarkApp.SCHEMA)
                && report.get("complete").equals(true) && report.get("test_evaluated").equals(false), "Completed receipt");
        require(!Files.exists(directory.resolve("benchmark.pending")), "Receipt was finalized");
        var files = JSON.array(report.get("files"), "files");
        require(files.size() == 9, "Four logs and five structured files");
        for (Object value : files) {
            var file = JSON.object(value, "file");
            byte[] bytes = Files.readAllBytes(directory.resolve((String) file.get("name")));
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.get("sha256")),
                    "Artifact hash");
            require(((Number) file.get("bytes")).longValue() == bytes.length, "Artifact size");
        }
    }

    private static List<Map<String, String>> table(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        String[] header = lines.getFirst().split(",", -1);
        List<Map<String, String>> result = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",", -1);
            require(fields.length == header.length, "CSV field count");
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < header.length; index++) row.put(header[index], fields[index]);
            result.add(row);
        }
        return result;
    }

    private static double[] series(List<Map<String, String>> rows, String position, String power, String method) {
        double[] result = new double[2];
        for (int run = 0; run < 2; run++) {
            String repetition = Integer.toString(run);
            result[run] = rows.stream().filter(row -> row.get("position").equals(position) && row.get("method").equals(method)
                            && row.get("repetition").equals(repetition) && (power.equals("ALL") || row.get("power").equals(power)))
                    .mapToDouble(row -> Double.parseDouble(row.get("fresh_search"))).average().orElseThrow();
        }
        return result;
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-12, message);
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked action) {
        try { action.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}