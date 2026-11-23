package _app;

import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collection configuration and graceful shutdown, without models or collection.
 */
public final class SelfPlayCollectionAppSelfCheck {

    private static final String HASH = "1".repeat(64);

    private SelfPlayCollectionAppSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayCollectionAppSelfCheck");
        Path root = Files.createTempDirectory("jreferee-collection-app-");
        try {
            Path config = configuration(root);
            execution(SelfPlayCollectionApp.read(config));
            subprocess(config, SelfPlayCollectionShutdownFixture.class, "shutdown", true);
            subprocess(config, SelfPlayCollectionApp.class, "missing-java", false);
            System.out.println("SelfPlayCollectionAppSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static Path configuration(Path root) throws Exception {
        Path base = Files.createDirectory(root.resolve("config with spaces"));
        Path config = base.resolve("collection.properties");
        Properties properties = settings();
        write(config, properties);
        var plan = SelfPlayCollectionApp.read(config);
        require(plan.batches() == 32 && plan.parallelism() == 8 && plan.games() == 256
                        && plan.batch(31).firstSeed() == 9_000_000_248L
                        && plan.template().splitSeed() == Long.MIN_VALUE,
                "Collection settings or 64-bit seeds differ");
        require(plan.directory().equals(base.resolve("collection"))
                        && plan.batch(0).directory().equals(base.resolve("collection/batch-00"))
                        && plan.runtime().classPath().getFirst().file().equals(base.resolve("runtime with spaces.jar")),
                "Paths are not relative to the configuration");
        for (String field : properties.stringPropertyNames()) {
            Properties missing = settings();
            missing.remove(field);
            write(config, missing);
            expect(IllegalArgumentException.class, () -> SelfPlayCollectionApp.read(config));
        }
        for (var entry : Map.of("batches", "1025", "parallelism", "33", "games", "129",
                "classpath.0.sha256", "bad", "typo-\u00e9", "unknown").entrySet()) {
            Properties invalid = settings();
            invalid.setProperty(entry.getKey(), entry.getValue());
            write(config, invalid);
            expect(IllegalArgumentException.class, () -> SelfPlayCollectionApp.read(config));
        }
        Properties overflow = settings();
        overflow.setProperty("first_seed", Long.toString(Long.MAX_VALUE - 7));
        write(config, overflow);
        expect(ArithmeticException.class, () -> SelfPlayCollectionApp.read(config));
        Properties missingParent = settings();
        missingParent.setProperty("output", "missing/collection");
        write(config, missingParent);
        expect(NoSuchFileException.class, () -> SelfPlayCollectionApp.read(config));
        write(config, properties);
        Files.writeString(base.resolve("collection"), "preserve");
        expect(FileAlreadyExistsException.class, () -> SelfPlayCollectionApp.read(config));
        require(Files.readString(base.resolve("collection")).equals("preserve"), "Existing output changed");
        Files.delete(base.resolve("collection"));
        require(!Files.exists(base.resolve("runtime with spaces.jar")), "Parsing created or hashed a runtime");
        expect(IllegalArgumentException.class, () -> SelfPlayCollectionApp.main(new String[0]));
        expect(IllegalArgumentException.class, () -> SelfPlayCollectionApp.main(new String[]{"one", "two"}));
        return config;
    }

    private static void execution(SelfPlayCollectionPlan plan) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var expected = new SelfPlayCollectionResult(plan.directory(), List.of(),
                    new FileVerificationResult(plan.directory().resolve("manifest.json"), 1, HASH));
            require(SelfPlayCollectionApp.run(plan, output, () -> expected) == expected
                            && bytes.toString(StandardCharsets.UTF_8).contains("Completed collection:"),
                    "Successful result was lost");
            bytes.reset();
            IOException failure = new IOException("fixture failed");
            try {
                SelfPlayCollectionApp.run(plan, output, () -> { throw failure; });
                throw new AssertionError("Failure returned success");
            } catch (IOException exception) {
                require(exception == failure, "Original failure changed");
            }
            require(!bytes.toString(StandardCharsets.UTF_8).contains("Completed collection:"), "Failure printed success");
        }
        AtomicBoolean restored = new AtomicBoolean(), cleaned = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try (PrintStream output = new PrintStream(OutputStream.nullOutputStream())) {
                try {
                    SelfPlayCollectionApp.run(plan, output, () -> {
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } finally {
                            cleaned.set(true);
                        }
                        throw new AssertionError("Interrupted operation returned");
                    });
                } catch (InterruptedException exception) {
                    restored.set(Thread.currentThread().isInterrupted());
                }
            }
            return null;
        });
        Thread caller = new Thread(task, "collection-app-interrupt");
        caller.start();
        try {
            require(entered.await(10, TimeUnit.SECONDS), "Operation did not start");
            caller.interrupt();
            task.get(10, TimeUnit.SECONDS);
            require(restored.get() && cleaned.get(), "Interruption or cleanup was lost");
        } finally {
            caller.interrupt();
            caller.join(10000);
            require(!caller.isAlive(), "Interrupted caller did not finish");
        }
    }

    private static void subprocess(Path config, Class<?> main, String name, boolean success) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path log = config.resolveSibling(name + ".log");
        Process process = new ProcessBuilder(java.toString(), "-Xmx64m", "-cp",
                System.getProperty("java.class.path"), main.getName(), config.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "Application did not finish");
            String text = Files.readString(log);
            require(success ? process.exitValue() == 0 && Files.exists(config.resolveSibling("cleaned"))
                            : process.exitValue() != 0 && text.contains("Java executable is missing:")
                            && !Files.exists(config.resolveSibling("collection")),
                    "Unexpected application exit: " + text);
            require(!text.contains("Completed collection:"), "Interrupted/failed app printed success");
        } finally {
            if (process.isAlive())
                process.destroyForcibly();
            process.waitFor();
        }
    }

    private static Properties settings() {
        Properties properties = new Properties();
        properties.setProperty("java", "missing-java.exe");
        properties.setProperty("heap_mib", "2048");
        properties.setProperty("classpath.count", "1");
        properties.setProperty("classpath.0.file", "runtime with spaces.jar");
        properties.setProperty("classpath.0.sha256", HASH);
        properties.setProperty("value.file", "baseline");
        properties.setProperty("anchor.file", "anchor.bin");
        properties.setProperty("anchor.sha256", HASH);
        properties.setProperty("policy.file", "policy.bin");
        properties.setProperty("policy.sha256", HASH);
        properties.setProperty("last_year", "1930");
        properties.setProperty("candidates", "4");
        properties.setProperty("iterations", "8");
        properties.setProperty("first_seed", "9000000000");
        properties.setProperty("games", "8");
        properties.setProperty("split_seed", Long.toString(Long.MIN_VALUE));
        properties.setProperty("batches", "32");
        properties.setProperty("parallelism", "8");
        properties.setProperty("output", "collection");
        return properties;
    }

    private static void write(Path file, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Self-check only");
        }
    }

    private static void expect(Class<? extends Exception> type, CollectionAppChecked action) throws Exception {
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

interface CollectionAppChecked { void run() throws Exception; }

final class SelfPlayCollectionShutdownFixture {

    public static void main(String[] args) throws Exception {
        Path config = Path.of(args[0]);
        SelfPlayCollectionApp.run(SelfPlayCollectionApp.read(config), System.out, () -> {
            Thread exit = new Thread(() -> System.exit(0), "collection-fixture-exit");
            exit.setDaemon(true);
            exit.start();
            try {
                new CountDownLatch(1).await();
            } finally {
                Files.writeString(config.resolveSibling("cleaned"), "cleanup completed", StandardOpenOption.CREATE_NEW);
            }
            throw new AssertionError("Shutdown operation returned");
        });
    }

}