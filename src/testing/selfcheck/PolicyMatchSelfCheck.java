package testing.selfcheck;

import _app.PolicyMatchApp;
import _app.PolicyMatchApp.*;
import adjudication.Justice;
import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.Nation;
import game.GameState;
import game.GameState_Adjustments;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scheduling, rotation-level statistics and real A/A recording without models.
 * Optional artifacts also exercise isolated value comparisons through the CLI.
 */
public final class PolicyMatchSelfCheck {

    private PolicyMatchSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0 && args.length != 5)
            throw new IllegalArgumentException("Usage: PolicyMatchSelfCheck"
                    + " [<reference-value.bin> <candidate-value.bin> <anchor.bin>"
                    + " <shared-gru.bin> <different-gru.bin>]");
        schedule();
        statistics();
        Path temporary = Files.createTempDirectory("jreferee-policy-match-");
        try {
            collection(temporary);
            guards(temporary);
            interrupted(temporary);
            changedArchive(temporary);
            if (args.length == 5)
                values(temporary, args);
            System.out.println("PolicyMatchSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static void schedule() throws Exception {

        List<Match> matches = PolicyMatchApp.schedule(2, 106);
        require(matches.size() == 28, "Two rotations must contain 28 games");
        Set<Long> seeds = new HashSet<>();
        for (int pair = 0; pair < 14; pair++) {
            Match a = matches.get(pair * 2), b = matches.get(pair * 2 + 1);
            require(a.pair() == pair && b.pair() == pair && a.rotation() == pair / 7
                    && b.rotation() == a.rotation(), "Pair/rotation mismatch");
            require(a.power() == Nation.values()[pair % 7] && b.power() == a.power()
                            && a.seed() == 106 + pair && b.seed() == a.seed() && seeds.add(a.seed()),
                    "Power rotation or matched seed changed");
            require(a.side() != b.side() && a.side() == (pair % 2 == 0 ? Side.REFERENCE : Side.CANDIDATE),
                    "Leg order is not counterbalanced");
        }
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.schedule(0, 7));
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.schedule(PolicyMatchApp.MAX_ROTATIONS + 1, 7));
        expect(ArithmeticException.class, () -> PolicyMatchApp.schedule(1, Long.MAX_VALUE - 5));
        require(PolicyMatchApp.schedule(1, Long.MAX_VALUE - 6).getLast().seed() == Long.MAX_VALUE,
                "Last nonoverflowing schedule was rejected");

    }

    private static void statistics() throws Exception {

        double[] reference = new double[14], candidate = new double[14];
        Arrays.fill(reference, 0.2);
        Arrays.fill(candidate, 0, 7, 0.3);
        Arrays.fill(candidate, 7, 14, 0.5);
        Comparison result = PolicyMatchApp.compare(reference, candidate, 7);
        close(result.meanDelta(), 0.2, "Paired score sign or mean differs");
        close(result.standardError().orElseThrow(), 0.1, "Seats were treated as independent rotations");
        require(result.rotationDeltas().size() == 2, "Wrong uncertainty sample count");
        Comparison one = PolicyMatchApp.compare(Arrays.copyOf(reference, 7), Arrays.copyOf(candidate, 7), 7);
        require(one.standardError().isEmpty(), "One rotation acquired a zero uncertainty estimate");
        close(PolicyMatchApp.compare(candidate, reference, 7).meanDelta(), -0.2, "Swapping sides did not reverse delta");
        close(PolicyMatchApp.compare(new double[]{0}, new double[]{1}, 1).meanDelta(), 1, "Solo reward scale changed");
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.compare(new double[6], new double[6], 7));
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.compare(new double[]{0}, new double[]{Double.NaN}, 1));
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.compare(new double[]{0}, new double[]{1.01}, 1));

    }

    private static GameRunner.Options options(long seed) {
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        AgentSettings settings = new AgentSettings(
                AgentSettings.Method.DIL_PIKL, 1, 1, 1, 1, PolicyOutput.AVERAGE);
        for (Nation power : Nation.values())
            seats.put(power, settings);
        return new GameRunner.Options(1901, seed, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static AgentSettings agent(GameRunner.Options options) {
        return options.agents().get(Nation.ENGLAND);
    }

    private static void collection(Path temporary) throws Exception {

        Path output = temporary.resolve("aa");
        GameRunner.Options options = options(106);
        Map<String, Object> report = ValueDatasetJson.parse(
                PolicyMatchApp.run(output, options, agent(options), 1, message -> { }));
        require(ValueDatasetJson.count(report, "games") == 14 && ValueDatasetJson.count(report, "pairs") == 7
                && Boolean.TRUE.equals(report.get("complete")), "Missing completed comparison");
        require(!report.containsKey("comparison")
                        && !ValueDatasetJson.parse(Files.readString(output.resolve("plan.json"))).containsKey("comparison"),
                "Legacy policy comparison format changed");
        Map<String, Object> overall = ValueDatasetJson.object(report.get("overall"));
        close(((Number) overall.get("mean_delta")).doubleValue(), 0, "A/A comparison has a nonzero effect");
        require(overall.get("standard_error") == null, "Single rotation reports uncertainty");
        List<Map<String, Object>> rows = Files.readAllLines(output.resolve("games.jsonl")).stream()
                .map(ValueDatasetJson::parse).toList();
        for (int pair = 0; pair < 7; pair++) {
            Map<String, Object> a = rows.get(pair * 2), b = rows.get(pair * 2 + 1);
            require(a.get("focal_sos").equals(b.get("focal_sos"))
                    && a.get("logical_key").equals(b.get("logical_key"))
                    && !a.get("game_id").equals(b.get("game_id")), "A/A paired streams or role identities differ");
            require("ARTIFICIAL_CUTOFF".equals(a.get("termination"))
                    && "ARTIFICIAL_CUTOFF".equals(b.get("termination")), "Short fixture became a natural ending");
        }
        Map<String, Object> endings = ValueDatasetJson.object(report.get("endings"));
        for (Side side : Side.values()) {
            var counts = ValueDatasetJson.object(endings.get(side.name()));
            require(ValueDatasetJson.count(counts, "cutoff_games") == 7
                    && ValueDatasetJson.count(counts, "solo_games") == 0, "Ending counts differ");
        }
        require(!Files.exists(output.resolve("cache")), "Evaluation created a training cache");
        require(report.equals(ValueDatasetJson.parse(PolicyMatchApp.verify(output, message -> { }))),
                "Readback summary differs");
        Path marker = output.resolve("manifest.json");
        String original = Files.readString(marker);
        Map<String, Object> altered = ValueDatasetJson.parse(original);
        ValueDatasetJson.object(altered.get("overall")).put("mean_delta", 0.5);
        Files.writeString(marker, SelfPlayFiles.json(altered));
        expect(IOException.class, () -> PolicyMatchApp.verify(output, message -> { }));
        Files.writeString(marker, original);
        expect(FileAlreadyExistsException.class,
                () -> PolicyMatchApp.run(output, options, agent(options), 1, message -> { }));
        Files.writeString(output.resolve("games.jsonl"), " ", StandardOpenOption.APPEND);
        expect(IOException.class, () -> PolicyMatchApp.verify(output, message -> { }));

    }

    private static void guards(Path temporary) throws Exception {

        Path output = temporary.resolve("invalid");
        GameRunner.Options options = options(7);
        AgentSettings different = new AgentSettings(
                AgentSettings.Method.DIL_PIKL, 2, 1, 1, 1, PolicyOutput.AVERAGE);
        expect(IllegalArgumentException.class,
                () -> PolicyMatchApp.run(output, options, different, 1, message -> { }));
        Map<Nation, AgentSettings> seats = new EnumMap<>(options.agents());
        seats.put(Nation.FRANCE, different);
        GameRunner.Options mixed = new GameRunner.Options(1901, 7, options.scoring(),
                options.movementPolicy(), 1, 0, seats);
        expect(IllegalArgumentException.class,
                () -> PolicyMatchApp.run(output, mixed, agent(options), 1, message -> { }));
        expect(NoSuchFileException.class, () -> PolicyMatchApp.run(
                temporary.resolve("missing-parent/match"), options, agent(options), 1, message -> { }));
        require(!Files.exists(output), "Preflight failure created output");

    }

    private static void interrupted(Path temporary) throws Exception {

        Path output = temporary.resolve("interrupted");
        GameRunner.Options options = options(20);
        RuntimeException sentinel = new IllegalStateException("Stop after a verified game");
        try {
            PolicyMatchApp.run(output, options, agent(options), 1, message -> {
                if (message.startsWith("Replay verified:"))
                    throw sentinel;
            });
            throw new AssertionError("Observer failure was swallowed");
        } catch (IllegalStateException exception) {
            require(exception == sentinel, "Observer failure was replaced");
        }
        require(Files.readAllLines(output.resolve("games.jsonl")).size() == 1
                && !Files.exists(output.resolve("manifest.json")), "Partial pair claimed completion");
        SelfPlayDataset.open(output.resolve("archives/pair-00000-reference"));

    }

    private static void changedArchive(Path temporary) throws Exception {

        Path output = temporary.resolve("changed");
        GameRunner.Options options = options(40);
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.run(output, options, agent(options), 1, message -> {
            if (!message.startsWith("Checking the complete plan"))
                return;
            try {
                Files.writeString(output.resolve("archives/pair-00000-reference/outcome.json"),
                        "\n", StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }));
        require(!Files.exists(output.resolve("manifest.json")), "Changed archive acquired a completion marker");

    }

    private static AgentSettings neural(AgentModels movement, NeuralValueModel value) {
        return new AgentSettings(AgentSettings.Method.DIL_PIKL, 1, 1, 1, 1, PolicyOutput.AVERAGE,
                AgentSettings.Generator.COHERENT_GRU_HYBRID, AgentSettings.Generator.UNIFORM,
                Optional.of(movement), Optional.of(value));
    }

    private static void values(Path temporary, String[] args) throws Exception {

        NeuralValueModel oldValue = NeuralValueModel.load(Path.of(args[0]));
        NeuralValueModel newValue = NeuralValueModel.load(Path.of(args[1]));
        AgentModels movement = AgentModels.load(Path.of(args[2]), Optional.of(Path.of(args[3])));
        AgentModels different = AgentModels.load(Path.of(args[2]), Optional.of(Path.of(args[4])));
        require(!oldValue.sha256().equals(newValue.sha256()), "Supply distinct value models for the fixture");
        require(!movement.identities().equals(different.identities()), "Supply distinct GRUs for the guard fixture");
        AgentSettings base = neural(movement, oldValue), candidate = base.withValueModel(newValue);
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, base);
        GameRunner.Options reference = new GameRunner.Options(1902, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, Justice.SHUFFLE_SEED_DEFAULT, seats);
        Path invalid = temporary.resolve("invalid-value");
        expect(IllegalArgumentException.class,
                () -> PolicyMatchApp.run(invalid, reference, candidate, 1, message -> { }));
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.runValue(
                invalid, reference, neural(different, newValue), 1, message -> { }));
        AgentSettings budget = new AgentSettings(base.method(), 2, base.iterations(), base.rolloutCount(),
                base.rolloutLimit(), base.output(), base.rootGenerator(), base.rolloutGenerator(),
                base.models(), candidate.valueModel());
        expect(IllegalArgumentException.class,
                () -> PolicyMatchApp.runValue(invalid, reference, budget, 1, message -> { }));
        GameRunner.Options modelFree = options(7);
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.runValue(
                invalid, modelFree, agent(modelFree).withValueModel(newValue), 1, message -> { }));
        require(!Files.exists(invalid), "Value preflight failure created output");
        IllegalStateException stop = new IllegalStateException("Loaded policy configuration accepted");
        try {
            PolicyMatchApp.run(temporary.resolve("policy-artifacts"), reference, neural(different, oldValue),
                    1, message -> { throw stop; });
            throw new AssertionError("Policy fixture did not reach its first game");
        } catch (IllegalStateException exception) {
            require(exception == stop, "Legacy loaded-policy comparison was rejected");
        }

        Path output = temporary.resolve("value");
        PolicyMatchApp.main(new String[]{"--value", args[0], args[1], args[2], args[3],
                "1902", "1", "1", "7", "1", output.toString()});
        Map<String, Object> plan = ValueDatasetJson.parse(Files.readString(output.resolve("plan.json")));
        Map<String, Object> report = ValueDatasetJson.parse(Files.readString(output.resolve("manifest.json")));
        require("VALUE".equals(plan.get("comparison")) && "VALUE".equals(report.get("comparison")),
                "Value comparison was not explicitly recorded");
        require(same(plan.get("reference_seat"), SelfPlayRecorder.settings(base))
                        && same(plan.get("candidate_seat"), SelfPlayRecorder.settings(candidate)),
                "CLI changed a frozen artifact or common search setting");
        require(ValueDatasetJson.count(report, "games") == 14
                        && ValueDatasetJson.count(report, "pairs") == 7
                        && !Files.exists(output.resolve("cache")),
                "Value fixture is not a complete evaluation-only rotation");
        List<Map<String, Object>> rows = Files.readAllLines(output.resolve("games.jsonl")).stream()
                .map(ValueDatasetJson::parse).toList();
        List<Match> schedule = PolicyMatchApp.schedule(1, 7);
        GameState winter = null;
        for (int index = 0; index < rows.size(); index++) {
            Match match = schedule.get(index);
            SelfPlayDataset.GameExample game = SelfPlayDataset.open(
                    output.resolve(ValueDatasetJson.text(rows.get(index), "directory")));
            require(game.configuration().seed() == match.seed() && game.configuration().lastYear() == 1902,
                    "Value pair changed its seed or cutoff");
            for (Nation power : Nation.values()) {
                var actual = game.configuration().seats().get(power);
                NeuralValueModel expected = match.side() == Side.CANDIDATE && power == match.power()
                        ? newValue : oldValue;
                var identity = actual.valueModel().orElseThrow();
                require(actual.movementModels().equals(movement.identities())
                                && identity.model().equals(expected.sha256())
                                && identity.checkpoint().equals(expected.checkpointSha256())
                                && identity.dataset().equals(expected.datasetFingerprint()),
                        "Wrong value or movement artifacts for " + match + " seat=" + power);
            }
            if (winter == null)
                winter = game.phases().stream().map(SelfPlayDataset.Phase::state)
                        .filter(state -> state instanceof GameState_Adjustments && state.moment().year() == 1901)
                        .findFirst().orElse(null);
        }
        require(winter != null, "Value fixture never reached a nonterminal winter");
        require(base.createEvaluator(reference, 7).evaluate(winter).equals(
                        oldValue.predict(winter.moment(), winter.board()))
                        && candidate.createEvaluator(reference, 7).evaluate(winter).equals(
                        newValue.predict(winter.moment(), winter.board())),
                "A nonterminal leaf did not use its assigned value model");
        require(report.equals(ValueDatasetJson.parse(PolicyMatchApp.verify(output, message -> { }))),
                "Value readback differs");
        expect(FileAlreadyExistsException.class,
                () -> PolicyMatchApp.runValue(output, reference, candidate, 1, message -> { }));
        comparisonMarker(output, false);
        identicalValues(temporary, oldValue);
        System.out.println("Value comparison passed: fixed movement artifacts, six reference opponents, "
                + "nonterminal values and receipt guards.");

    }

    private static void identicalValues(Path temporary, NeuralValueModel model) throws Exception {

        GameRunner.Options original = options(7);
        AgentSettings base = agent(original).withValueModel(model);
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, base);
        GameRunner.Options reference = new GameRunner.Options(
                original.lastYear(), original.seed(), original.scoring(), original.movementPolicy(),
                original.adjudicationTrials(), original.adjudicationSeed(), seats);
        Path output = temporary.resolve("identical-values");
        Map<String, Object> report = ValueDatasetJson.parse(
                PolicyMatchApp.runValue(output, reference, base, 1, message -> { }));
        close(((Number) ValueDatasetJson.object(report.get("overall")).get("mean_delta")).doubleValue(),
                0, "Identical values produced an A/A effect");
        comparisonMarker(output, true);

    }

    private static void comparisonMarker(Path output, boolean identicalValues) throws Exception {

        Path planPath = output.resolve("plan.json"), manifestPath = output.resolve("manifest.json");
        String originalPlan = Files.readString(planPath), originalManifest = Files.readString(manifestPath);
        try {
            for (boolean remove : new boolean[]{true, false}) {
                Map<String, Object> plan = ValueDatasetJson.parse(originalPlan);
                if (remove)
                    plan.remove("comparison");
                else
                    plan.put("comparison", "UNKNOWN");
                Files.writeString(planPath, SelfPlayFiles.json(plan) + "\n");
                Map<String, Object> manifest = ValueDatasetJson.parse(originalManifest);
                ValueDatasetJson.object(manifest.get("files")).put("plan.json", SelfPlayFiles.object(
                        "bytes", Files.size(planPath), "sha256", ValueDatasetJson.hash(planPath)));
                Files.writeString(manifestPath, SelfPlayFiles.json(manifest) + "\n");
                expect(remove && identicalValues ? IOException.class : IllegalArgumentException.class,
                        () -> PolicyMatchApp.verify(output, message -> { }));
            }
            Files.writeString(planPath, originalPlan);
            Map<String, Object> manifest = ValueDatasetJson.parse(originalManifest);
            manifest.remove("comparison");
            Files.writeString(manifestPath, SelfPlayFiles.json(manifest) + "\n");
            expect(IOException.class, () -> PolicyMatchApp.verify(output, message -> { }));
        } finally {
            Files.writeString(planPath, originalPlan);
            Files.writeString(manifestPath, originalManifest);
        }

    }

    private static boolean same(Object a, Object b) {
        return ValueDatasetJson.parse(SelfPlayFiles.json(a)).equals(ValueDatasetJson.parse(SelfPlayFiles.json(b)));
    }

    private interface Checked { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, Checked action) throws Exception {
        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception))
                return;
            throw exception;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) <= 1e-12, message + ": " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}