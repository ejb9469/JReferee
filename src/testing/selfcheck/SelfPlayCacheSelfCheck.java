package testing.selfcheck;

import agents.encoding.*;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.*;
import game.*;
import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Checks actual cache bytes against typed policies and an adjudicated solo.
 */
public final class SelfPlayCacheSelfCheck {

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private SelfPlayCacheSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayCacheSelfCheck");
        require(NeuralSelfPlayCache.split("0".repeat(64), 7) == EncodingPurpose.TRAIN,
                "TRAIN split golden changed");
        require(NeuralSelfPlayCache.split("0".repeat(63) + "6", 7) == EncodingPurpose.VALIDATION,
                "Unsigned VALIDATION split golden changed");
        Path temporary = Files.createTempDirectory("jreferee-self-play-cache-");
        try {
            Path original = temporary.resolve("original"), source = temporary.resolve("source");
            SelfPlayRecorder.run(original, new UUID(0, 91),
                    StandardGameFactory.create1901().snapshot(), options(1902, 2, true), d -> { });
            zeroPrior(original, source);
            Path solo = temporary.resolve("solo"), terminal = temporary.resolve("terminal");
            GameRunner.Result finished = SelfPlayRecorder.run(solo, new UUID(0, 92),
                    beforeSolo(), options(1901, 1, false), d -> { });
            require(finished.reason() == GameRunner.EndReason.SOLO, "Fixture did not reach a solo");
            SelfPlayRecorder.run(terminal, new UUID(0, 93), new GameState_Movement(
                            new GameMoment(1901, GamePhase.SPRING_MOVEMENT), finished.state().board()),
                    options(1901, 1, false), d -> { });
            List<Path> archives = List.of(source, solo, terminal);
            Path output = temporary.resolve("cache");
            var manifest = ValueDatasetJson.parse(
                    NeuralSelfPlayCache.write(archives, output, 7, message -> { }));
            List<SelfPlayDataset.GameExample> games = SelfPlayDataset.openAll(archives).stream()
                    .sorted(Comparator.comparing(SelfPlayDataset.GameExample::logicalKey)).toList();
            verifyPolicy(output, manifest, games);
            verifyValue(output, manifest, games);

            String repeated = NeuralSelfPlayCache.write(List.of(terminal, solo, source),
                    temporary.resolve("reordered"), 7, message -> { });
            require(ValueDatasetJson.parse(repeated).equals(manifest),
                    "Input order changed cache membership or bytes");
            expect(() -> NeuralSelfPlayCache.write(archives, output, 7, message -> { }));
            require(document(output.resolve("manifest.json")).equals(manifest),
                    "Existing cache was overwritten");
            Path duplicate = temporary.resolve("duplicate");
            expect(() -> NeuralSelfPlayCache.write(List.of(source, source), duplicate, 7, message -> { }));
            require(!Files.exists(duplicate), "Duplicate rejection created an output");
            Files.writeString(source.resolve("phases.jsonl"), "corrupted");
            Path corrupt = temporary.resolve("corrupt");
            expect(() -> NeuralSelfPlayCache.write(List.of(source), corrupt, 7, message -> { }));
            require(!Files.exists(corrupt), "Unverified source created an output");
            System.out.println("SelfPlayCacheSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameRunner.Options options(int year, int candidates, boolean mixed) {
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, new AgentSettings(mixed && power == Nation.GERMANY
                    ? AgentSettings.Method.PRIOR : AgentSettings.Method.DIL_PIKL,
                    candidates, 2, 1, 1,
                    power == Nation.ENGLAND ? PolicyOutput.FINAL : PolicyOutput.AVERAGE));
        return new GameRunner.Options(year, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static GameState beforeSolo() {
        List<Province> centers = Arrays.stream(Province.values())
                .filter(p -> p.supplyCenter && Province.canonical(p) == p).limit(18).toList();
        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        centers.forEach(p -> owners.put(p, Nation.FRANCE));
        Province target = centers.getLast();
        owners.put(target, Nation.GERMANY);
        UnitId army = new UnitId(Nation.FRANCE, UnitType.ARMY, target);
        return new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(army, target), owners));
    }

