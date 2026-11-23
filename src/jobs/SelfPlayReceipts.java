package jobs;

import io.json.JsonAccess;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static jobs.ReceiptDocument.hash;

/**
 * Completion and file-integrity checks, not adjudication or numeric-cache validation.
 */
final class SelfPlayReceipts {

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));
    private static final String BATCH = "jreferee-self-play-batch-v1";
    private static final String CACHE = "jreferee-self-play-cache-v1";
    private static final Set<String> POWERS =
            Set.of("AUSTRIA", "ENGLAND", "FRANCE", "GERMANY", "ITALY", "RUSSIA", "TURKEY");
    private static final Set<String> SPLITS = Set.of("TRAIN", "VALIDATION");
    private static final Set<String> POLICY_FILES = Set.of(
            "board.u8", "context.i32", "types.f64", "type_ids.i32", "example_offsets.i64",
            "slot_locations.u8", "option_offsets.i64", "options.u8", "candidate_offsets.i64",
            "action_offsets.i64", "actions.i32", "policy.f64", "log_prior.f64",
            "mean_values.f64", "selected.i32", "metadata.jsonl");
    private static final Set<String> VALUE_FILES = Set.of(
            "board.u8", "context.i32", "targets.f32", "game_offsets.i64", "games.jsonl");

    private SelfPlayReceipts() { }

    static SelfPlayResult verify(SelfPlayRequest request, Workplace workplace)
            throws IOException, InterruptedException {

        Path root = path(request.directory().toRealPath(), "batch");
        ReceiptDocument manifest = read(root, "manifest.json", workplace);
        Map<String, Object> batch = manifest.object();
        equal(batch, "schema", BATCH);
        equal(batch, "complete", true);
        schedule(batch, request);
        equal(batch, "cache_directory", "cache");
        equal(batch, "training_performed", false);
        equal(batch, "test_opened", false);
        Map<String, Object> files = object(batch.get("files"));
        require(files.keySet().equals(Set.of("plan.json", "games.jsonl", "cache/manifest.json")),
                "Unexpected batch receipt files");
        ReceiptDocument plan = checked(root, "plan.json", files.get("plan.json"), workplace);
        equal(plan.object(), "schema", BATCH);
        schedule(plan.object(), request);
        equal(plan.object(), "initial_position", "STANDARD_SPRING_1901");
        equal(plan.object(), "models_reloaded_between_games", false);
        equal(plan.object(), "training_performed", false);
        equal(plan.object(), "test_opened", false);
        ReceiptDocument journal = checked(root, "games.jsonl", files.get("games.jsonl"), workplace);
        ReceiptDocument cacheDocument = checked(root, "cache/manifest.json", files.get("cache/manifest.json"), workplace);
        Map<String, Object> cache = cacheDocument.object();
        equal(cache, "schema", CACHE);
        equal(cache, "complete", true);
        equal(cache, "test_opened", false);
        equal(cache, "byte_order", "LITTLE_ENDIAN");
        equal(cache, "split_seed", Long.toString(request.splitSeed()));
        equal(cache, "games", (long) request.games());
        Path cacheRoot = path(root, "cache");
        ReceiptDocument index = checked(cacheRoot, "games.jsonl", cache.get("game_index"), workplace);
        String fingerprint = hash((CACHE + "\n" + request.splitSeed() + "\n" + index.hash())
                .getBytes(StandardCharsets.UTF_8));
        equal(cache, "dataset_fingerprint", fingerprint);
        equal(batch, "dataset_fingerprint", fingerprint);
        Map<String, long[]> totals = verifyGames(root, journal, index, request, workplace);
        equal(cache, "policy_examples", totals.values().stream().mapToLong(counts -> counts[1]).sum());
        equal(cache, "value_positions", totals.values().stream().mapToLong(counts -> counts[2]).sum());
        for (String count : List.of("policy_examples", "value_positions"))
            equal(batch, count, cache.get(count));

        checkFile(cacheRoot, "policy-specification.json", cache.get("policy_specification"), workplace);
        Map<String, Object> policySplits = object(cache.get("policy_splits"));
        require(policySplits.keySet().equals(SPLITS), "Unexpected policy splits");
        Map<String, Object> batchSplits = object(batch.get("policy_splits"));
        require(batchSplits.keySet().equals(SPLITS), "Unexpected batch splits");
        for (String split : SPLITS) {
            long examples = 0;
            for (Object value : JSON.array(policySplits.get(split), split)) {
                Map<String, Object> shard = object(value);
                examples = Math.addExact(examples, count(shard, "examples"));
                Path directory = path(cacheRoot, text(shard, "directory"));
                checkFiles(directory, object(shard.get("files")), POLICY_FILES, workplace);
            }
            require(examples == totals.get(split)[1], "Policy shard totals differ");
            equal(object(batchSplits.get(split)), "games", totals.get(split)[0]);
            equal(object(batchSplits.get(split)), "examples", examples);
        }
        equal(batch, "policy_splits_ready", SPLITS.stream().allMatch(split -> totals.get(split)[1] > 0));
        ReceiptDocument valueDocument = checked(cacheRoot, "value/manifest.json", cache.get("value_manifest"), workplace);
        Map<String, Object> value = valueDocument.object();
        equal(value, "schema", "jreferee-value-cache-v1");
        equal(value, "complete", true);
        equal(value, "test_exported", false);
        equal(value, "dataset_fingerprint", fingerprint);
        Path valueRoot = path(cacheRoot, "value");
        checkFile(valueRoot, "specification.json", value.get("specification"), workplace);
        Map<String, Object> valueSplits = object(value.get("splits"));
        require(valueSplits.keySet().equals(SPLITS), "Unexpected value splits");
        for (String split : SPLITS) {
            Map<String, Object> data = object(valueSplits.get(split));
            equal(data, "positions", totals.get(split)[2]);
            equal(data, "games", totals.get(split)[3]);
            checkFiles(path(valueRoot, split.toLowerCase(Locale.ROOT)),
                    object(data.get("files")), VALUE_FILES, workplace);
        }
        return new SelfPlayResult(request.directory(), request.directory().resolve("process.log"),
                fingerprint, manifest.receipt(), cacheDocument.receipt());

    }

    private static Map<String, long[]> verifyGames(
            Path root, ReceiptDocument journal, ReceiptDocument index, SelfPlayRequest request, Workplace workplace
    ) throws IOException, InterruptedException {

        List<Map<String, Object>> recorded = journal.rows();
        List<Map<String, Object>> cached = index.rows();
        require(recorded.size() == request.games() && cached.size() == request.games(), "Game count differs");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        Set<String> logicalKeys = new HashSet<>();
        Map<String, long[]> totals = new HashMap<>();
        SPLITS.forEach(split -> totals.put(split, new long[4]));
        for (int ordinal = 0; ordinal < recorded.size(); ordinal++) {
            Map<String, Object> game = recorded.get(ordinal);
            equal(game, "ordinal", (long) ordinal);
            equal(game, "game_seed", Long.toString(Math.addExact(request.firstSeed(), ordinal)));
            String id = text(game, "game_id");
            UUID.fromString(id);
            require(byId.putIfAbsent(id, game) == null, "Duplicate recorded game");
            String relative = "archives/game-" + String.format(Locale.ROOT, "%05d", ordinal);
            equal(game, "directory", relative);
            Path archive = path(root, relative);
            Map<String, Object> manifest = read(archive, "manifest.json", workplace).object();
            equal(manifest, "schema", "jreferee-self-play-v1");
            equal(manifest, "game_id", id);
            Map<String, Object> files = object(manifest.get("files"));
            List<String> names = List.of("run.json", "phases.jsonl", "outcome.json");
            checkFiles(archive, files, Set.copyOf(names), workplace);
            String identity = "jreferee-self-play-v1";
            for (String name : names)
                identity += "\n" + text(object(files.get(name)), "sha256");
            equal(game, "archive_sha256", hash(identity.getBytes(StandardCharsets.UTF_8)));
        }
        for (int ordinal = 0; ordinal < cached.size(); ordinal++) {
            workplace.checkCancelled();
            Map<String, Object> game = cached.get(ordinal);
            equal(game, "game_index", (long) ordinal);
            Map<String, Object> recordedGame = byId.remove(text(game, "game_id"));
            require(recordedGame != null, "Unexpected or repeated cached game");
            for (String field : List.of("logical_key", "archive_sha256", "split", "termination"))
                equal(game, field, recordedGame.get(field));
            String key = text(game, "logical_key");
            require(key.matches("[0-9a-f]{64}") && logicalKeys.add(key), "Invalid or repeated logical key");
            String splitHash = hash((request.splitSeed() + "\n" + key).getBytes(StandardCharsets.UTF_8));
            String split = Long.remainderUnsigned(Long.parseUnsignedLong(splitHash.substring(0, 16), 16), 10) == 0
                    ? "VALIDATION" : "TRAIN";
            equal(game, "split", split);
            long positions = count(game, "value_positions");
            String ending = text(game, "termination");
            require(ending.equals("SOLO") || ending.equals("ARTIFICIAL_CUTOFF") && positions == 0,
                    "Invalid ending or cutoff value targets");
            long[] counts = totals.get(split);
            counts[0]++;
            counts[1] = Math.addExact(counts[1], count(game, "policy_examples"));
            counts[2] = Math.addExact(counts[2], positions);
            if (positions > 0)
                counts[3]++;
            Map<String, Object> configuration = object(game.get("configuration"));
            equal(configuration, "game_seed", recordedGame.get("game_seed"));
            equal(configuration, "last_year", (long) request.lastYear());
            equal(configuration, "adjudication_trials", 1L);
            Map<String, Object> seats = object(configuration.get("seats"));
            require(seats.keySet().equals(POWERS), "Unexpected seats");
            for (Object entry : seats.values()) {
                Map<String, Object> seat = object(entry);
                equal(seat, "method", "DIL_PIKL");
                equal(seat, "candidates", (long) request.candidates());
                equal(seat, "iterations", (long) request.iterations());
                equal(seat, "rollouts", 1L);
                equal(seat, "rollout_limit", (long) request.candidates());
                equal(seat, "output", "AVERAGE");
                equal(seat, "root", "COHERENT_GRU_HYBRID");
                equal(seat, "continuation", "UNIFORM");
                Map<String, Object> models = object(seat.get("movement_models"));
                equal(models, "anchor", request.anchor().expectedSha256());
                equal(models, "gru", request.policy().expectedSha256());
                if (request.value().isEmpty())
                    equal(seat, "value_model", null);
                else
                    equal(object(seat.get("value_model")), "model",
                            request.value().orElseThrow().expectedSha256());
            }
        }
        return totals;

    }

    private static void schedule(Map<String, Object> value, SelfPlayRequest request) throws IOException {
        equal(value, "games", (long) request.games());
        equal(value, "first_game_seed", Long.toString(request.firstSeed()));
        equal(value, "last_game_seed", Long.toString(Math.addExact(request.firstSeed(), request.games() - 1L)));
        equal(value, "split_seed", Long.toString(request.splitSeed()));
    }

    private static ReceiptDocument read(Path root, String name, Workplace workplace)
            throws IOException, InterruptedException {
        workplace.checkCancelled();
        Path file = path(root, name);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            require(bytes.length <= 16 * 1024 * 1024, "Metadata file exceeds 16 MiB: " + file);
            return new ReceiptDocument(file, bytes);
        }
    }

    private static ReceiptDocument checked(Path root, String name, Object value, Workplace workplace)
            throws IOException, InterruptedException {
        ReceiptDocument document = read(root, name, workplace);
        Map<String, Object> receipt = object(value);
        equal(receipt, "bytes", (long) document.bytes().length);
        equal(receipt, "sha256", document.hash());
        return document;
    }

    private static void checkFile(Path root, String name, Object value, Workplace workplace)
            throws IOException, InterruptedException {
        Map<String, Object> receipt = object(value);
        var result = new FileVerificationWorker().execute(new FileVerificationRequest(
                path(root, name), text(receipt, "sha256")), workplace);
        equal(receipt, "bytes", result.bytes());
    }

    private static void checkFiles(
            Path root, Map<String, Object> files, Set<String> names, Workplace workplace
    )
            throws IOException, InterruptedException {
        require(files.keySet().equals(names), "Unexpected file receipt set: " + root);
        for (var entry : files.entrySet())
            checkFile(root, entry.getKey(), entry.getValue(), workplace);
    }

    private static Path path(Path root, String name) throws IOException {
        Path file = root.resolve(name).normalize().toRealPath();
        require(file.startsWith(root) && !file.equals(root), "Receipt path escapes its directory: " + name);
        return file;
    }

    private static Map<String, Object> object(Object value) {
        return JSON.object(value, "$");
    }

    private static String text(Map<String, Object> value, String name) {
        return JSON.string(JSON.required(value, name, "$"), "$." + name);
    }

    private static long count(Map<String, Object> value, String name) throws IOException {
        if (value.get(name) instanceof Long number && number >= 0)
            return number;
        throw new IOException("Invalid count: " + name);
    }

    private static void equal(Map<String, Object> value, String name, Object expected) throws IOException {
        require(value.containsKey(name) && Objects.equals(value.get(name), expected), "Unexpected " + name);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition)
            throw new IOException(message);
    }

}