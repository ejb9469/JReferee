package testing.selfcheck;

import _app.SelfPlayBatchApp;
import agents.encoding.EncodingPurpose;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.Nation;
import game.StandardGameFactory;
import io.json.JsonReader;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Real, cheap DiL games check batch provenance, split coverage and failure boundaries.
 * Uniform proposals avoid requiring model files; this is not a training corpus.
 */
public final class SelfPlayBatchSelfCheck {

    private SelfPlayBatchSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayBatchSelfCheck");
        Path temporary = Files.createTempDirectory("jreferee-self-play-batch-");
        try {
            collection(temporary);
            guards(temporary);
            interrupted(temporary);
            changedArchive(temporary);
            System.out.println("SelfPlayBatchSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameRunner.Options options(long seed) {
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, new AgentSettings(
                    AgentSettings.Method.DIL_PIKL, 1, 1, 1, 1, PolicyOutput.AVERAGE));
        return new GameRunner.Options(1901, seed, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static UUID legacyId(GameRunner.Options options) {
        return UUID.nameUUIDFromBytes(("SelfPlayGameApp:v1:" + options).getBytes(StandardCharsets.UTF_8));
    }

    private static void collection(Path temporary) throws Exception {

        Path firstArchive = temporary.resolve("standalone");
        GameRunner.Options first = options(8);
        SelfPlayRecorder.run(firstArchive, legacyId(first),
                StandardGameFactory.create1901().snapshot(), first, decision -> { });
        var standalone = SelfPlayDataset.open(firstArchive);
        Path secondArchive = temporary.resolve("standalone-second");
        SelfPlayRecorder.run(secondArchive, legacyId(options(9)),
                StandardGameFactory.create1901().snapshot(), options(9), decision -> { });
        var second = SelfPlayDataset.open(secondArchive);
        long splitSeed = 7;
        while (splitSeed < 10000 && (NeuralSelfPlayCache.split(standalone.logicalKey(), splitSeed)
                != EncodingPurpose.TRAIN || NeuralSelfPlayCache.split(second.logicalKey(), splitSeed)
                != EncodingPurpose.VALIDATION))
            splitSeed = Math.incrementExact(splitSeed);
        require(splitSeed < 10000, "Fixture could not obtain both policy splits");

        Path output = temporary.resolve("batch");
        String frozen = first.toString();
        Map<String, Object> report = ValueDatasetJson.parse(
                SelfPlayBatchApp.run(output, first, 2, splitSeed, message -> { }));
        require(Boolean.TRUE.equals(report.get("complete")) && ValueDatasetJson.count(report, "games") == 2,
                "Batch did not finish");
        require(first.toString().equals(frozen), "Batch mutated the supplied settings");
        require(ValueDatasetJson.parse(Files.readString(output.resolve("manifest.json"))).equals(report),
                "Published batch manifest differs");
        verifyReceipts(output, report);
        List<Map<String, Object>> index = Files.readAllLines(output.resolve("games.jsonl")).stream()
                .map(ValueDatasetJson::parse).toList();
        require(index.size() == 2, "Missing completed-game journal entries");
        Map<String, Object> splitCounts = ValueDatasetJson.object(report.get("policy_splits"));
        long train = 0, validation = 0;
        Set<UUID> ids = new HashSet<>();
        List<Path> archives = new ArrayList<>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            var row = index.get(ordinal);
            Path archive = output.resolve(ValueDatasetJson.text(row, "directory"));
            archives.add(archive);
            var game = SelfPlayDataset.open(archive);
            long seed = 8 + ordinal;
            require(game.configuration().seed() == seed && game.id().equals(legacyId(options(seed)))
                    && ids.add(game.id()), "Game seed or legacy ID changed");
            require(ValueDatasetJson.count(row, "ordinal") == ordinal
                            && game.fingerprint().equals(ValueDatasetJson.text(row, "archive_sha256"))
                            && game.logicalKey().equals(ValueDatasetJson.text(row, "logical_key")),
                    "Journal lost replay identity");
            require(game.configuration().seats().equals(standalone.configuration().seats()),
                    "Seat configuration drifted between games");
            EncodingPurpose split = NeuralSelfPlayCache.split(game.logicalKey(), splitSeed);
            require(split.name().equals(ValueDatasetJson.text(row, "split")), "Journal reassigned a game");
            if (split == EncodingPurpose.TRAIN) train++; else validation++;
            var reference = ordinal == 0 ? standalone : second;
            require(game.logicalKey().equals(reference.logicalKey())
                    && SelfPlayRecorder.state(game.finalState()).equals(SelfPlayRecorder.state(reference.finalState()))
                    && game.phases().size() == reference.phases().size(), "Batch changed the standalone game");
        }
        require(ValueDatasetJson.count(ValueDatasetJson.object(splitCounts.get("TRAIN")), "games") == train
                        && ValueDatasetJson.count(ValueDatasetJson.object(splitCounts.get("VALIDATION")), "games") == validation,
                "Policy-game coverage differs");
        require(Boolean.valueOf(train > 0 && validation > 0).equals(report.get("policy_splits_ready")),
                "Incorrect fitting-readiness flag");
        require(ValueDatasetJson.count(report, "value_positions") == 0, "Cutoff games acquired value targets");
        Path reordered = temporary.resolve("reordered");
        Map<String, Object> cache = ValueDatasetJson.parse(
                NeuralSelfPlayCache.write(List.of(archives.get(1), archives.get(0)),
                        reordered, splitSeed, message -> { }));
        require(ValueDatasetJson.text(cache, "dataset_fingerprint")
                        .equals(ValueDatasetJson.text(report, "dataset_fingerprint")),
                "Batch changed the existing cache protocol");
        Path duplicate = temporary.resolve("duplicate");
        expect(IllegalArgumentException.class, () -> NeuralSelfPlayCache.write(
                List.of(firstArchive, archives.getFirst()), duplicate, 7, message -> { }));
        require(!Files.exists(duplicate), "Repeated standalone/batch game created a cache");

        Path one = temporary.resolve("train-only");
        var smoke = ValueDatasetJson.parse(SelfPlayBatchApp.run(one, first, 1, splitSeed, message -> { }));
        require(Boolean.TRUE.equals(smoke.get("complete"))
                && Boolean.FALSE.equals(smoke.get("policy_splits_ready")), "Empty validation became ready");
        expect(FileAlreadyExistsException.class,
                () -> SelfPlayBatchApp.run(output, first, 1, 7, message -> { }));
        verifyReceipts(output, report);

    }

    private static void guards(Path temporary) throws Exception {

        Path output = temporary.resolve("invalid");
        expect(IllegalArgumentException.class,
                () -> SelfPlayBatchApp.run(output, options(7), 0, 7, message -> { }));
        expect(IllegalArgumentException.class,
                () -> SelfPlayBatchApp.run(output, options(7), SelfPlayBatchApp.MAX_GAMES + 1, 7, message -> { }));
        expect(ArithmeticException.class,
                () -> SelfPlayBatchApp.run(output, options(Long.MAX_VALUE), 2, 7, message -> { }));
        expect(NoSuchFileException.class, () -> SelfPlayBatchApp.run(
                temporary.resolve("missing-parent/batch"), options(7), 1, 7, message -> { }));
        Map<Nation, AgentSettings> seats = new EnumMap<>(options(7).agents());
        seats.put(Nation.FRANCE, new AgentSettings(
                AgentSettings.Method.PRIOR, 1, 1, 1, 1, PolicyOutput.AVERAGE));
        var invalid = new GameRunner.Options(1901, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
        expect(IllegalArgumentException.class,
                () -> SelfPlayBatchApp.run(output, invalid, 1, 7, message -> { }));
        require(!Files.exists(output) && !Files.exists(temporary.resolve("missing-parent")),
                "Preflight failure created output");
        Path marker = temporary.resolve("existing-file");
        Files.writeString(marker, "untouched");
        expect(FileAlreadyExistsException.class,
                () -> SelfPlayBatchApp.run(marker, options(7), 1, 7, message -> { }));
        require(Files.readString(marker).equals("untouched"), "Existing file was overwritten");

    }

    private static void interrupted(Path temporary) throws Exception {

        Path output = temporary.resolve("interrupted");
        RuntimeException sentinel = new IllegalStateException("Stop after the first verified game");
        try {
            SelfPlayBatchApp.run(output, options(20), 2, 7, message -> {
                if (message.startsWith("Replay verified:"))
                    throw sentinel;
            });
            throw new AssertionError("Observer failure was swallowed");
        } catch (IllegalStateException exception) {
            require(exception == sentinel, "Observer failure changed");
        }
        SelfPlayDataset.open(output.resolve("archives/game-00000"));
        require(Files.readAllLines(output.resolve("games.jsonl")).size() == 1,
                "Previously verified game was not flushed");
        require(!Files.exists(output.resolve("archives/game-00001"))
                && !Files.exists(output.resolve("cache"))
                && !Files.exists(output.resolve("manifest.json")), "Interrupted batch claimed completion");

    }

    private static void changedArchive(Path temporary) throws Exception {

        Path output = temporary.resolve("changed");
        Path archive = output.resolve("archives/game-00000");
        expect(IOException.class, () -> SelfPlayBatchApp.run(output, options(30), 1, 7, message -> {
            if (!message.startsWith("Replay verified:"))
                return;
            try {
                var snapshot = SelfPlayFiles.snapshot(archive);
                var phases = snapshot.phases().lines().map(JsonReader::read)
                        .map(ValueDatasetJson::object).toList();
                Object decisions = phases.getFirst().get("decisions");
                if (!(decisions instanceof List<?> list))
                    throw new AssertionError("Missing decisions");
                Map<String, Object> decision = ValueDatasetJson.object(list.getFirst());
                long elapsed = Long.parseLong(ValueDatasetJson.text(decision, "elapsed_nanos"));
                decision.put("elapsed_nanos", Long.toString(Math.incrementExact(elapsed)));
                Files.move(archive, temporary.resolve("saved-original"));
                try (SelfPlayFiles replacement = new SelfPlayFiles(
                        archive, ValueDatasetJson.parse(snapshot.run()))) {
                    for (var phase : phases)
                        replacement.append(phase);
                    replacement.finish(ValueDatasetJson.parse(snapshot.outcome()));
                }
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }));
        SelfPlayDataset.open(archive);
        require(Files.exists(output.resolve("cache/manifest.json"))
                        && !Files.exists(output.resolve("manifest.json")),
                "Changed replay bytes were accepted as the original collection");

    }

    private static void verifyReceipts(Path directory, Map<String, Object> manifest) throws Exception {
        for (var file : ValueDatasetJson.object(manifest.get("files")).entrySet()) {
            Path path = directory.resolve(file.getKey());
            var receipt = ValueDatasetJson.object(file.getValue());
            require(Files.size(path) == ValueDatasetJson.count(receipt, "bytes")
                            && ValueDatasetJson.hash(path).equals(ValueDatasetJson.text(receipt, "sha256")),
                    "Batch receipt differs: " + path);
        }
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

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}