    private static void zeroPrior(Path source, Path target) throws Exception {
        var snapshot = SelfPlayFiles.snapshot(source);
        var phases = snapshot.phases().lines().map(JsonReader::read)
                .map(SelfPlayCacheSelfCheck::map).toList();
        var decision = array(phases.getFirst().get("decisions")).stream()
                .map(SelfPlayCacheSelfCheck::map).filter(row -> row.get("type") != null)
                .findFirst().orElseThrow();
        var pool = array(map(decision.get("pools")).get((String) decision.get("power")));
        require(pool.size() == 2, "Zero-prior fixture requires two candidates");
        int selected = JSON.integer(decision.get("selected_index"), "selected_index");
        List<Double> policy = new ArrayList<>(List.of(0.0, 0.0));
        policy.set(selected, 1.0);
        decision.put("policy", policy);
        map(pool.get(1 - selected)).put("log_prior", "-Infinity");
        try (SelfPlayFiles files = new SelfPlayFiles(target, map(JsonReader.read(snapshot.run())))) {
            for (var phase : phases)
                files.append(phase);
            files.finish(map(JsonReader.read(snapshot.outcome())));
        }
    }

    private static void verifyPolicy(
            Path output, Map<String, Object> manifest, List<SelfPlayDataset.GameExample> games
    ) throws Exception {

        verifyReceipt(output.resolve("policy-specification.json"), map(manifest.get("policy_specification")));
        verifyReceipt(output.resolve("games.jsonl"), map(manifest.get("game_index")));
        long examples = 0;
        boolean zeroPrior = false, opening = false, standard = false;
        Set<String> seen = new HashSet<>();
        for (var entry : map(manifest.get("policy_splits")).entrySet()) {
            EncodingPurpose split = EncodingPurpose.valueOf(entry.getKey());
            for (Object raw : array(entry.getValue())) {
                var shard = map(raw);
                Path directory = output.resolve((String) shard.get("directory"));
                for (var file : map(shard.get("files")).entrySet())
                    verifyReceipt(directory.resolve(file.getKey()), map(file.getValue()));
                ByteBuffer boards = bytes(directory, "board.u8"), contexts = bytes(directory, "context.i32"),
                        types = bytes(directory, "types.f64"), ids = bytes(directory, "type_ids.i32"),
                        slots = bytes(directory, "slot_locations.u8"), options = bytes(directory, "options.u8"),
                        labels = bytes(directory, "actions.i32"), probabilities = bytes(directory, "policy.f64"),
                        priors = bytes(directory, "log_prior.f64"), means = bytes(directory, "mean_values.f64"),
                        selections = bytes(directory, "selected.i32");
                LongBuffer exampleOffsets = bytes(directory, "example_offsets.i64").asLongBuffer(),
                        optionOffsets = bytes(directory, "option_offsets.i64").asLongBuffer(),
                        candidateOffsets = bytes(directory, "candidate_offsets.i64").asLongBuffer(),
                        actionOffsets = bytes(directory, "action_offsets.i64").asLongBuffer();
                require(exampleOffsets.get() == 0 && optionOffsets.get() == 0
                        && candidateOffsets.get() == 0 && actionOffsets.get() == 0, "Missing initial offsets");
                long unitCount = 0, optionCount = 0, candidateCount = 0, actionCount = 0;
                for (String line : Files.readAllLines(directory.resolve("metadata.jsonl"))) {
                    var row = ValueDatasetJson.parse(line);
                    int gameIndex = JSON.integer(row.get("game_index"), "game_index");
                    int phaseIndex = JSON.integer(row.get("phase_index"), "phase_index");
                    var game = games.get(gameIndex);
                    require(split == NeuralSelfPlayCache.split(game.logicalKey(), 7), "Split leakage");
                    Nation power = Nation.valueOf((String) row.get("power"));
                    require(seen.add(gameIndex + ":" + phaseIndex + ":" + power), "Repeated example");
                    var phase = game.phases().get(phaseIndex);
                    var decision = phase.decisions().stream().filter(d -> d.power() == power)
                            .findFirst().orElseThrow();
                    var encoding = new MovementEncoding((GameState_Movement) phase.state(), power);
                    var input = new NeuralMovementEncoding(encoding.input());
                    equalBytes(boards, input.board());
                    equalBytes(slots, input.slotLocations());
                    equalBytes(options, input.orderFields());
                    require(contexts.getInt() == input.power() && contexts.getInt() == input.phase()
                            && contexts.getInt() == input.year(), "Context changed");
                    var profile = decision.type().orElseThrow();
                    boolean isOpening = profile.name().equals("OPENING");
                    opening |= isOpening;
                    standard |= !isOpening;
                    require(ids.getInt() == (isOpening ? 1 : 2), "Type ID changed");
                    require(ids.getInt() == (game.configuration().seats().get(power).output()
                            == PolicyOutput.FINAL ? 1 : 2), "Output ID changed");
                    require(types.getDouble() == profile.agentLambda(), "Playing lambda changed");
                    for (double value : profile.population().values())
                        require(types.getDouble() == value, "Population lambda changed");
                    for (double value : profile.population().probabilities())
                        require(types.getDouble() == value, "Population weight changed");
                    IntBuffer localOffsets = input.optionOffsets();
                    for (int slot = 0; slot < input.units(); slot++)
                        require(optionOffsets.get() == optionCount + localOffsets.get(slot + 1),
                                "Option offsets lost their slots");
                    unitCount += input.units();
                    optionCount += input.optionCount();
                    require(exampleOffsets.get() == unitCount, "Example offset changed");
                    for (int candidate = 0; candidate < decision.policy().candidates().size(); candidate++) {
                        var action = decision.policy().candidates().get(candidate);
                        for (int label : encoding.encode(action.orders()))
                            require(labels.getInt() == label, "Complete action lost its candidate alignment");
                        require(probabilities.getDouble() == decision.policy().probabilities().get(candidate),
                                "Search target changed");
                        double prior = priors.getDouble();
                        zeroPrior |= prior == Double.NEGATIVE_INFINITY;
                        require(prior == action.logPrior(), "Anchor prior changed");
                        require(means.getDouble() == decision.search().orElseThrow().meanValues().get(candidate),
                                "Search diagnostic changed");
                        actionCount += input.units();
                        require(actionOffsets.get() == actionCount, "Action offset changed");
                        candidateCount++;
                    }
                    require(candidateOffsets.get() == candidateCount, "Candidate offset changed");
                    require(selections.getInt() == decision.selectedIndex(), "Selection index changed");
                    examples++;
                }
                for (Buffer buffer : List.of(boards, contexts, types, ids, slots, options, labels,
                        probabilities, priors, means, selections, exampleOffsets, optionOffsets,
                        candidateOffsets, actionOffsets))
                    require(!buffer.hasRemaining(), "Unclaimed numeric data");
            }
        }
        long expected = games.stream().flatMap(game -> game.phases().stream())
                .filter(phase -> phase.state() instanceof GameState_Movement)
                .mapToLong(phase -> phase.decisions().stream().filter(d -> d.type().isPresent()
                        && phase.state().board().locations().keySet().stream()
                        .anyMatch(unit -> unit.owner() == d.power())).count()).sum();
        require(examples == expected && examples == ValueDatasetJson.count(manifest, "policy_examples")
                && zeroPrior && opening && standard, "Missing policies, zero prior or root profile");

    }

