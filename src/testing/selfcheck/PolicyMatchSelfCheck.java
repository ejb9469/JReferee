package testing.selfcheck;

import _app.PolicyMatchApp;
import _app.PolicyMatchApp.*;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.Nation;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scheduling, rotation-level statistics and real A/A recording without models.
 */
public final class PolicyMatchSelfCheck {

    private PolicyMatchSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: PolicyMatchSelfCheck");
        schedule();
        statistics();
        Path temporary = Files.createTempDirectory("jreferee-policy-match-");
        try {
            collection(temporary);
            guards(temporary);
            interrupted(temporary);
            changedArchive(temporary);
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