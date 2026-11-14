package testing.selfcheck;

import _app.*;
import agents.encoding.*;
import agents.evaluation.*;
import agents.execution.ProposalInspector;
import agents.execution.ProposalInspector.Method;
import agents.generation.*;
import agents.policy.*;
import agents.search.*;
import domain.*;
import game.*;
import io.json.*;
import io.training.*;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Fixed pools, checkpoint prefixes, shared fresh samples and paired reports.
 */
public final class SearchBudgetBenchmarkSelfCheck {

    private static int checks;
    private static final PolicyProposalBenchmarkApp.Seeds SEEDS = new PolicyProposalBenchmarkApp.Seeds(7, 7, 7, 17);
    private SearchBudgetBenchmarkSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Usage: SearchBudgetBenchmarkSelfCheck [linear-policy.bin gru-model.bin]");
        options();
        var uniform = new LinearMovementPolicy(1024);
        var experiment = new SearchBudgetExperiment(uniform, uniform, uniform);
        var options = new SearchBudgetExperiment.Options(2, 1, 2, 2, List.of(16, 64, 256),
                SearchBudgetExperiment.Options.defaults().methods());
        compare(experiment, state(1905, GamePhase.FALL_MOVEMENT), options);
        failures();
        if (args.length == 2) {
            var loaded = SearchBudgetExperiment.load(Path.of(args[0]), Path.of(args[0]), Path.of(args[1]));
            parity(loaded, args);
            files(args);
        }
        System.out.println("SearchBudgetBenchmarkSelfCheck passed: " + checks + " checks");
    }

    private static void compare(SearchBudgetExperiment experiment, GameState_Movement root, SearchBudgetExperiment.Options options) {
        var result = experiment.run(root, options, SEEDS);
        require(result.root().equals(root) && result.methods().size() == 3, "Complete method comparison");
        var shorter = new SearchBudgetExperiment.Options(2, 1, 2, 2, List.of(16, 64), options.methods());
        var prefix = experiment.run(root, shorter, SEEDS);
        require(result.opponents().equals(prefix.opponents()), "Checkpoint schedule cannot change fresh opponent draws");
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(misc.scoring.ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), SearchBudgetBenchmarkSelfCheck::game, 1, 2, SEEDS.rollout());
        for (int method = 0; method < result.methods().size(); method++) {
            var run = result.methods().get(method);
            var earlier = prefix.methods().get(method);
            require(run.pools().equals(earlier.pools()) && run.freshValues().equals(earlier.freshValues()),
                    "Pools and fresh payoff tables are independent of iteration budget");
            require(run.history().subList(0, 2).equals(earlier.history()), "Exact search prefix reuse");
            Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
            run.pools().forEach((power, pool) -> pools.put(power, pool.candidates()));
            long total = pools.values().stream().mapToLong(List::size).sum();
            require(run.work().fresh().requests() == total * 2, "Fresh evaluations occur once per candidate/sample");
            require(run.work().search().requests() == run.work().requestsPerIteration() * 256,
                    "Trace pays for 256 iterations, not 16 + 64 + 256");
            for (int index = 0; index < options.checkpoints().size(); index++) {
                int iterations = options.checkpoints().get(index);
                Game simulation = game(root);
                ActionGenerator forbidden = (r, c) -> { throw new AssertionError("Explicit pools must not be regenerated"); };
                var standalone = PaperSearchProfile.forState(root).solver(evaluator, forbidden, 2, iterations,
                        SEEDS.search(), PolicyOutput.AVERAGE).solve(simulation, pools);
                require(standalone.equals(run.history().get(index)), "Checkpoint equals a standalone same-seed solve");
                require(simulation.snapshot().equals(root) && simulation.getSnapshot() == null, "Root restored");
                for (var entry : run.pools().entrySet()) {
                    var solved = run.history().get(index).results().get(entry.getKey());
                    require(solved.distribution().candidates().equals(entry.getValue().candidates()), "Candidate indexing and priors retained");
                    require(entry.getValue().sha256().equals(SearchBudgetExperiment.fingerprint(entry.getValue().inspection())), "Stable pool identity");
                    if (entry.getKey() == Nation.TURKEY) {
                        require(solved.distribution().candidates().size() == 1
                                && solved.distribution().candidates().getFirst().orders().isEmpty(), "Center-only empty action retained");
                        close(solved.distribution().probabilities().getFirst(), 1, "Center-only policy");
                    }
                }
            }
            expect(UnsupportedOperationException.class, run.pools()::clear);
            expect(UnsupportedOperationException.class, run.history()::clear);
            expect(UnsupportedOperationException.class, () -> run.freshValues().values().iterator().next().getFirst().clear());
        }
        expect(UnsupportedOperationException.class, () -> result.opponents().getFirst().clear());
    }

    private static void parity(SearchBudgetExperiment experiment, String[] models) throws Exception {
        var options = new SearchBudgetExperiment.Options(2, 1, 2, 2, List.of(16, 32), SearchBudgetExperiment.Options.defaults().methods());
        try (var quiet = new PrintStream(OutputStream.nullOutputStream())) {
            for (String position : List.of("OPENING", "CONTACT")) {
                var original = PolicyProposalExperimentApp.run(new String[]{"--proposal-model", models[0], "--anchor-model", models[0],
                        "--gru-model", models[1], "--position", position, "--candidates", "2", "--iterations", "16",
                        "--rollouts", "1", "--rollout-candidates", "2", "--opponent-samples", "2"}, quiet);
                var result = experiment.run(original.root(), options, SEEDS);
                for (var method : result.methods())
                    for (var entry : method.pools().entrySet()) {
                        var expected = original.observations().stream().filter(row -> row.method().name().equals(method.method().name())
                                && row.power() == entry.getKey()).findFirst().orElseThrow();
                        var probabilities = method.history().getFirst().results().get(entry.getKey()).distribution().probabilities();
                        close(PolicyProposalBenchmarkApp.estimate(SearchBudgetExperiment.mixture(
                                        method.freshValues().get(entry.getKey()), probabilities)).mean(), expected.freshSearch(),
                                "Original experiment fresh-search parity");
                        var inspection = entry.getValue().inspection();
                        var generated = inspection.generation().get(entry.getKey());
                        var diagnostics = ProposalDiagnostics.measure(original.root(), new MovementEncoding(original.root(), entry.getKey()).catalog(),
                                entry.getValue().candidates(), probabilities, generated == null ? null : generated.learned(),
                                generated == null ? null : generated.hybrid().orElse(null));
                        require(diagnostics.equals(expected.diagnostics()), "Original construction and playing-mass parity");
                    }
            }
        }
    }

    private static void files(String[] models) throws Exception {
        String fingerprint = GruMovementPolicy.load(Path.of(models[1])).datasetIndexSha256();
        List<MovementDataset.Example> examples = new ArrayList<>();
        for (String game : List.of("a", "b"))
            for (GamePhase phase : game.equals("a") ? List.of(GamePhase.SPRING_MOVEMENT, GamePhase.FALL_MOVEMENT)
                    : List.of(GamePhase.FALL_MOVEMENT)) {
                var input = new MovementEncoding(state(1905, phase), Nation.ENGLAND).input();
                examples.add(new MovementDataset.Example("fixture", game, game.equals("a") ? null : "Full", input,
                        Collections.nCopies(input.slots().size(), 0)));
            }
        var selection = ValidationPositions.select(dataset(examples, fingerprint), 2, 7);
        Path temporary = Files.createTempDirectory("jreferee-search-budgets-");
        try {
            Path positions = temporary.resolve("positions.json"), output = temporary.resolve("results");
            ValidationPositions.save(selection, positions);
            var command = new ArrayList<>(List.of("--positions", positions.toString(), "--output", output.toString(),
                    "--proposal-model", models[0], "--anchor-model", models[0], "--gru-model", models[1], "--runs", "2",
                    "--candidates", "2", "--rollout-candidates", "2", "--opponent-samples", "2", "--checkpoints", "2,4,8"));
            SearchBudgetBenchmarkApp.main(command.toArray(String[]::new));
            require(Files.readString(positions).equals(Files.readString(output.resolve("positions.json"))), "Frozen input copied unchanged");
            var observations = ProposalBenchmarkSelfCheck.table(output.resolve("observations.csv"));
            var candidates = ProposalBenchmarkSelfCheck.table(output.resolve("candidates.csv"));
            var fresh = ProposalBenchmarkSelfCheck.table(output.resolve("fresh.csv"));
            var policies = ProposalBenchmarkSelfCheck.table(output.resolve("policies.csv"));
            var diagnostics = ProposalBenchmarkSelfCheck.table(output.resolve("diagnostics.csv"));            require(observations.size() == 162 && diagnostics.size() == 162 && candidates.size() == 90
                    && fresh.size() == 180 && policies.size() == 270, "Three methods/budgets without repeated generation or fresh samples");
            for (var row : observations) {
                var pool = candidates.stream().filter(c -> cell(c, row)).toList();
                var playing = policies.stream().filter(c -> cell(c, row) && c.get("iterations").equals(row.get("iterations"))).toList();
                require(pool.size() == playing.size() && pool.size() == Integer.parseInt(row.get("candidates")), "Aligned saved pools");
                double[] mixture = new double[2];
                double mass = 0;
                for (var policy : playing) {
                    double probability = Double.parseDouble(policy.get("probability"));
                    mass += probability;
                    var samples = fresh.stream().filter(c -> cell(c, row) && c.get("candidate").equals(policy.get("candidate"))).toList();
                    require(samples.size() == 2, "One saved payoff per opponent sample");
                    for (var sample : samples) mixture[Integer.parseInt(sample.get("sample"))] += probability * Double.parseDouble(sample.get("value"));
                }
                close(mass, 1, "Saved playing distribution");
                var estimate = PolicyProposalBenchmarkApp.estimate(mixture);
                close(Double.parseDouble(row.get("fresh_search")), estimate.mean(), "Reconstruct search value from artifacts");
                close(Double.parseDouble(row.get("opponent_se")), estimate.standardError(), "Reconstruct opponent error");
                for (var other : observations.stream().filter(c -> cell(c, row)).toList())
                    require(row.get("fresh_anchor").equals(other.get("fresh_anchor")) && row.get("fresh_best").equals(other.get("fresh_best")),
                            "Fixed-pool reference values remain unchanged across checkpoints");
            }
            for (var row : diagnostics) {
                if (row.get("method").startsWith("COHERENT_"))
                    require(row.get("blocker_actions").equals("0") && !row.get("construction_attempts").isEmpty(), "Coherent diagnostics");
                if (row.get("power").equals("TURKEY"))
                    require(row.get("empty_actions").equals("1") && row.get("all_hold_actions").equals("0"), "Empty is separate from all-hold");
            }
            var pairs = ProposalBenchmarkSelfCheck.table(output.resolve("paired.csv"));
            require(pairs.stream().anyMatch(r -> r.get("method").equals(r.get("baseline_method"))
                    && r.get("iterations").equals("8") && r.get("baseline_iterations").equals("2")), "Within-method budget comparison");
            for (var row : pairs) {
                require(row.get("method").equals(row.get("baseline_method")) || row.get("iterations").equals(row.get("baseline_iterations")),
                        "Comparisons change one factor at a time");
                Map<String, List<Double>> differences = new TreeMap<>();
                var values = gameMeans(observations, selection, row, row.get("method"), row.get("iterations"));
                var baseline = gameMeans(observations, selection, row, row.get("baseline_method"), row.get("baseline_iterations"));
                require(values.keySet().equals(baseline.keySet()), "Source-game pairing");
                values.forEach((game, value) -> differences.put(game, List.of(value - baseline.get(game))));
                var expected = ValidationProposalBenchmarkApp.grouped(differences);
                close(Double.parseDouble(row.get("mean_delta")), expected.mean(), "Game-paired delta");
                if (expected.standardError() == null) require(row.get("game_se").isEmpty(), "Singleton game SE unavailable");
                else close(Double.parseDouble(row.get("game_se")), expected.standardError(), "Game-paired SE");
            }
            var json = new JsonAccess((path, message) -> new IllegalArgumentException(path + ": " + message));
            var receipt = json.object(JsonReader.read(Files.readString(output.resolve("benchmark.json"))), "$");
            require(receipt.get("schema").equals(SearchBudgetBenchmarkApp.SCHEMA) && Boolean.TRUE.equals(receipt.get("complete"))
                    && Boolean.FALSE.equals(receipt.get("test_evaluated")) && Boolean.TRUE.equals(receipt.get("trace_reused"))
                    && Boolean.TRUE.equals(receipt.get("fresh_reused")), "Complete shared-work receipt");
            var artifacts = json.object(receipt.get("files"), "files");
            require(artifacts.size() == 12 && !Files.exists(output.resolve("benchmark.pending")), "All artifacts finalized");
            for (var entry : artifacts.entrySet())
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output.resolve(entry.getKey()))))
                        .equals(entry.getValue()), "Artifact hash");
            expect(FileAlreadyExistsException.class, () -> SearchBudgetBenchmarkApp.main(command.toArray(String[]::new)));
            Path wrong = temporary.resolve("wrong.json");
            String other = fingerprint.equals("a".repeat(64)) ? "b".repeat(64) : "a".repeat(64);
            Files.writeString(wrong, Files.readString(positions).replace("\"dataset_sha256\":\"" + fingerprint + "\"",
                    "\"dataset_sha256\":\"" + other + "\""));
            command.set(command.indexOf("--positions") + 1, wrong.toString());
            command.set(command.indexOf("--output") + 1, temporary.resolve("failed").toString());
            expect(IOException.class, () -> SearchBudgetBenchmarkApp.main(command.toArray(String[]::new)));
            require(!Files.exists(temporary.resolve("failed/benchmark.json")), "Dataset mismatch has no completion receipt");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, Double> gameMeans(List<Map<String, String>> rows, ValidationPositions.Selection selection,
                                                 Map<String, String> comparison, String method, String iterations) {
        Map<String, List<Double>> games = new TreeMap<>();
        for (var position : selection.positions()) {
            if (!position.groups().contains(comparison.get("group"))) continue;
            for (int repetition = 0; repetition < 2; repetition++) {
                String run = Integer.toString(repetition);
                var values = rows.stream().filter(r -> r.get("position").equals(position.rank()) && r.get("repetition").equals(run)
                                && r.get("method").equals(method) && r.get("iterations").equals(iterations)
                                && (comparison.get("power").equals("ALL") || r.get("power").equals(comparison.get("power"))))
                        .mapToDouble(r -> Double.parseDouble(r.get("fresh_search"))).toArray();
                if (values.length > 0) games.computeIfAbsent(position.game(), ignored -> new ArrayList<>()).add(Arrays.stream(values).average().orElseThrow());
            }
        }
        Map<String, Double> means = new TreeMap<>();
        games.forEach((game, values) -> means.put(game, values.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
        return means;
    }

    private static boolean cell(Map<String, String> a, Map<String, String> b) {
        return List.of("position", "repetition", "method", "power", "pool_sha256").stream().allMatch(k -> a.get(k).equals(b.get(k)));
    }

    private static void options() {
        var defaults = SearchBudgetExperiment.Options.defaults();
        require(defaults.checkpoints().equals(List.of(16, 64, 256)), "Default checkpoint budgets");
        expect(IllegalArgumentException.class, () -> new SearchBudgetExperiment.Options(8, 1, 8, 1, defaults.checkpoints(), defaults.methods()));
        for (var bad : List.of(List.<Integer>of(), List.of(16), List.of(0, 16), List.of(64, 16), List.of(16, 16)))
            expect(IllegalArgumentException.class, () -> new SearchBudgetExperiment.Options(8, 1, 8, 16, bad, defaults.methods()));
        expect(IllegalArgumentException.class, () -> new SearchBudgetExperiment.Options(8, 1, 8, 16, defaults.checkpoints(),
                List.of(Method.PLAUSIBLE, Method.PLAUSIBLE)));
        expect(IllegalArgumentException.class, () -> SearchBudgetExperiment.mixture(List.of(List.of(1.0, 0.0)), List.of(0.5)));
        expect(IllegalArgumentException.class, () -> SearchBudgetBenchmarkApp.main(new String[]{"--positions", "missing", "--iterations", "16"}));
        expect(IllegalArgumentException.class, () -> SearchBudgetBenchmarkApp.main(new String[]{"--positions", "a", "--positions", "b"}));
    }

    private static void failures() {
        var uniform = new LinearMovementPolicy(1024);
        MovementPolicy broken = input -> { throw new IllegalStateException("Deliberate model failure"); };
        expect(IllegalStateException.class, () -> new SearchBudgetExperiment(uniform, uniform, broken)
                .run(state(1905, GamePhase.FALL_MOVEMENT), SearchBudgetExperiment.Options.defaults(), SEEDS));
        Map<Province, String> chosen = Map.of(Province.Lon, "ARMY Lon MOVE Bel -",
                Province.NTH, "FLEET NTH CONVOY Lon Bel", Province.NWG, "FLEET NWG CONVOY Lon Bel");
        MovementPolicy impossible = input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                var slot = input.slots().get(prefix.size());
                double[] values = new double[slot.options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                for (int i = 0; i < values.length; i++)
                    if (slot.options().get(i).label().equals(chosen.get(slot.location()))) values[i] = 0;
                return values;
            }
        };
        Map<UnitId, Province> units = new LinkedHashMap<>();
        units.put(new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon), Province.Lon);
        for (Province sea : List.of(Province.NTH, Province.NWG))
            units.put(new UnitId(Nation.ENGLAND, UnitType.FLEET, sea), sea);
        var root = new GameState_Movement(new GameMoment(1905, GamePhase.FALL_MOVEMENT), new BoardState(units, Map.of()));
        try {
            new SearchBudgetExperiment(uniform, uniform, impossible).run(root,
                    new SearchBudgetExperiment.Options(2, 1, 2, 2, List.of(1, 2), List.of(Method.COHERENT_GRU)), SEEDS);
            throw new AssertionError("Expected coherent exhaustion");
        } catch (IllegalStateException exception) {
            require(exception.getCause() instanceof ProposalInspector.CoherentExhaustion
                    && exception.getMessage().contains("COHERENT_GRU exhausted for ENGLAND")
                    && exception.getMessage().contains("construction="), "Empty coherent pool reports method, power and construction counts");
        }
    }

    private static GameState_Movement state(int year, GamePhase phase) {
        return new GameState_Movement(new GameMoment(year, phase),
                new BoardState(Map.of(new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon), Province.Lon,
                        new UnitId(Nation.FRANCE, UnitType.FLEET, Province.StpSC), Province.StpSC),
                        Map.of(Province.Lon, Nation.ENGLAND, Province.Stp, Nation.FRANCE, Province.Smy, Nation.TURKEY)));
    }

    private static MovementDataset dataset(List<MovementDataset.Example> rows, String fingerprint) {
        return new MovementDataset() {
            @Override public Cursor open(EncodingPurpose split) {
                require(split == EncodingPurpose.VALIDATION, "Only validation is opened");
                return new Cursor() {
                    private int index;
                    @Override public Example next() { return index == rows.size() ? null : rows.get(index++); }
                    @Override public Statistics statistics() { return new Statistics(index, 0, 0, 0, 0, 0, 0, index == rows.size() ? "b".repeat(64) : null); }
                    @Override public void close() { }
                };
            }
            @Override public long examples(EncodingPurpose split) { return rows.size(); }
            @Override public String fingerprint() { return fingerprint; }
        };
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }
    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-12, message);
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked action) {
        try { action.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++; return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}