    private static void verifyValue(
            Path output, Map<String, Object> manifest, List<SelfPlayDataset.GameExample> games
    ) throws Exception {

        require(ValueDatasetJson.count(manifest, "value_positions") == 1, "Wrong admitted value count");
        verifyReceipt(output.resolve("value/manifest.json"), map(manifest.get("value_manifest")));
        var value = document(output.resolve("value/manifest.json"));
        verifyReceipt(output.resolve("value/specification.json"), map(value.get("specification")));
        long admitted = 0;
        boolean cutoff = false, terminal = false, zeroUnits = false, nonDil = false;
        for (String line : Files.readAllLines(output.resolve("games.jsonl"))) {
            var row = ValueDatasetJson.parse(line);
            cutoff |= "ARTIFICIAL_CUTOFF".equals(row.get("value_status"))
                    && ValueDatasetJson.count(row, "value_positions") == 0;
            terminal |= "NO_POSITIONS".equals(row.get("value_status"));
            zeroUnits |= ValueDatasetJson.count(row, "skipped_zero_units") > 0;
            nonDil |= ValueDatasetJson.count(row, "skipped_non_dil") > 0;
        }
        for (var entry : map(value.get("splits")).entrySet()) {
            Path directory = output.resolve("value/" + entry.getKey().toLowerCase(Locale.ROOT));
            for (var file : map(map(entry.getValue()).get("files")).entrySet())
                verifyReceipt(directory.resolve(file.getKey()), map(file.getValue()));
            List<String> rows = Files.readAllLines(directory.resolve("games.jsonl"));
            LongBuffer offsets = bytes(directory, "game_offsets.i64").asLongBuffer();
            ByteBuffer boards = bytes(directory, "board.u8"), contexts = bytes(directory, "context.i32"),
                    targets = bytes(directory, "targets.f32");
            require(offsets.get() == 0, "Missing initial value offset");
            for (String line : rows) {
                var row = ValueDatasetJson.parse(line);
                var game = games.stream().filter(g -> g.id().toString().equals(row.get("game_key")))
                        .findFirst().orElseThrow();
                require(NeuralSelfPlayCache.split(game.logicalKey(), 7).name().equals(entry.getKey()),
                        "Policy/value split disagreement");
                require(game.valueAssessment().rewards().isPresent() && game.phases().size() == 1,
                        "Cutoff or terminal-only game acquired a target");
                var phase = game.phases().getFirst().state();
                var input = new NeuralValueEncoding(phase.moment(), phase.board());
                equalBytes(boards, input.board());
                require(contexts.getInt() == input.phase() && contexts.getInt() == input.year(),
                        "Value context changed");
                for (Nation power : NeuralValueEncoding.OUTPUT_NATIONS)
                    require(targets.getFloat() == (power == Nation.FRANCE ? 1.0f : 0.0f),
                            "Solo target order or value changed");
                require(offsets.get() == 1, "Value game offset changed");
                admitted++;
            }
            require(!boards.hasRemaining() && !contexts.hasRemaining()
                    && !targets.hasRemaining() && !offsets.hasRemaining(), "Unexpected value data");
        }
        require(admitted == 1 && cutoff && terminal && zeroUnits && nonDil, "Missing admission edge case");

    }

    private static void verifyReceipt(Path path, Map<String, Object> receipt) throws Exception {
        require(Files.size(path) == ValueDatasetJson.count(receipt, "bytes")
                && ValueDatasetJson.hash(path).equals(receipt.get("sha256")), "Bad receipt: " + path);
    }

    private static void equalBytes(ByteBuffer actual, ByteBuffer expected) {
        while (expected.hasRemaining())
            require(actual.get() == expected.get(), "Packed input bytes changed");
    }

    private static ByteBuffer bytes(Path directory, String name) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(directory.resolve(name))).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Map<String, Object> document(Path path) throws IOException {
        return ValueDatasetJson.parse(Files.readString(path));
    }

    private static Map<String, Object> map(Object value) { return JSON.object(value, "object"); }
    private static List<Object> array(Object value) { return JSON.array(value, "array"); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Attempt { void run() throws Exception; }

    private static void expect(Attempt attempt) throws Exception {
        try { attempt.run(); }
        catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected cache conversion to fail");
    }

}