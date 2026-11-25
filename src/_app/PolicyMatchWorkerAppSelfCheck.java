package _app;

import agents.encoding.*;
import agents.policy.*;
import io.training.*;
import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;

import static _app.PolicyMatchWorkerApp.Configuration;

/**
 * Temporary synthetic policies exercise the real pair CLI and process cleanup.
 */
public final class PolicyMatchWorkerAppSelfCheck {

    private PolicyMatchWorkerAppSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("Usage: PolicyMatchWorkerAppSelfCheck");
        Path root = Files.createTempDirectory("jreferee-parallel-match-");
        try {
            Path anchor = root.resolve("anchor.bin");
            new LinearMovementPolicy(LinearMovementPolicy.DEFAULT_DIMENSIONS).save(anchor);
            Path reference = policy(root.resolve("reference.bin"), anchor, 1);
            Path candidate = policy(root.resolve("candidate.bin"), anchor, 2);
            JavaProcessRuntime runtime = new JavaProcessRuntime(java(), List.of(pin(runtime(root))), 128);
            Configuration base = new Configuration(runtime, Optional.empty(), pin(anchor), pin(reference),
                    pin(candidate), 1902, 1, 1, 9_000_000_000L, 2, 2, root.resolve("parallel"));
            configuration(root, base);
            equivalentRuns(root, base);
            corruption(root, base);
            failedProcesses(copy(base, root.resolve("failed-eight"), 8));
            interrupted(copy(base, root.resolve("interrupted"), 2));
            shutdown(root, copy(base, root.resolve("shutdown"), 2));
            System.out.println("PolicyMatchWorkerAppSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void configuration(Path root, Configuration base) throws Exception {
        Path file = root.resolve("match.properties");
        Properties expected = properties(root, base);
        store(file, expected);
        require(PolicyMatchWorkerApp.read(file).equals(base), "Configuration round trip differs");
        for (String field : expected.stringPropertyNames()) {
            Properties missing = properties(root, base);
            missing.remove(field);
            store(file, missing);
            expect(IllegalArgumentException.class, () -> PolicyMatchWorkerApp.read(file));
        }
        for (var entry : Map.of("parallelism", "15", "rotations", "0", "candidates", "0",
                "last_year", "1901", "candidate.sha256", "bad", "unknown", "value").entrySet()) {
            Properties invalid = properties(root, base);
            invalid.setProperty(entry.getKey(), entry.getValue());
            store(file, invalid);
            expect(IllegalArgumentException.class, () -> PolicyMatchWorkerApp.read(file));
        }
        Properties overflow = properties(root, base);
        overflow.setProperty("first_pair_seed", Long.toString(Long.MAX_VALUE - 12));
        store(file, overflow);
        expect(ArithmeticException.class, () -> PolicyMatchWorkerApp.read(file));
        Properties neural = properties(root, base);
        neural.setProperty("value.file", "reference.bin");
        neural.setProperty("value.sha256", base.reference().expectedSha256());
        store(file, neural);
        require(PolicyMatchWorkerApp.read(file).value().orElseThrow().equals(base.reference()),
                "Shared value pin was lost");
        store(file, expected);
        Files.writeString(base.directory(), "preserve");
        expect(FileAlreadyExistsException.class, () -> PolicyMatchWorkerApp.read(file));
        require(Files.readString(base.directory()).equals("preserve"), "Existing output changed");
        Files.delete(base.directory());
        expect(IllegalArgumentException.class, () -> PolicyMatchWorkerApp.main(new String[0]));
        expect(IllegalArgumentException.class, () -> PolicyMatchApp.main(new String[]{"--pair"}));
    }

    private static void equivalentRuns(Path root, Configuration base) throws Exception {
        String[] arguments = base.arguments().toArray(String[]::new);
        var reference = PolicyMatchApp.reference(arguments, false);
        var candidate = PolicyMatchApp.candidate(arguments, false, reference);
        Path sequential = root.resolve("sequential");
        String expected = PolicyMatchApp.run(sequential, reference, candidate, base.rotations(), line -> { });
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        List<Integer> completed = new ArrayList<>();
        Worker<JavaProcessRequest, ProcessResult> worker = (request, context) -> {
            int pair = Integer.parseInt(request.arguments().get(1));
            require(Files.exists(base.directory().resolve("plan.json"))
                    && Files.exists(base.directory().resolve("parallel-plan.json")), "Submitted before the plan");
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            try {
                if (pair == 2) {
                    Path zero = PolicyMatchApp.pairDirectory(base.directory(), 0);
                    await(zero.resolve("ready"));
                    require(ProcessHandle.of(Long.parseLong(Files.readString(zero.resolve("process.pid")).strip()))
                            .orElseThrow().isAlive(), "First pair did not overlap a later pair");
                }
                ProcessResult result = pair == 0 ? fixture(request, context, "gate", 2)
                        : new JavaProcessWorker().execute(request, context);
                if (pair == 2)
                    Files.writeString(PolicyMatchApp.pairDirectory(base.directory(), 0).resolve("release"), "ready");
                return result;
            } finally {
                active.decrementAndGet();
            }
        };
        String actual = PolicyMatchWorkerApp.run(base, worker, line -> {
            if (line.startsWith("Completed "))
                completed.add(Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1)));
        });
        require(active.get() == 0 && peak.get() == 2, "Pair concurrency bound differs");
        require(completed.size() == 14 && completed.indexOf(2) < completed.indexOf(0),
                "Coordinator waited for submission order");
        require(Files.readString(sequential.resolve("plan.json"))
                .equals(Files.readString(base.directory().resolve("plan.json"))), "Parallel plan differs");
        Map<String, Object> left = ValueDatasetJson.parse(expected), right = ValueDatasetJson.parse(actual);
        left.remove("files");
        right.remove("files");
        require(PolicyMatchApp.same(left, right), "Parallel statistics or output shape differs");
        List<Map<String, Object>> a = journal(sequential), b = journal(base.directory());
        for (int index = 0; index < a.size(); index++) {
            Path oldArchive = sequential.resolve(ValueDatasetJson.text(a.get(index), "directory"));
            Path newArchive = base.directory().resolve(ValueDatasetJson.text(b.get(index), "directory"));
            a.get(index).remove("archive_sha256");
            b.get(index).remove("archive_sha256");
            require(PolicyMatchApp.same(a.get(index), b.get(index)), "Pair schedule or outcome differs");
            for (String name : List.of("run.json", "phases.jsonl", "outcome.json")) {
                List<Object> oldRows = Files.readAllLines(oldArchive.resolve(name)).stream()
                        .map(ValueDatasetJson::parse).map(PolicyMatchWorkerAppSelfCheck::withoutTiming).toList();
                List<Object> newRows = Files.readAllLines(newArchive.resolve(name)).stream()
                        .map(ValueDatasetJson::parse).map(PolicyMatchWorkerAppSelfCheck::withoutTiming).toList();
                require(PolicyMatchApp.same(oldRows, newRows), "Recorded decisions or boards differ: " + name);
            }
        }
        require(PolicyMatchApp.same(ValueDatasetJson.parse(actual),
                        ValueDatasetJson.parse(PolicyMatchApp.verify(base.directory(), line -> { }))),
                "Existing verifier rejected the parallel result");
        require(!Files.exists(base.directory().resolve("cache")), "Evaluation produced a training cache");
        stopped(base.directory());
    }

    private static void corruption(Path root, Configuration base) throws Exception {
        for (String changed : List.of("pair", "journal", "model", "identity")) {
            Configuration test = copy(base, root.resolve("changed-" + changed), 1);
            byte[] original = Files.readAllBytes(base.reference().file());
            try {
                expect(IOException.class, () -> PolicyMatchWorkerApp.run(test, (request, context) -> {
                    int pair = Integer.parseInt(request.arguments().get(1));
                    Path source = PolicyMatchApp.pairDirectory(base.directory(), pair).resolve("pair.json");
                    Path target = request.directory().resolve("pair.json");
                    Files.copy(source, target);
                    for (var row : PolicyMatchApp.pairRows(Files.readAllBytes(source),
                            ValueDatasetJson.parse(Files.readString(base.directory().resolve("plan.json"))),
                            pair, request.arguments().get(2))) {
                        String archive = ValueDatasetJson.text(row, "directory");
                        copyTree(base.directory().resolve(archive), test.directory().resolve(archive));
                    }
                    if (changed.equals("identity") && pair == 0) {
                        Map<String, Object> data = ValueDatasetJson.parse(Files.readString(target));
                        data.put("pair", 1);
                        Files.writeString(target, SelfPlayFiles.json(data));
                    }
                    Files.createFile(request.directory().resolve("process.log"));
                    return new ProcessResult(ProcessHandle.current().pid(), request.directory().resolve("process.log"));
                }, line -> {
                    try {
                        if (changed.equals("pair") && line.equals("Completed 1/14 pairs; pair 0"))
                            Files.writeString(PolicyMatchApp.pairDirectory(test.directory(), 0).resolve("pair.json"),
                                    "\n", StandardOpenOption.APPEND);
                        if (changed.equals("journal") && line.equals("Submitted pair 1"))
                            Files.writeString(test.directory().resolve("games.jsonl"), "\n", StandardOpenOption.APPEND);
                        if (changed.equals("model") && line.startsWith("Checking the complete plan"))
                            Files.writeString(base.reference().file(), "changed", StandardOpenOption.APPEND);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }));
                require(!Files.exists(test.directory().resolve("manifest.json")), "Changed input was published");
            } finally {
                Files.write(base.reference().file(), original);
            }
        }
    }

    private static void failedProcesses(Configuration configuration) throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        expect(ExecutionException.class, () -> PolicyMatchWorkerApp.run(configuration, (request, context) -> {
            submitted.incrementAndGet();
            int pair = Integer.parseInt(request.arguments().get(1));
            return fixture(request, context, pair == 0 ? "fail" : "hold", configuration.parallelism());
        }, line -> { }));
        require(submitted.get() == 8, "Failure refilled the bounded window");
        require(!Files.exists(configuration.directory().resolve("manifest.json")), "Failed run was published");
        stopped(configuration.directory());
    }

    private static void interrupted(Configuration configuration) throws Exception {
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            try {
                PolicyMatchWorkerApp.run(configuration,
                        (request, context) -> fixture(request, context, "hold", 2), line -> { });
                throw new AssertionError("Interrupted run succeeded");
            } catch (InterruptedException exception) {
                return Thread.currentThread().isInterrupted();
            }
        });
        Thread caller = new Thread(task, "parallel-match-interrupt-check");
        caller.start();
        try {
            for (int pair = 0; pair < 2; pair++)
                await(PolicyMatchApp.pairDirectory(configuration.directory(), pair).resolve("ready"));
            caller.interrupt();
            require(task.get(30, TimeUnit.SECONDS), "Caller interrupt flag was lost");
            require(!Files.exists(configuration.directory().resolve("manifest.json")), "Interrupted run was published");
            stopped(configuration.directory());
        } finally {
            caller.interrupt();
            caller.join(30000);
            require(!caller.isAlive(), "Interrupted coordinator did not finish");
        }
    }

    private static void shutdown(Path root, Configuration configuration) throws Exception {
        Path file = root.resolve("shutdown.properties");
        store(file, properties(root, configuration));
        Path working = Files.createDirectory(root.resolve("controller"));
        try (LocalWorkforce workforce = new LocalWorkforce(1, 1)) {
            Job<ProcessResult> job = workforce.submit(new JavaProcessWorker(), new JavaProcessRequest(
                    configuration.runtime(), ParallelMatchShutdownFixture.class.getName(),
                    List.of(file.toString()), working));
            try {
                job.get(60, TimeUnit.SECONDS);
                require(Files.exists(configuration.directory().resolve("cleaned")), "Shutdown skipped cleanup");
                require(!Files.exists(configuration.directory().resolve("manifest.json")), "Shutdown published success");
                stopped(configuration.directory());
            } finally {
                job.cancel(true);
            }
        }
    }

    static ProcessResult fixture(JavaProcessRequest request, Workplace context, String mode, int parallelism)
            throws Exception {
        List<String> arguments = new ArrayList<>(List.of(mode, Integer.toString(parallelism)));
        arguments.addAll(request.arguments());
        return new JavaProcessWorker().execute(new JavaProcessRequest(request.runtime(),
                ParallelMatchProcessFixture.class.getName(), arguments, request.directory()), context);
    }

    static void await(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline)
                throw new IOException("Fixture did not become ready: " + file);
            Thread.sleep(25);
        }
    }

    private static void stopped(Path directory) throws IOException {
        try (var files = Files.walk(directory.resolve("workers"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals("process.pid")).toList())
                require(ProcessHandle.of(Long.parseLong(Files.readString(file).strip()))
                        .map(handle -> !handle.isAlive()).orElse(true), "Child survived coordinator cleanup");
        }
    }

    private static List<Map<String, Object>> journal(Path directory) throws IOException {
        return Files.readAllLines(directory.resolve("games.jsonl")).stream().map(ValueDatasetJson::parse).toList();
    }

    private static Object withoutTiming(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ValueDatasetJson.object(value).forEach((key, item) -> {
                if (!key.equals("elapsed_nanos"))
                    copy.put(key, withoutTiming(item));
            });
            return copy;
        }
        if (value instanceof List<?> list)
            return list.stream().map(PolicyMatchWorkerAppSelfCheck::withoutTiming).toList();
        return value;
    }

    private static Configuration copy(Configuration base, Path directory, int parallelism) {
        return new Configuration(base.runtime(), base.value(), base.anchor(), base.reference(), base.candidate(),
                base.lastYear(), base.candidates(), base.iterations(), base.firstSeed(), base.rotations(),
                parallelism, directory);
    }

    private static Properties properties(Path root, Configuration configuration) {
        Properties properties = new Properties();
        properties.setProperty("java", configuration.runtime().javaExecutable().toString());
        properties.setProperty("heap_mib", Integer.toString(configuration.runtime().heapMiB()));
        properties.setProperty("classpath.count", "1");
        Map<String, FileVerificationRequest> pins = Map.of(
                "classpath.0", configuration.runtime().classPath().getFirst(), "anchor", configuration.anchor(),
                "reference", configuration.reference(), "candidate", configuration.candidate());
        pins.forEach((name, pin) -> {
            properties.setProperty(name + ".file", root.relativize(pin.file()).toString());
            properties.setProperty(name + ".sha256", pin.expectedSha256());
        });
        properties.setProperty("value.file", "baseline");
        properties.setProperty("last_year", Integer.toString(configuration.lastYear()));
        properties.setProperty("candidates", Integer.toString(configuration.candidates()));
        properties.setProperty("iterations", Integer.toString(configuration.iterations()));
        properties.setProperty("first_pair_seed", Long.toString(configuration.firstSeed()));
        properties.setProperty("rotations", Integer.toString(configuration.rotations()));
        properties.setProperty("parallelism", Integer.toString(configuration.parallelism()));
        properties.setProperty("output", root.relativize(configuration.directory()).toString());
        return properties;
    }

    private static void store(Path file, Properties properties) throws IOException {
        try (Writer output = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(output, "Synthetic match self-check");
        }
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }

    private static Path runtime(Path root) throws Exception {
        Path source = Path.of(PolicyMatchWorkerApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = root.resolve("fixture runtime.jar");
        if (Files.isRegularFile(source))
            return Files.copy(source, target);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target));
             var files = Files.walk(source)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .sorted().toList()) {
                output.putNextEntry(new JarEntry(source.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return target;
    }

    private static Path policy(Path path, Path anchor, int identity) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] dataset = new byte[32], manifest = new byte[32], checkpoint = new byte[32];
        Arrays.fill(dataset, (byte) 1);
        Arrays.fill(manifest, (byte) 2);
        Arrays.fill(checkpoint, (byte) identity);
        ByteArrayOutputStream cohort = new ByteArrayOutputStream();
        cohort.writeBytes("jreferee-self-play-cohort-v1\n".getBytes(StandardCharsets.US_ASCII));
        cohort.writeBytes(dataset);
        cohort.writeBytes(manifest);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeBytes("JREFTGR1");
            for (String text : List.of(GruMovementPolicy.TYPED_SCHEMA, "jreferee-type-conditioned-gru-v1",
                    NeuralMovementSpec.toJson(), SelfPlayFiles.json(NeuralSearchType.specification())))
                text(output, text);
            output.write(HexFormat.of().parseHex(NeuralMovementSpec.sha256()));
            output.write(checkpoint);
            output.write(MessageDigest.getInstance("SHA-256").digest(cohort.toByteArray()));
            output.write(manifest);
            output.write(HexFormat.of().parseHex(ValueDatasetJson.hash(anchor)));
            output.writeLong(7);
            output.writeInt(1);
            output.write(dataset);
            output.write(manifest);
            output.writeInt(1);
            output.writeInt(16);
            Map<String, int[]> shapes = new LinkedHashMap<>();
            shapes.put("location.weight", new int[]{82, 16});
            shapes.put("unit.weight", new int[]{3, 4});
            shapes.put("power.weight", new int[]{8, 8});
            shapes.put("order.weight", new int[]{5, 8});
            shapes.put("phase.weight", new int[]{3, 4});
            shapes.put("terrain.weight", new int[]{3, 4});
            String[] layers = {"board_in", "board_layers.0", "board_layers.1", "initial", "order_in", "query", "bias"};
            int[] inputs = {78, 32, 32, 29, 108, 32, 16};
            for (int index = 0; index < layers.length; index++) {
                int size = index == layers.length - 1 ? 1 : 16;
                shapes.put(layers[index] + ".weight", new int[]{size, inputs[index]});
                shapes.put(layers[index] + ".bias", new int[]{size});
            }
            shapes.put("gru.weight_ih", new int[]{48, 16});
            shapes.put("gru.weight_hh", new int[]{48, 16});
            shapes.put("gru.bias_ih", new int[]{48});
            shapes.put("gru.bias_hh", new int[]{48});
            shapes.put("type_projection.weight", new int[]{16, NeuralSearchType.FIELDS.size()});
            output.writeInt(shapes.size());
            for (var entry : shapes.entrySet()) {
                text(output, entry.getKey());
                output.writeInt(entry.getValue().length);
                int count = 1;
                for (int dimension : entry.getValue()) {
                    output.writeInt(dimension);
                    count *= dimension;
                }
                for (int index = 0; index < count; index++)
                    output.writeFloat(0);
            }
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(MessageDigest.getInstance("SHA-256").digest(payload));
        return Files.write(path, bytes.toByteArray(), StandardOpenOption.CREATE_NEW);
    }

    private static void text(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static FileVerificationRequest pin(Path file) throws IOException {
        return new FileVerificationRequest(file, ValueDatasetJson.hash(file));
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path copy = target.resolve(source.relativize(path));
                if (Files.isDirectory(path))
                    Files.createDirectory(copy);
                else
                    Files.copy(path, copy);
            }
        }
    }

    private static void expect(Class<? extends Exception> type, ParallelMatchChecked action) throws Exception {
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

interface ParallelMatchChecked { void run() throws Exception; }

final class ParallelMatchProcessFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int parallelism = Integer.parseInt(args[1]);
        String[] match = Arrays.copyOfRange(args, 2, args.length);
        int pair = Integer.parseInt(match[1]);
        Path root = Path.of(match[12]), working = PolicyMatchApp.pairDirectory(root, pair);
        Files.writeString(working.resolve("ready"), "ready", StandardOpenOption.CREATE_NEW);
        if (mode.equals("gate")) {
            PolicyMatchWorkerAppSelfCheck.await(working.resolve("release"));
            PolicyMatchApp.main(match);
        } else if (mode.equals("fail")) {
            for (int index = 0; index < parallelism; index++)
                PolicyMatchWorkerAppSelfCheck.await(PolicyMatchApp.pairDirectory(root, index).resolve("ready"));
            throw new IOException("Intentional child failure");
        } else if (mode.equals("hold")) {
            Thread.sleep(60000);
            throw new IOException("Fixture was not cancelled");
        } else {
            throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}

final class ParallelMatchShutdownFixture {
    public static void main(String[] args) throws Exception {
        Configuration configuration = PolicyMatchWorkerApp.read(Path.of(args[0]));
        PolicyMatchWorkerApp.lifecycle(System.out, () -> {
            Thread exit = new Thread(() -> {
                try {
                    for (int pair = 0; pair < 2; pair++)
                        PolicyMatchWorkerAppSelfCheck.await(
                                PolicyMatchApp.pairDirectory(configuration.directory(), pair).resolve("ready"));
                    System.exit(0);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }, "parallel-match-fixture-exit");
            exit.setDaemon(true);
            exit.start();
            try {
                return PolicyMatchWorkerApp.run(configuration,
                        (request, context) -> PolicyMatchWorkerAppSelfCheck.fixture(request, context, "hold", 2),
                        line -> { });
            } finally {
                Files.writeString(configuration.directory().resolve("cleaned"), "cleaned", StandardOpenOption.CREATE_NEW);
            }
        });
    }
}