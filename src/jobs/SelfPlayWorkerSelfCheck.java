package jobs;

import io.training.SelfPlayFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

import static io.training.SelfPlayFiles.object;

/**
 * Real child processes and synthetic receipt fixtures, not actual self-play games.
 */
public final class SelfPlayWorkerSelfCheck {

    private static final List<String> POWERS =
            List.of("AUSTRIA", "ENGLAND", "FRANCE", "GERMANY", "ITALY", "RUSSIA", "TURKEY");
    private static final List<String> POLICY_FILES = List.of(
            "board.u8", "context.i32", "types.f64", "type_ids.i32", "example_offsets.i64",
            "slot_locations.u8", "option_offsets.i64", "options.u8", "candidate_offsets.i64",
            "action_offsets.i64", "actions.i32", "policy.f64", "log_prior.f64",
            "mean_values.f64", "selected.i32", "metadata.jsonl");

    private SelfPlayWorkerSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayWorkerSelfCheck");
        Path root = Files.createTempDirectory("jreferee-child-worker-");
        try {
            Path jar = root.resolve("fixture with spaces.jar");
            String resource = "jobs/SelfPlayProcessFixture.class";
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                 InputStream input = SelfPlayWorkerSelfCheck.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null)
                    throw new IOException("Missing compiled process fixture");
                output.putNextEntry(new JarEntry(resource));
                input.transferTo(output);
                output.closeEntry();
            }
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            var runtime = new JavaProcessRuntime(java, List.of(pinned(jar)), 64);
            Path model = Files.writeString(root.resolve("fixture model.bin"), "not a real model");
            successAndFailures(root, runtime, pinned(model));
            cancelled(root, runtime, pinned(model), false);
            cancelled(root, runtime, pinned(model), true);
            System.out.println("SelfPlayWorkerSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static void successAndFailures(
            Path root, JavaProcessRuntime runtime, FileVerificationRequest model
    ) throws Exception {

        try (Workforce workforce = new LocalWorkforce(1, 2)) {
            for (boolean value : List.of(false, true)) {
                var request = request(root.resolve("success " + value), model, value);
                Path template = root.resolve("template-" + value);
                fixture(template, request, false);
                SelfPlayWorker worker = fixtureWorker(runtime, template, "copy");
                var job = workforce.submit(worker, request);
                var result = get(job);
                require(Files.exists(result.directory().resolve("worker-complete.properties")), "No worker receipt");
                require(Files.size(result.log()) > 300000, "Child output was not retained");
                require(Files.readString(result.log()).contains("stderr fixture"), "Child stderr was not retained");
                Properties receipt = new Properties();
                try (Reader reader = Files.newBufferedReader(result.directory().resolve("worker-complete.properties"))) {
                    receipt.load(reader);
                }
                require(receipt.getProperty("job").equals(job.id().toString())
                                && receipt.getProperty("first_seed").equals("9000000000"),
                        "Durable worker receipt lost job identity or seed");
                require(getFailure(workforce.submit(worker, request)) instanceof FileAlreadyExistsException,
                        "Existing output was accepted");
            }

            for (String mode : List.of("fail", "empty", "wrong-settings", "corrupt")) {
                var request = request(root.resolve(mode), model, false);
                Path template = root.resolve("template-" + mode);
                fixture(template, request, mode.equals("wrong-settings"));
                if (mode.equals("corrupt"))
                    Files.write(template.resolve("cache/policy/game-0/board.u8"), new byte[]{99});
                String childMode = mode.equals("fail") || mode.equals("empty") ? mode : "copy";
                Throwable failure = getFailure(workforce.submit(fixtureWorker(runtime, template, childMode), request));
                require(failure instanceof IOException, "Expected explicit process/receipt failure: " + failure);
                require(Files.exists(request.directory().resolve("process.log")), "Failure lost its log");
                require(!Files.exists(request.directory().resolve("worker-complete.properties")),
                        "Failed attempt published a worker receipt");
                if (mode.equals("fail")) {
                    require(failure.getMessage().contains("17"), "Child exit code was lost");
                    require(Files.exists(request.directory().resolve("batch/partial.txt")), "Partial output was lost");
                }
            }

            var wrong = new FileVerificationRequest(model.file(), "0".repeat(64));
            var request = request(root.resolve("wrong-model"), wrong, false);
            require(getFailure(workforce.submit(new SelfPlayWorker(runtime), request)) instanceof IOException,
                    "Wrong model hash was accepted");
            require(!Files.exists(request.directory()), "Bad model created collection output");

            Path directory = Files.createDirectory(root.resolve("wrong-runtime"));
            var badRuntime = new JavaProcessRuntime(runtime.javaExecutable(),
                    List.of(new FileVerificationRequest(runtime.classPath().getFirst().file(), "0".repeat(64))), 64);
            var process = new JavaProcessRequest(
                    badRuntime, SelfPlayProcessFixture.class.getName(), List.of("empty"), directory);
            require(getFailure(workforce.submit(new JavaProcessWorker(), process)) instanceof IOException,
                    "Wrong runtime hash was accepted");
            require(!Files.exists(directory.resolve("process.log")), "Bad runtime launched a process");
        }

    }

    private static void cancelled(
            Path root, JavaProcessRuntime runtime, FileVerificationRequest model, boolean interrupt
    ) throws Exception {

        var request = request(root.resolve("cancel-" + interrupt), model, false);
        Path pidFile = request.directory().resolve("pid.txt");
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            Job<SelfPlayResult> job = workforce.submit(fixtureWorker(runtime, root, "hang"), request);
            ProcessHandle child = null;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((!Files.exists(pidFile) || !Files.exists(request.directory().resolve("process.pid")))
                        && System.nanoTime() < deadline)
                    Thread.sleep(20);
                require(Files.exists(pidFile), "Child did not start");
                long pid = Long.parseLong(Files.readString(pidFile));
                require(Long.parseLong(Files.readString(request.directory().resolve("process.pid")).strip()) == pid,
                        "Recorded child PID differs");
                child = ProcessHandle.of(pid).orElseThrow();
                require(child.isAlive(), "Child was not running");
                require(job.cancel(interrupt), "Cancellation failed");
                Job<SelfPlayCleanupResult> following = workforce.submit(
                        (SelfPlayCleanupRequest input, Workplace workplace) ->
                                new SelfPlayCleanupResult(!input.child().isAlive()), new SelfPlayCleanupRequest(child));
                require(get(following).stopped(), "Workforce released its slot while child was alive");
                require(!Files.exists(request.directory().resolve("worker-complete.properties")),
                        "Cancelled attempt published a worker receipt");
                require(Files.exists(request.directory().resolve("batch/partial.txt")), "Cancellation lost partial output");
            } finally {
                job.cancel(true);
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                    child.onExit().get(10, TimeUnit.SECONDS);
                }
            }
        }

    }

    private static SelfPlayWorker fixtureWorker(JavaProcessRuntime runtime, Path template, String mode) {
        return new SelfPlayWorker(runtime, (request, workplace) -> {
            require(request.mainClass().equals("_app.SelfPlayBatchApp") && request.arguments().size() == 10,
                    "Wrong batch launch contract");
            require(request.arguments().subList(3, 9).equals(List.of("1930", "4", "8", "9000000000", "2", "7")),
                    "Batch settings or 64-bit seed changed");
            require(Path.of(request.arguments().getLast()).equals(request.directory().resolve("batch")),
                    "Batch output escaped its attempt");
            return new JavaProcessWorker().execute(new JavaProcessRequest(runtime,
                    SelfPlayProcessFixture.class.getName(), List.of(mode, template.toString()), request.directory()), workplace);
        });
    }

    private static SelfPlayRequest request(Path directory, FileVerificationRequest model, boolean value) {
        return new SelfPlayRequest(value ? Optional.of(model) : Optional.empty(),
                model, model, 1930, 4, 8, 9_000_000_000L, 2, 7, directory);
    }

    private static void fixture(Path root, SelfPlayRequest request, boolean wrongSettings) throws Exception {

        Files.createDirectory(root);
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path archives = Files.createDirectory(root.resolve("archives"));
        Path policy = Files.createDirectory(cache.resolve("policy"));
        Map<String, Object> seats = new TreeMap<>();
        for (String power : POWERS)
            seats.put(power, object("method", "DIL_PIKL", "candidates", wrongSettings ? 3 : 4,
                    "iterations", 8, "rollouts", 1, "rollout_limit", 4, "output", "AVERAGE",
                    "root", "COHERENT_GRU_HYBRID", "continuation", "UNIFORM",
                    "movement_models", object("anchor", request.anchor().expectedSha256(),
                            "gru", request.policy().expectedSha256()),
                    "value_model", request.value().map(file -> object("model", file.expectedSha256())).orElse(null)));
        List<Object> journal = new ArrayList<>(), index = new ArrayList<>();
        Map<String, List<Object>> shards = new LinkedHashMap<>();
        Map<String, Long> games = new LinkedHashMap<>();
        Map<String, Long> valueGames = new LinkedHashMap<>();
        for (String split : List.of("TRAIN", "VALIDATION")) {
            shards.put(split, new ArrayList<>());
            games.put(split, 0L);
            valueGames.put(split, 0L);
        }
        for (int ordinal = 0; ordinal < request.games(); ordinal++) {
            long seed = request.firstSeed() + ordinal;
            String id = new UUID(7, seed).toString();
            String key = hash(("fixture-" + seed).getBytes(StandardCharsets.UTF_8));
            String splitHash = hash((request.splitSeed() + "\n" + key).getBytes(StandardCharsets.UTF_8));
            String split = Long.remainderUnsigned(Long.parseUnsignedLong(splitHash.substring(0, 16), 16), 10) == 0
                    ? "VALIDATION" : "TRAIN";
            games.put(split, games.get(split) + 1);
            boolean solo = ordinal == 0;
            String ending = solo ? "SOLO" : "ARTIFICIAL_CUTOFF";
            if (solo)
                valueGames.put(split, valueGames.get(split) + 1);
            String relative = "archives/game-" + String.format(Locale.ROOT, "%05d", ordinal);
            Path archive = archives.resolve("game-" + String.format(Locale.ROOT, "%05d", ordinal));
            try (SelfPlayFiles files = new SelfPlayFiles(archive,
                    object("schema", SelfPlayFiles.SCHEMA, "game_id", id))) {
                files.append(object("index", 0, "decisions", List.of()));
                files.finish(object("game_id", id, "phase_count", 1, "decision_count", 0,
                        "termination", ending, "value_target_eligible", solo,
                        "eventual_sos_target", solo ? Map.of("ENGLAND", 1.0) : null,
                        "cutoff_scores", solo ? null : Map.of("ENGLAND", 1.0)));
            }
            String fingerprint = SelfPlayFiles.snapshot(archive).fingerprint();
            journal.add(object("ordinal", ordinal, "directory", relative, "game_id", id, "game_seed", Long.toString(seed),
                    "logical_key", key, "archive_sha256", fingerprint, "split", split, "termination", ending));
            index.add(object("game_index", ordinal, "game_id", id, "logical_key", key, "archive_sha256", fingerprint,
                    "split", split, "termination", ending, "policy_examples", 1, "value_positions", solo ? 1 : 0,
                    "configuration", object("last_year", 1930, "game_seed", Long.toString(seed),
                            "adjudication_trials", 1, "seats", seats)));
            Path shard = Files.createDirectory(policy.resolve("game-" + ordinal));
            shards.get(split).add(object("directory", "policy/game-" + ordinal, "examples", 1,
                    "files", payloads(shard, POLICY_FILES)));
        }
        lines(root.resolve("games.jsonl"), journal);
        lines(cache.resolve("games.jsonl"), index);
        String fingerprint = hash(("jreferee-self-play-cache-v1\n7\n"
                + hash(Files.readAllBytes(cache.resolve("games.jsonl")))).getBytes(StandardCharsets.UTF_8));
        write(cache.resolve("policy-specification.json"), Map.of());
        Path value = Files.createDirectory(cache.resolve("value"));
        write(value.resolve("specification.json"), Map.of());
        Map<String, Object> valueSplits = new LinkedHashMap<>(), batchSplits = new LinkedHashMap<>();
        for (String split : games.keySet()) {
            valueSplits.put(split, object("games", valueGames.get(split), "positions", valueGames.get(split),
                    "files", payloads(Files.createDirectory(value.resolve(split.toLowerCase(Locale.ROOT))),
                            List.of("board.u8", "context.i32", "targets.f32", "game_offsets.i64", "games.jsonl"))));
            batchSplits.put(split, object("games", games.get(split), "examples", games.get(split)));
        }
        write(value.resolve("manifest.json"), object("schema", "jreferee-value-cache-v1", "complete", true,
                "test_exported", false, "dataset_fingerprint", fingerprint,
                "specification", receipt(value.resolve("specification.json")), "splits", valueSplits));
        write(cache.resolve("manifest.json"), object("schema", "jreferee-self-play-cache-v1", "complete", true,
                "test_opened", false, "byte_order", "LITTLE_ENDIAN", "split_seed", "7", "games", 2,
                "dataset_fingerprint", fingerprint, "policy_examples", 2, "value_positions", 1,
                "game_index", receipt(cache.resolve("games.jsonl")),
                "policy_specification", receipt(cache.resolve("policy-specification.json")),
                "policy_splits", shards, "value_manifest", receipt(value.resolve("manifest.json"))));
        Map<String, Object> plan = object("schema", "jreferee-self-play-batch-v1", "games", 2,
                "first_game_seed", Long.toString(request.firstSeed()),
                "last_game_seed", Long.toString(request.firstSeed() + 1), "split_seed", "7",
                "initial_position", "STANDARD_SPRING_1901", "models_reloaded_between_games", false,
                "training_performed", false, "test_opened", false);
        write(root.resolve("plan.json"), plan);
        Map<String, Object> manifest = new LinkedHashMap<>(plan);
        manifest.putAll(object("complete", true, "cache_directory", "cache", "dataset_fingerprint", fingerprint,
                "policy_examples", 2, "value_positions", 1, "policy_splits", batchSplits,
                "policy_splits_ready", games.values().stream().allMatch(count -> count > 0),
                "files", object("plan.json", receipt(root.resolve("plan.json")),
                        "games.jsonl", receipt(root.resolve("games.jsonl")),
                        "cache/manifest.json", receipt(cache.resolve("manifest.json")))));
        write(root.resolve("manifest.json"), manifest);

    }

    private static Map<String, Object> payloads(Path directory, List<String> names) throws Exception {
        Map<String, Object> receipts = new LinkedHashMap<>();
        for (String name : names) {
            Path file = Files.write(directory.resolve(name), new byte[]{0, 1, 2});
            receipts.put(name, receipt(file));
        }
        return receipts;
    }

    private static void lines(Path path, List<Object> rows) throws IOException {
        Files.writeString(path, String.join("\n", rows.stream().map(SelfPlayFiles::json).toList()) + "\n");
    }

    private static void write(Path path, Object value) throws IOException {
        Files.writeString(path, SelfPlayFiles.json(value) + "\n", StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, Object> receipt(Path file) throws Exception {
        return object("bytes", Files.size(file), "sha256", hash(Files.readAllBytes(file)));
    }

    private static FileVerificationRequest pinned(Path file) throws Exception {
        return new FileVerificationRequest(file, hash(Files.readAllBytes(file)));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static <T> T get(Future<T> job) throws Exception {
        return job.get(15, TimeUnit.SECONDS);
    }

    private static Throwable getFailure(Job<?> job) throws Exception {
        try {
            get(job);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
        throw new AssertionError("Expected job failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}

record SelfPlayCleanupRequest(ProcessHandle child) implements JobRequest<SelfPlayCleanupResult> { }

record SelfPlayCleanupResult(boolean stopped) implements JobResult { }

final class SelfPlayProcessFixture {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path batchPartialPath = Path.of("batch/partial.txt");
        Path batchPath = Path.of("batch");
        if (mode.equals("hang")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            Files.createDirectory(batchPath);
            Files.writeString(batchPartialPath, "partial fixture");
            Path pending = Path.of("pid.pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, Path.of("pid.txt"), StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Game 1/2: waiting in fixture");
            Thread.sleep(60000);
        } else if (mode.equals("fail")) {
            Files.createDirectory(batchPath);
            Files.writeString(batchPartialPath, "partial fixture");
            System.err.println("Deliberate fixture failure");
            System.exit(17);
        } else if (mode.equals("copy")) {
            Path source = Path.of(args[1]);
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    Path target = batchPath.resolve(source.relativize(path));
                    if (Files.isDirectory(path))
                        Files.createDirectory(target);
                    else
                        Files.copy(path, target);
                }
            }
            for (int line = 0; line < 2000; line++)
                System.out.println("fixture " + "x".repeat(256));
            System.err.println("stderr fixture");
            System.out.println("Completed fixture");
        } else if (!mode.equals("empty")) {
            throw new IllegalArgumentException("Unknown fixture mode");
        }
    }

}