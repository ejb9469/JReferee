package testing.selfcheck;

import _app.PolicyProposalBenchmarkApp;
import _app.PolicyProposalExperimentApp;
import _app.ProposalDiagnostics;
import agents.encoding.MovementEncoding;
import agents.execution.ProposalInspector;
import agents.generation.*;
import agents.policy.*;
import domain.*;
import game.*;
import io.json.*;
import phase.Order;
import phase.UnitId;

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
        diagnostics();
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
            var details = table(output.resolve("diagnostics.csv"));
            require(observations.size() == 252 && summary.size() == 144 && pairs.size() == 576 && details.size() == 252,
                    "Nine methods with complete paired result dimensions");
            for (var row : observations) {
                boolean sampled = !Set.of("UNIFORM", "PLAUSIBLE", "COHERENT_GRU", "COHERENT_GRU_HYBRID").contains(row.get("method"));
                require(sampled != row.get("policy_samples").isEmpty(), "Unknown sampling counts remain empty");
                if (sampled) require(Long.parseLong(row.get("policy_samples")) >= Long.parseLong(row.get("policy_duplicates")),
                        "Sample accounting");
            }
            for (var row : details) {
                boolean coherent = row.get("method").startsWith("COHERENT_");
                require(coherent != row.get("construction_attempts").isEmpty(), "Only coherent modes have construction attempts");
                if (coherent) {
                    require(row.get("blocker_actions").equals("0") && Double.parseDouble(row.get("blocker_mass")) == 0,
                            "Coherent pools contain no blockers");
                    require(Long.parseLong(row.get("construction_attempts")) == Long.parseLong(row.get("learned_available"))
                                    + Long.parseLong(row.get("construction_dead_ends")) + Long.parseLong(row.get("construction_duplicates")),
                            "Construction attempt accounting");
                }
                if (!row.get("learned_selected").isEmpty())
                    require(Integer.parseInt(row.get("learned_selected")) + Integer.parseInt(row.get("heuristic_selected"))
                            == Integer.parseInt(row.get("candidates")), "Selected child counts match pool size");
                for (String metric : List.of("blocker_mass", "convoy_blocker_mass", "dependency_mass", "caution_mass", "all_hold_mass", "hold_fraction")) {
                    double value = Double.parseDouble(row.get(metric));
                    require(Double.isFinite(value) && value >= 0 && value <= 1 + 1e-12, "Valid search mass");
                }
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
                        var detail = details.stream().filter(value -> value.get("repetition").equals("0")
                                && value.get("position").equals(position) && value.get("method").equals(observation.method().name())
                                && value.get("power").equals(observation.power().name())).findFirst().orElseThrow();
                        close(Double.parseDouble(detail.get("all_hold_mass")), observation.diagnostics().allHoldMass(),
                                "Saved all-hold mass matches search");
                        close(Double.parseDouble(detail.get("caution_mass")), observation.diagnostics().cautionMass(),
                                "Saved caution mass matches search");
                    }
                    var inspector = ProposalInspector.load(Path.of(linear), Path.of(linear), Path.of(gru));
                    for (var method : List.of(ProposalInspector.Method.COHERENT_GRU, ProposalInspector.Method.COHERENT_GRU_HYBRID)) {
                        var inspection = inspector.inspect(result.root(), Nation.FRANCE, method,
                                new ProposalInspector.Options(2, 2, 1, 2, 7, 7, 7, true));
                        var generation = inspection.generation().get(Nation.FRANCE);
                        var expected = ProposalDiagnostics.measure(result.root(), new MovementEncoding(result.root(), Nation.FRANCE).catalog(),
                                inspection.pools().get(Nation.FRANCE),
                                inspection.search().orElseThrow().results().get(Nation.FRANCE).distribution().probabilities(),
                                generation.learned(), generation.hybrid().orElse(null));
                        var measured = result.observations().stream().filter(row -> row.method().name().equals(method.name())
                                && row.power() == Nation.FRANCE).findFirst().orElseThrow();
                        require(expected.equals(measured.diagnostics()), "Viewer and benchmark construction/search parity");
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
        require(files.size() == 10, "Four logs and six structured files");
        require(JSON.array(report.get("methods"), "methods").size() == 9, "Receipt declares all nine methods");
        for (Object value : files) {
            var file = JSON.object(value, "file");
            byte[] bytes = Files.readAllBytes(directory.resolve((String) file.get("name")));
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.get("sha256")),
                    "Artifact hash");
            require(((Number) file.get("bytes")).longValue() == bytes.length, "Artifact size");
        }
    }

    private static void diagnostics() {
        var bre = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Bre);
        var lon = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        var cly = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Cly);
        var lvp = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Lvp);
        var eng = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        var root = new GameState_Movement(new GameMoment(1905, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(bre, Province.Bre, lon, Province.Lon, cly, Province.Cly, lvp, Province.Lvp, eng, Province.ENG),
                        Map.of(Province.Smy, Nation.TURKEY)));
        var bad = new ActionCandidate(Nation.ENGLAND, List.of(Order.supportMove(bre, Province.Lon, Province.Pic),
                Order.move(lon, Province.Bel), Order.move(cly, Province.NAO), Order.move(lvp, Province.NAO)), 0, ActionCandidate.Source.POLICY);
        var hold = new ActionCandidate(Nation.ENGLAND, List.of(Order.hold(bre), Order.hold(lon), Order.hold(cly), Order.hold(lvp)),
                0, ActionCandidate.Source.HEURISTIC);
        var catalog = new MovementEncoding(root, Nation.ENGLAND).catalog();
        var row = ProposalDiagnostics.measure(root, catalog, List.of(bad, hold), List.of(0.25, 0.75), null, null);
        require(row.blockerActions() == 1 && row.dependencyActions() == 1 && row.cautionActions() == 1,
                "Finding categories count each affected action once");
        require(row.orders() == 8 && row.holdOrders() == 4 && row.allHoldActions() == 1, "Pool counts are unweighted");
        close(row.blockerMass(), 0.25, "Blocker search mass");
        close(row.dependencyMass(), 0.25, "Foreign-dependency search mass");
        close(row.cautionMass(), 0.25, "Caution search mass");
        close(row.allHoldMass(), 0.75, "All-hold search mass");
        close(row.holdFraction(), 0.75, "Expected fraction of hold orders");
        require(row.construction() == null && row.mixing() == null, "Unavailable construction counters remain absent");
        expect(IllegalArgumentException.class, () -> ProposalDiagnostics.measure(root, catalog,
                List.of(bad, hold), List.of(0.8, 0.8), null, null));
        expect(IllegalStateException.class, () -> ProposalDiagnostics.measure(root, catalog, List.of(bad), List.of(1.0),
                new CoherentPolicyActionGenerator.Result(List.of(bad), 1, 1, 0, 0, 4), null));
        var empty = new ActionCandidate(Nation.TURKEY, List.of(), 0, ActionCandidate.Source.POLICY);
        var idle = ProposalDiagnostics.measure(root, new MovementEncoding(root, Nation.TURKEY).catalog(),
                List.of(empty), List.of(1.0), null, null);
        require(idle.emptyActions() == 1 && idle.allHoldActions() == 0 && idle.holdFraction() == 0,
                "Empty center-only action is separate from all-hold");
    }

    static List<Map<String, String>> table(Path path) throws IOException {
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

    static double[] series(List<Map<String, String>> rows, String position, String power, String method) {
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