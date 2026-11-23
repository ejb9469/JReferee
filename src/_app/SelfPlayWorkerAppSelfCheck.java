package _app;

import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Launcher configuration, job handling and graceful JVM shutdown; no game collection.
 */
public final class SelfPlayWorkerAppSelfCheck {

    private static final String HASH = "1".repeat(64);

    private SelfPlayWorkerAppSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayWorkerAppSelfCheck");
        Path root = Files.createTempDirectory("jreferee-worker-app-");
        try {
            configuration(root);
            execution(root);
            interruption(root);
            shutdown(root);
            System.out.println("SelfPlayWorkerAppSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static void configuration(Path root) throws Exception {

        Path config = root.resolve("worker.properties");
        Properties settings = settings();
        write(config, settings);
        var parsed = SelfPlayWorkerApp.read(config);
        var request = parsed.request();
        require(request.firstSeed() == 9_000_000_000L && request.splitSeed() == Long.MIN_VALUE,
                "Configuration truncated a seed");
        require(request.directory().equals(root.resolve("attempt"))
                        && request.anchor().file().equals(root.resolve("anchor with spaces.bin")),
                "Relative paths did not use the configuration directory");
        require(parsed.runtime().heapMiB() == 2048
                        && parsed.runtime().classPath().getFirst().file().equals(root.resolve("application.jar"))
                        && request.value().orElseThrow().expectedSha256().equals(HASH),
                "Runtime or model settings differ");
        settings.setProperty("value.file", "baseline");
        settings.remove("value.sha256");
        write(config, settings);
        require(SelfPlayWorkerApp.read(config).request().value().isEmpty(), "Baseline required a value model");

        for (String field : List.of("heap_mib", "games", "split_seed", "classpath.0.sha256")) {
            Properties incomplete = settings();
            incomplete.remove(field);
            write(config, incomplete);
            expect(IllegalArgumentException.class, () -> SelfPlayWorkerApp.read(config));
        }
        Map<String, String> invalid = Map.of(
                "typo", "unexpected", "classpath.count", "0",
                "classpath.0.sha256", "bad", "first_seed", Long.toString(Long.MAX_VALUE),
                "games", "129", "last_year", "1901");
        for (var entry : invalid.entrySet()) {
            Properties changed = settings();
            changed.setProperty(entry.getKey(), entry.getValue());
            write(config, changed);
            expect(entry.getKey().equals("first_seed") ? ArithmeticException.class : IllegalArgumentException.class,
                    () -> SelfPlayWorkerApp.read(config));
        }
        Properties missingParent = settings();
        missingParent.setProperty("output", "missing/attempt");
        write(config, missingParent);
        expect(NoSuchFileException.class, () -> SelfPlayWorkerApp.read(config));
        write(config, settings());
        Files.createDirectory(root.resolve("attempt"));
        expect(FileAlreadyExistsException.class, () -> SelfPlayWorkerApp.read(config));
        Files.delete(root.resolve("attempt"));
        require(!Files.exists(root.resolve("application.jar")), "Parsing tried to build a runtime");

    }

    private static void execution(Path root) throws Exception {

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var request = request(root);
        var expected = new SelfPlayResult(request.directory(), root.resolve("process.log"), HASH,
                new FileVerificationResult(root.resolve("batch.json"), 1, HASH),
                new FileVerificationResult(root.resolve("cache.json"), 2, HASH));
        AtomicInteger calls = new AtomicInteger();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var actual = SelfPlayWorkerApp.run((input, workplace) -> {
                require(input == request, "Launcher replaced the request");
                calls.incrementAndGet();
                workplace.reportProgress("fixture progress");
                return expected;
            }, request, output);
            String text = bytes.toString(StandardCharsets.UTF_8);
            require(actual == expected && calls.get() == 1 && text.contains("fixture progress")
                            && text.contains("Completed worker attempt:"),
                    "Result, progress or single-job contract differs");
            bytes.reset();
            IOException failure = new IOException("fixture failure");
            try {
                SelfPlayWorkerApp.run((input, workplace) -> {
                    throw failure;
                }, request, output);
                throw new AssertionError("Worker failure was lost");
            } catch (ExecutionException exception) {
                require(exception.getCause() == failure, "Worker failure cause changed");
            }
            require(!bytes.toString(StandardCharsets.UTF_8).contains("Completed worker attempt:"),
                    "Failed job printed success");
        }

    }

    private static void interruption(Path root) throws Exception {

        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(), restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (PrintStream output = new PrintStream(OutputStream.nullOutputStream())) {
            Thread caller = new Thread(() -> {
                try {
                    SelfPlayWorkerApp.run((request, workplace) -> {
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } finally {
                            cleaned.set(true);
                        }
                        throw new AssertionError("Blocking fixture returned");
                    }, request(root), output);
                    failure.set(new AssertionError("Interrupted launcher returned success"));
                } catch (InterruptedException exception) {
                    restored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "worker-app-interruption");
            caller.start();
            try {
                require(entered.await(10, TimeUnit.SECONDS), "Worker did not start");
                caller.interrupt();
                caller.join(10000);
                require(!caller.isAlive() && failure.get() == null && cleaned.get() && restored.get(),
                        "Interrupted launcher did not wait for cleanup and restore interruption");
            } finally {
                caller.interrupt();
                caller.join(10000);
            }
        }

    }

    private static void shutdown(Path root) throws Exception {

        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path log = root.resolve("shutdown.log");
        Process process = new ProcessBuilder(java.toString(), "-Xmx64m", "-cp",
                System.getProperty("java.class.path"), ShutdownFixture.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "Shutdown hook did not finish");
            require(process.exitValue() == 0 && Files.exists(root.resolve("shutdown-cleaned")),
                    "Graceful shutdown failed: " + Files.readString(log));
        } finally {
            if (process.isAlive())
                process.destroyForcibly();
            process.waitFor();
        }

    }

    public static final class ShutdownFixture {

        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
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
            }, "fixture-shutdown-request");
            exit.setDaemon(true);
            exit.start();
            try {
                SelfPlayWorkerApp.run((request, workplace) -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        Files.writeString(root.resolve("shutdown-cleaned"), "cleaned");
                    }
                    throw new AssertionError("Blocking fixture returned");
                }, request(root), System.out);
            } catch (CancellationException expected) {
                System.out.println("Fixture cancelled by JVM shutdown.");
            }
        }

    }

    private static SelfPlayRequest request(Path root) {
        var model = new FileVerificationRequest(root.resolve("fixture.bin"), HASH);
        return new SelfPlayRequest(Optional.empty(), model, model,
                1902, 4, 8, 9_000_000_000L, 2, 7, root.resolve("unused-output"));
    }

    private static Properties settings() {
        Properties properties = new Properties();
        properties.setProperty("java", "java");
        properties.setProperty("heap_mib", "2048");
        properties.setProperty("classpath.count", "1");
        properties.setProperty("classpath.0.file", "application.jar");
        properties.setProperty("classpath.0.sha256", HASH);
        properties.setProperty("value.file", "value.bin");
        properties.setProperty("value.sha256", HASH);
        properties.setProperty("anchor.file", "anchor with spaces.bin");
        properties.setProperty("anchor.sha256", HASH);
        properties.setProperty("policy.file", "policy.bin");
        properties.setProperty("policy.sha256", HASH);
        properties.setProperty("last_year", "1902");
        properties.setProperty("candidates", "4");
        properties.setProperty("iterations", "8");
        properties.setProperty("first_seed", "9000000000");
        properties.setProperty("games", "2");
        properties.setProperty("split_seed", Long.toString(Long.MIN_VALUE));
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