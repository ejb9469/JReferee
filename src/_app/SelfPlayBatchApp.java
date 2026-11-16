package _app;

import agents.encoding.EncodingPurpose;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import game.GameMoment;
import game.StandardGameFactory;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Sequential fixed-model collection with replay checks and game-level splits.
 * The batch completion marker is published only after cache handoff is checked.
 */
public final class SelfPlayBatchApp {

    public static final String SCHEMA = "jreferee-self-play-batch-v1";
    public static final int MAX_GAMES = 128;

    private static final List<EncodingPurpose> SPLITS =
            List.of(EncodingPurpose.TRAIN, EncodingPurpose.VALIDATION);

    private SelfPlayBatchApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 10)
            throw new IllegalArgumentException(
                    "Usage: SelfPlayBatchApp <value-model.bin|baseline> <anchor.bin> <gru-model.bin> "
                            + "<last-year> <candidates> <iterations> <first-game-seed> "
                            + "<games> <split-seed> <new-output-directory>");
        int lastYear = Integer.parseInt(args[3]);
        int candidates = Integer.parseInt(args[4]);
        int iterations = Integer.parseInt(args[5]);
        long firstSeed = Long.parseLong(args[6]);
        int games = Integer.parseInt(args[7]);
        long splitSeed = Long.parseLong(args[8]);
        Path output = Path.of(args[9]);
        preflight(output, firstSeed, games);
        GameRunner.Options first = SelfPlayGameApp.options(
                args[0], Path.of(args[1]), Path.of(args[2]), lastYear, candidates, iterations, firstSeed);

        System.out.println("BEGIN SELF-PLAY BATCH");
        System.out.println("Output: " + output.toAbsolutePath().normalize());
        System.out.println("Game seeds: " + firstSeed + ".." + Math.addExact(firstSeed, games - 1L));
        System.out.println("Split seed: " + splitSeed);
        System.out.println("First configuration: " + first);
        System.out.println("Models loaded once. No training or TEST access.");
        long started = System.nanoTime();
        Map<String, Object> report = ValueDatasetJson.parse(
                run(output, first, games, splitSeed, System.out::println));
        Map<String, Object> splits = ValueDatasetJson.object(report.get("policy_splits"));
        for (EncodingPurpose split : SPLITS) {
            var counts = ValueDatasetJson.object(splits.get(split.name()));
            System.out.println(split + ": policy_games=" + ValueDatasetJson.count(counts, "games")
                    + " policy_examples=" + ValueDatasetJson.count(counts, "examples"));
        }
        System.out.println("Value positions: " + ValueDatasetJson.count(report, "value_positions"));
        System.out.println("Dataset fingerprint: " + ValueDatasetJson.text(report, "dataset_fingerprint"));
        if (!Boolean.TRUE.equals(report.get("policy_splits_ready")))
            System.out.println("NOT READY FOR FITTING: collect another disjoint seed range; do not reassign games.");
        else if (ValueDatasetJson.count(ValueDatasetJson.object(splits.get("VALIDATION")), "games") < 3)
            System.out.println("Few VALIDATION games: collect more before meaningful checkpoint selection.");
        System.out.println("Cutoffs remain policy sources, not eventual-value targets. No model was promoted.");
        System.out.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
        System.out.println("END SELF-PLAY BATCH");

    }

    private static void preflight(Path directory, long firstSeed, int games) throws IOException {

        Objects.requireNonNull(directory, "directory");
        if (games < 1 || games > MAX_GAMES)
            throw new IllegalArgumentException("Batch size must be 1.." + MAX_GAMES);
        Math.addExact(firstSeed, games - 1L);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(directory.toString());
        Path parent = directory.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new NoSuchFileException("Output parent must already exist: " + parent);

    }

    private record Recorded(UUID id, long seed, String logicalKey, String fingerprint, EncodingPurpose split) { }

    /**
     * Reuses the supplied immutable seat settings; only the game seed changes.
     * Every game starts from the standard Spring 1901 position.
     */
    public static String run(
            Path directory, GameRunner.Options first, int games,
            long splitSeed, Consumer<String> progress
    ) throws IOException {

        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(progress, "progress");
        preflight(directory, first.seed(), games);
        if (first.scoring() != ScoringSystem.SUM_OF_SQUARES
                || first.movementPolicy() != MovementProcessor.Policy.JUDGE)
            throw new IllegalArgumentException("Batch recording requires SUM_OF_SQUARES and JUDGE");
        for (AgentSettings settings : first.agents().values()) {
            if (settings.method() != AgentSettings.Method.DIL_PIKL)
                throw new IllegalArgumentException("Policy collection requires DiL agents for every seat");
            if (settings.models().isPresent() && settings.models().orElseThrow().identities().isEmpty())
                throw new IllegalArgumentException("Recording learned models requires loaded artifact identities");
            settings.createEvaluator(first, 0);
        }

        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("archives"));
        write(directory.resolve("plan.json"), object(
                "schema", SCHEMA, "games", games, "first_game_seed", Long.toString(first.seed()),
                "last_game_seed", Long.toString(Math.addExact(first.seed(), games - 1L)),
                "split_seed", Long.toString(splitSeed), "first_configuration", first.toString(),
                "initial_position", "STANDARD_SPRING_1901", "models_reloaded_between_games", false,
                "training_performed", false, "test_opened", false));

        List<Path> archives = new ArrayList<>();
        Map<UUID, Recorded> recorded = new LinkedHashMap<>();
        Set<String> logicalKeys = new HashSet<>();
        try (BufferedWriter index = Files.newBufferedWriter(directory.resolve("games.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (int ordinal = 0; ordinal < games; ordinal++) {
                long seed = Math.addExact(first.seed(), ordinal);
                GameRunner.Options options = new GameRunner.Options(
                        first.lastYear(), seed, first.scoring(), first.movementPolicy(),
                        first.adjudicationTrials(), first.adjudicationSeed(), first.agents());
                UUID id = SelfPlayGameApp.gameId(options);
                String relative = "archives/game-" + String.format(Locale.ROOT, "%05d", ordinal);
                Path archive = directory.resolve(relative);
                String label = "Game " + (ordinal + 1) + "/" + games;
                progress.accept(label + ": seed=" + seed + " id=" + id + " output=" + relative);
                GameMoment[] moment = {null};
                SelfPlayRecorder.run(archive, id, StandardGameFactory.create1901().snapshot(), options, decision -> {
                    if (!decision.state().moment().equals(moment[0])) {
                        moment[0] = decision.state().moment();
                        progress.accept(label + ": " + moment[0]);
                    }
                });
                SelfPlayDataset.GameExample game = SelfPlayDataset.open(archive);
                if (!game.id().equals(id) || game.configuration().seed() != seed
                        || !logicalKeys.add(game.logicalKey()))
                    throw new IOException("Recorded game identity or logical key differs: " + archive);
                EncodingPurpose split = NeuralSelfPlayCache.split(game.logicalKey(), splitSeed);
                Recorded receipt = new Recorded(id, seed, game.logicalKey(), game.fingerprint(), split);
                if (recorded.putIfAbsent(id, receipt) != null)
                    throw new IOException("Repeated game ID: " + id);
                archives.add(archive);
                index.write(SelfPlayFiles.json(object(
                        "ordinal", ordinal, "directory", relative, "game_id", id.toString(),
                        "game_seed", Long.toString(seed), "logical_key", game.logicalKey(),
                        "archive_sha256", game.fingerprint(), "split", split.name(),
                        "phases", game.phases().size(),
                        "decisions", game.phases().stream().mapToLong(phase -> phase.decisions().size()).sum(),
                        "termination", game.outcome().termination().name())));
                index.write('\n');
                index.flush();
                progress.accept("Replay verified: " + id + " split=" + split
                        + " ending=" + game.outcome().termination());
            }
        }

        Path cache = directory.resolve("cache");
        progress.accept("Building the combined cache from all replay-verified archives.");
        Map<String, Object> cacheManifest = ValueDatasetJson.parse(
                NeuralSelfPlayCache.write(archives, cache, splitSeed, progress));
        if (!ValueDatasetJson.parse(Files.readString(cache.resolve("manifest.json"),
                StandardCharsets.UTF_8)).equals(cacheManifest))
            throw new IOException("Cache manifest changed after publication");
        Map<String, Object> splits = checkCache(cache, cacheManifest, recorded, splitSeed);
        boolean ready = SPLITS.stream().allMatch(split ->
                ValueDatasetJson.count(ValueDatasetJson.object(splits.get(split.name())), "examples") > 0);
        Map<String, Object> manifest = object(
                "schema", SCHEMA, "complete", true, "games", games,
                "first_game_seed", Long.toString(first.seed()),
                "last_game_seed", Long.toString(Math.addExact(first.seed(), games - 1L)),
                "split_seed", Long.toString(splitSeed), "cache_directory", "cache",
                "dataset_fingerprint", ValueDatasetJson.text(cacheManifest, "dataset_fingerprint"),
                "policy_examples", ValueDatasetJson.count(cacheManifest, "policy_examples"),
                "value_positions", ValueDatasetJson.count(cacheManifest, "value_positions"),
                "policy_splits", splits, "policy_splits_ready", ready,
                "training_performed", false, "test_opened", false,
                "files", object("plan.json", receipt(directory.resolve("plan.json")),
                        "games.jsonl", receipt(directory.resolve("games.jsonl")),
                        "cache/manifest.json", receipt(cache.resolve("manifest.json"))));
        String text = SelfPlayFiles.json(manifest);
        write(directory.resolve("manifest.pending"), manifest);
        Files.move(directory.resolve("manifest.pending"), directory.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE);
        return text;

    }

    private static Map<String, Object> checkCache(
            Path cache, Map<String, Object> manifest, Map<UUID, Recorded> expected, long splitSeed
    ) throws IOException {

        if (!NeuralSelfPlayCache.SCHEMA.equals(ValueDatasetJson.text(manifest, "schema"))
                || !Boolean.TRUE.equals(manifest.get("complete"))
                || !Boolean.FALSE.equals(manifest.get("test_opened"))
                || !Long.toString(splitSeed).equals(ValueDatasetJson.text(manifest, "split_seed")))
            throw new IOException("Unexpected cache contract");
        Map<EncodingPurpose, long[]> counts = new EnumMap<>(EncodingPurpose.class);
        SPLITS.forEach(split -> counts.put(split, new long[2]));
        Set<UUID> seen = new HashSet<>();
        long policies = 0, values = 0;
        try (BufferedReader input = Files.newBufferedReader(cache.resolve("games.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                Map<String, Object> row = ValueDatasetJson.parse(line);
                UUID id = UUID.fromString(ValueDatasetJson.text(row, "game_id"));
                Recorded game = expected.get(id);
                if (game == null || !seen.add(id)
                        || !Long.toString(game.seed()).equals(ValueDatasetJson.text(
                        ValueDatasetJson.object(row.get("configuration")), "game_seed")))
                    throw new IOException("Unexpected or duplicate cached game: " + id);
                if (!game.logicalKey().equals(ValueDatasetJson.text(row, "logical_key"))
                        || !game.fingerprint().equals(ValueDatasetJson.text(row, "archive_sha256"))
                        || !game.split().name().equals(ValueDatasetJson.text(row, "split")))
                    throw new IOException("Archive changed between replay and cache creation: " + id);
                long examples = ValueDatasetJson.count(row, "policy_examples");
                if (examples == 0)
                    throw new IOException("A standard-start DiL game has no movement policy targets: " + id);
                counts.get(game.split())[0]++;
                counts.get(game.split())[1] += examples;
                policies += examples;
                values += ValueDatasetJson.count(row, "value_positions");
            }
        }
        if (!seen.equals(expected.keySet()) || ValueDatasetJson.count(manifest, "games") != expected.size()
                || policies != ValueDatasetJson.count(manifest, "policy_examples")
                || values != ValueDatasetJson.count(manifest, "value_positions"))
            throw new IOException("Cache totals differ from the recorded collection");
        Map<String, Object> shards = ValueDatasetJson.object(manifest.get("policy_splits"));
        if (!shards.keySet().equals(Set.of("TRAIN", "VALIDATION")))
            throw new IOException("Unexpected policy splits");
        Map<String, Object> result = new LinkedHashMap<>();
        for (EncodingPurpose split : SPLITS) {
            if (!(shards.get(split.name()) instanceof List<?> reports))
                throw new IOException("Expected policy shard reports");
            long examples = 0;
            for (Object report : reports)
                examples += ValueDatasetJson.count(ValueDatasetJson.object(report), "examples");
            if (examples != counts.get(split)[1])
                throw new IOException("Policy shard counts differ from the game index");
            result.put(split.name(), object("games", counts.get(split)[0], "examples", counts.get(split)[1]));
        }
        return result;

    }

    private static Map<String, Object> receipt(Path path) throws IOException {
        return object("bytes", Files.size(path), "sha256", ValueDatasetJson.hash(path));
    }

    private static void write(Path path, Map<String, Object> value) throws IOException {
        Files.writeString(path, SelfPlayFiles.json(value) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

}