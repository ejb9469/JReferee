package _app;

import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Configuration, single-job handling and graceful shutdown; no Python or fitting.
 */
public final class PolicyTrainingWorkerAppSelfCheck {

    private static final String HASH = "abcdef0123456789".repeat(4);
    private static final String OTHER_HASH = "1".repeat(64);

    private PolicyTrainingWorkerAppSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: PolicyTrainingWorkerAppSelfCheck");
        Path root = Files.createTempDirectory("jreferee-training-app-");
        try {
            Path config = configuration(root);
            var request = PolicyTrainingWorkerApp.read(config).request();
            execution(request);
            interruption(request);
            shutdown(config);
            mainFailure(config);
            System.out.println("PolicyTrainingWorkerAppSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static Path configuration(Path root) throws Exception {

        Path base = Files.createDirectory(root.resolve("config with spaces"));
        Path config = base.resolve("worker.properties");
        Properties settings = settings(root);
        write(config, settings);
        var parsed = PolicyTrainingWorkerApp.read(config);
        var request = parsed.request();
        require(parsed.runtime().pythonExecutable().equals(base.resolve("missing-python.exe"))
                        && parsed.runtime().sources().equals(new FileVerificationRequest(
                        base.resolve("source files.zip"), HASH)),
                "Runtime paths or source pin differ");
        require(request.cacheManifests().equals(List.of(
                        new FileVerificationRequest(base.resolve("batch-0/cache/manifest.json"), HASH),
                        new FileVerificationRequest(root.resolve("batch-1/cache/manifest.json"), OTHER_HASH)))
                        && request.initialPolicy().equals(new FileVerificationRequest(
                        base.resolve("initial with spaces.pt"), HASH))
                        && request.anchor().equals(new FileVerificationRequest(
                        root.resolve("anchor with spaces.bin"), OTHER_HASH))
                        && request.directory().equals(base.resolve("attempt")),
                "Cache order, pins or configuration-relative paths differ");
        require(request.splitSeed() == Long.MIN_VALUE && request.settings().equals(
                        new PolicyTrainingSettings(2, 3, 5, 7, 0.0002, 0.0003, 0.75, 9_000_000_000L, 2)),
                "Explicit settings or 64-bit seeds differ");
        try (var paths = Files.list(base)) {
            require(paths.toList().equals(List.of(config)), "Parsing created runtime or output files");
        }
        for (String field : settings.stringPropertyNames()) {
            Properties incomplete = settings(root);
            incomplete.remove(field);
            write(config, incomplete);
            expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.read(config));
        }
        Map<String, String> invalid = Map.of(
                "typo", "unexpected", "cache.count", "1025", "cache.0.sha256", "bad",
                "cache.1.file", "not-a-manifest.json", "device", "cuda",
                "learning_rate", "NaN", "weight_decay", "-1", "gradient_clip", "0",
                "seed", Long.toString(Long.MAX_VALUE), "threads", "257");
        for (var entry : invalid.entrySet()) {
            Properties changed = settings(root);
            changed.setProperty(entry.getKey(), entry.getValue());
            write(config, changed);
            expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.read(config));
        }
        for (String count : List.of("0", "-1", "2147483648", "1")) {
            Properties changed = settings(root);
            changed.setProperty("cache.count", count);
            write(config, changed);
            expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.read(config));
        }
        Properties utf8 = settings(root);
        utf8.setProperty("typo-\u00e9", "unexpected");
        write(config, utf8);
        try {
            PolicyTrainingWorkerApp.read(config);
            throw new AssertionError("Unknown UTF-8 key was accepted");
        } catch (IllegalArgumentException exception) {
            require(exception.getMessage().contains("typo-\u00e9"), "Properties were not read as UTF-8");
        }
        Properties blank = settings(root);
        blank.setProperty("initial.sha256", " ");
        write(config, blank);
        expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.read(config));
        Properties missingParent = settings(root);
        missingParent.setProperty("output", "missing/attempt");
        write(config, missingParent);
        expect(NoSuchFileException.class, () -> PolicyTrainingWorkerApp.read(config));
        write(config, settings);
        Path attempt = base.resolve("attempt");
        Files.writeString(attempt, "preserve", StandardOpenOption.CREATE_NEW);
        expect(FileAlreadyExistsException.class, () -> PolicyTrainingWorkerApp.read(config));
        require(Files.readString(attempt).equals("preserve"), "Existing output changed");
        Files.delete(attempt);
        Files.createDirectory(attempt);
        expect(FileAlreadyExistsException.class, () -> PolicyTrainingWorkerApp.read(config));
        Files.delete(attempt);
        expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.main(new String[0]));
        expect(IllegalArgumentException.class, () -> PolicyTrainingWorkerApp.main(new String[]{"one", "two"}));
        return config;

    }

    private static void execution(PolicyTrainingRequest request) throws Exception {

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int epoch : List.of(0, 2)) {
            bytes.reset();
            Path directory = request.directory();
            var expected = new PolicyTrainingResult(directory,
                    directory.resolve("verify/process.log"), directory.resolve("fit/process.log"),
                    new FileVerificationResult(directory.resolve("model/policy.pt"), 1, HASH),
                    new FileVerificationResult(directory.resolve("model/training.json"), 2, OTHER_HASH),
                    epoch, 1.25);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch displayed = new CountDownLatch(1);
            try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8) {
                @Override
                public void println(String line) {
                    super.println(line);
                    if ("fixture progress".equals(line))
                        displayed.countDown();
                }
            }) {
                var actual = PolicyTrainingWorkerApp.run((input, workplace) -> {
                    require(input == request, "Launcher replaced the request");
                    calls.incrementAndGet();
                    workplace.reportProgress("fixture progress");
                    require(displayed.await(10, TimeUnit.SECONDS), "Running-job progress was not displayed");
                    workplace.reportProgress("fixture progress");
                    return expected;
                }, request, output);
                String text = bytes.toString(StandardCharsets.UTF_8);
                require(actual == expected && calls.get() == 1
                                && text.lines().filter("fixture progress"::equals).count() == 1
                                && text.contains("Verification log: " + expected.verificationLog())
                                && text.contains("Training log: " + expected.trainingLog())
                                && text.contains("Checkpoint SHA-256: " + HASH)
                                && text.contains("Training report SHA-256: " + OTHER_HASH)
                                && text.contains("Selected epoch: " + epoch)
                                && text.contains("Validation cross-entropy: 1.25")
                                && text.contains("Completed worker attempt: " + directory),
                        "Single-job result, changed progress or completion output differs");
            }
        }
        bytes.reset();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            IOException failure = new IOException("fixture failure");
            try {
                PolicyTrainingWorkerApp.run((input, workplace) -> {
                    throw failure;
                }, request, output);
                throw new AssertionError("Worker failure was lost");
            } catch (ExecutionException exception) {
                require(exception.getCause() == failure, "Worker failure cause changed");
            }
            String text = bytes.toString(StandardCharsets.UTF_8);
            require(!text.contains("Completed worker attempt:") && !text.contains("Selected epoch:"),
                    "Failed job printed success");
        }

    }

    private static void interruption(PolicyTrainingRequest request) throws Exception {

        CountDownLatch entered = new CountDownLatch(1), cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(), restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (PrintStream output = new PrintStream(OutputStream.nullOutputStream())) {
            Thread caller = new Thread(() -> {
                try {
                    PolicyTrainingWorkerApp.run((input, workplace) -> {
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } finally {
                            cleaning.countDown();
                            release.await();
                            cleaned.set(true);
                        }
                        throw new AssertionError("Blocking fixture returned");
                    }, request, output);
                    failure.set(new AssertionError("Interrupted launcher returned success"));
                } catch (InterruptedException exception) {
                    restored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    returned.countDown();
                }
            }, "training-app-interruption");
            caller.start();
            try {
                require(entered.await(10, TimeUnit.SECONDS), "Worker did not start");
                caller.interrupt();
                require(cleaning.await(10, TimeUnit.SECONDS), "Worker did not begin cleanup");
                require(!returned.await(100, TimeUnit.MILLISECONDS), "Launcher returned before cleanup");
                release.countDown();
                caller.join(10000);
                require(!caller.isAlive() && failure.get() == null && cleaned.get() && restored.get(),
                        "Interrupted launcher did not finish cleanup and restore interruption: " + failure.get());
            } finally {
                release.countDown();
                caller.interrupt();
                caller.join(10000);
            }
        }

    }

    private static void shutdown(Path config) throws Exception {

        Path log = config.resolveSibling("shutdown.log");
        Process process = launch(ShutdownFixture.class, config, log);
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "Shutdown hook did not finish");
            require(process.exitValue() == 0 && Files.exists(config.resolveSibling("shutdown-cleaned")),
                    "Graceful shutdown failed: " + Files.readString(log));
        } finally {
            stop(process);
        }

    }

    private static void mainFailure(Path config) throws Exception {

        Path log = config.resolveSibling("failure.log");
        Process process = launch(PolicyTrainingWorkerApp.class, config, log);
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "Application did not report missing Python");
            String text = Files.readString(log);
            require(process.exitValue() != 0 && text.contains("java.util.concurrent.ExecutionException")
                            && text.contains("Python executable is missing:") && text.contains("missing-python.exe")
                            && !text.contains("Completed worker attempt:")
                            && !Files.exists(config.resolveSibling("attempt")),
                    "Application failure exit or untouched output differs: " + text);
        } finally {
            stop(process);
        }

    }

    public static final class ShutdownFixture {

        public static void main(String[] args) throws Exception {
            Path config = Path.of(args[0]);
            CountDownLatch entered = new CountDownLatch(1);
            Thread exit = new Thread(() -> {
                try {
                    if (!entered.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("Fixture did not start");
                    System.exit(0);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }, "training-fixture-shutdown");
            exit.setDaemon(true);
            exit.start();
            try {
                PolicyTrainingWorkerApp.run((request, workplace) -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        Files.writeString(config.resolveSibling("shutdown-cleaned"), "cleaned",
                                StandardOpenOption.CREATE_NEW);
                    }
                    throw new AssertionError("Blocking fixture returned");
                }, PolicyTrainingWorkerApp.read(config).request(), System.out);
            } catch (CancellationException expected) {
                System.out.println("Fixture cancelled by JVM shutdown.");
            }
        }

    }

    private static Process launch(Class<?> main, Path config, Path log) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new ProcessBuilder(java.toString(), "-Xmx64m", "-cp",
                System.getProperty("java.class.path"), main.getName(), config.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive())
            process.destroyForcibly();
        process.waitFor();
    }

    private static Properties settings(Path root) {
        Properties properties = new Properties();
        properties.setProperty("python", "missing-python.exe");
        properties.setProperty("sources.file", "source files.zip");
        properties.setProperty("sources.sha256", HASH.toUpperCase(Locale.ROOT));
        properties.setProperty("cache.count", "2");
        properties.setProperty("cache.0.file", "batch-0/cache/manifest.json");
        properties.setProperty("cache.0.sha256", HASH);
        properties.setProperty("cache.1.file", root.resolve("batch-1/cache/manifest.json").toString());
        properties.setProperty("cache.1.sha256", OTHER_HASH);
        properties.setProperty("initial.file", "initial with spaces.pt");
        properties.setProperty("initial.sha256", HASH);
        properties.setProperty("anchor.file", root.resolve("anchor with spaces.bin").toString());
        properties.setProperty("anchor.sha256", OTHER_HASH);
        properties.setProperty("split_seed", Long.toString(Long.MIN_VALUE));
        properties.setProperty("device", "cpu");
        properties.setProperty("epochs", "2");
        properties.setProperty("batch_size", "3");
        properties.setProperty("candidate_batch_size", "5");
        properties.setProperty("max_options", "7");
        properties.setProperty("learning_rate", "0.0002");
        properties.setProperty("weight_decay", "0.0003");
        properties.setProperty("gradient_clip", "0.75");
        properties.setProperty("seed", "9000000000");
        properties.setProperty("threads", "2");
        properties.setProperty("output", "attempt");
        return properties;
    }

    private static void write(Path file, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Self-check only");
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