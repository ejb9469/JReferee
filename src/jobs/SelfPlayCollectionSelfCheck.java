package jobs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;

import static io.training.SelfPlayFiles.object;

/**
 * Synthetic batch receipts and real cancellable JVMs, never game collection.
 */
public final class SelfPlayCollectionSelfCheck {

    private SelfPlayCollectionSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayCollectionSelfCheck");
        Path root = Files.createTempDirectory("jreferee-collection-");
        try {
            planning(root);
            orderedCompletion(root, 2);
            orderedCompletion(root, 8);
            failures(root);
            children(root, false);
            children(root, true);
            System.out.println("SelfPlayCollectionSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void planning(Path root) throws Exception {
        var fixture = plan(root.resolve("planning"), 32, 8, true);
        var plan = new SelfPlayCollectionPlan(fixture.runtime(),
                request(fixture.template(), 809, fixture.directory()), 32, 8);
        Set<Long> seeds = new HashSet<>();
        for (int index = 0; index < plan.batches(); index++) {
            var batch = plan.batch(index);
            require(batch.directory().equals(plan.directory().resolve(String.format(Locale.ROOT, "batch-%02d", index))),
                    "Batch path depends on scheduling");
            for (long seed = batch.firstSeed(); seed < batch.firstSeed() + batch.games(); seed++)
                require(seeds.add(seed), "Overlapping seeds");
            require(batch.policy().equals(plan.template().policy())
                            && batch.value().equals(plan.template().value()) && batch.splitSeed() == Long.MIN_VALUE,
                    "Batch settings changed");
        }
        require(seeds.size() == 256 && seeds.contains(809L) && seeds.contains(1064L), "Wrong production schedule");
        expect(IllegalArgumentException.class, () -> new SelfPlayCollectionPlan(plan.runtime(), plan.template(), 0, 1));
        expect(IllegalArgumentException.class, () -> new SelfPlayCollectionPlan(plan.runtime(), plan.template(), 1025, 8));
        expect(IllegalArgumentException.class, () -> new SelfPlayCollectionPlan(plan.runtime(), plan.template(), 2, 3));
        expect(IllegalArgumentException.class, () -> new SelfPlayCollectionPlan(plan.runtime(), plan.template(), 512, 257));
        var overflow = request(plan.template(), Long.MAX_VALUE - 7, plan.directory());
        expect(ArithmeticException.class, () -> new SelfPlayCollectionPlan(plan.runtime(), overflow, 2, 1));
        var missing = new SelfPlayCollectionPlan(plan.runtime(),
                request(plan.template(), 809, plan.directory().resolve("missing/collection")), 1, 1);
        expect(NoSuchFileException.class, () -> SelfPlayCollection.run(missing, message -> { }));
        Thread.currentThread().interrupt();
        try {
            expect(InterruptedException.class, () -> SelfPlayCollection.run(plan, message -> { }));
            require(Thread.currentThread().isInterrupted() && !Files.exists(plan.directory()),
                    "Pre-cancelled collection created output or lost interruption");
        } finally {
            Thread.interrupted();
        }
    }

    private static void orderedCompletion(Path root, int parallelism) throws Exception {
        var plan = plan(root.resolve("success-" + parallelism), parallelism + 1, parallelism, true);
        CountDownLatch waiting = new CountDownLatch(parallelism - 1), refilled = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        Set<Long> submitted = ConcurrentHashMap.newKeySet();
        var result = SelfPlayCollection.run(plan, (request, context) -> {
            Path planned = plan.directory().resolve("plan.json");
            var document = new ReceiptDocument(planned, Files.readAllBytes(planned)).object();
            require(((List<?>) document.get("schedule")).size() == plan.batches(),
                    "Worker started before the complete plan");
            require(submitted.add(request.firstSeed()), "Batch was submitted twice");
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            int index = (int) ((request.firstSeed() - plan.template().firstSeed()) / request.games());
            try {
                if (index < parallelism - 1) {
                    waiting.countDown();
                    await(refilled);
                } else if (index == parallelism - 1) {
                    await(waiting);
                } else {
                    refilled.countDown();
                }
                return complete(plan, request, context);
            } finally {
                active.decrementAndGet();
            }
        }, message -> { });
        require(maximum.get() == parallelism && active.get() == 0 && submitted.size() == plan.batches(),
                "Concurrency/window differs");
        var journal = new ReceiptDocument(plan.directory().resolve("batches.jsonl"),
                Files.readAllBytes(plan.directory().resolve("batches.jsonl"))).rows();
        require(journal.getFirst().get("batch").equals(parallelism - 1L),
                "Coordinator waited for the first submitted job");
        var manifest = new ReceiptDocument(result.manifest().file(), Files.readAllBytes(result.manifest().file())).object();
        require(Boolean.TRUE.equals(manifest.get("complete"))
                        && result.batches().size() == plan.batches() && manifest.get("games").equals((long) plan.games()),
                "Incomplete collection returned");
        for (int index = 0; index < plan.batches(); index++)
            require(result.batches().get(index).directory().equals(plan.batch(index).directory()),
                    "Results were returned in completion order");
        List<?> recorded = (List<?>) manifest.get("batches");
        for (int index = 0; index < recorded.size(); index++)
            require(((Map<?, ?>) recorded.get(index)).get("batch").equals((long) index), "Manifest order differs");
        require(new FileVerificationWorker().execute(new FileVerificationRequest(
                        result.manifest().file(), result.manifest().sha256()), context()).bytes() == result.manifest().bytes(),
                "Manifest receipt differs");
        expect(FileAlreadyExistsException.class,
                () -> SelfPlayCollection.run(plan, (request, context) -> complete(plan, request, context), message -> { }));
    }

    private static void failures(Path root) throws Exception {
        for (String mode : List.of("wrong-pin", "wrong-result", "wrong-job", "changed-plan",
                "changed-journal", "changed-receipt", "changed-model", "duplicate-dataset", "progress-failure")) {
            var plan = plan(root.resolve(mode), 2, 1, false);
            AtomicInteger calls = new AtomicInteger();
            if (mode.equals("wrong-pin"))
                Files.writeString(plan.template().policy().file(), "changed");
            try {
                SelfPlayCollection.run(plan, (request, context) -> {
                    calls.incrementAndGet();
                    var result = complete(plan, request, context,
                            mode.equals("duplicate-dataset") ? "1".repeat(64) : fingerprint(request));
                    if (mode.equals("wrong-job")) {
                        Path receipt = request.directory().resolve("worker-complete.properties");
                        Files.writeString(receipt, Files.readString(receipt).replace(context.jobId().toString(), UUID.randomUUID().toString()));
                    }
                    return mode.equals("wrong-result")
                            ? new SelfPlayResult(root, result.log(), result.datasetFingerprint(),
                            result.batchManifest(), result.cacheManifest()) : result;
                }, message -> {
                    if (!message.startsWith("Completed 1/"))
                        return;
                    try {
                        switch (mode) {
                            case "changed-plan" -> Files.writeString(plan.directory().resolve("plan.json"), "{}");
                            case "changed-journal" -> Files.writeString(plan.directory().resolve("batches.jsonl"), "extra",
                                    StandardOpenOption.APPEND);
                            case "changed-receipt" -> Files.writeString(plan.batch(0).directory().resolve("worker-complete.properties"), "changed");
                            case "changed-model" -> Files.writeString(plan.template().policy().file(), "changed");
                            case "progress-failure" -> throw new IllegalStateException("observer failed");
                        }
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
                throw new AssertionError("Accepted " + mode);
            } catch (IOException exception) {
                require(!mode.equals("progress-failure"), "Unexpected observer failure type");
            } catch (IllegalStateException exception) {
                require(mode.equals("progress-failure") && exception.getMessage().equals("observer failed"),
                        "Original observer exception changed");
            }
            require(!Files.exists(plan.directory().resolve("manifest.json")), "Failure published success: " + mode);
            if (mode.equals("wrong-pin"))
                require(calls.get() == 0 && !Files.exists(plan.directory()), "Bad inputs launched work");
            if (mode.equals("progress-failure"))
                require(calls.get() == 1, "Failure submitted another batch");
        }
    }

    private static void children(Path root, boolean interrupt) throws Exception {
        var plan = plan(root.resolve("children-" + interrupt), 3, 2, false);
        List<ProcessHandle> children = new ArrayList<>();
        AtomicInteger submitted = new AtomicInteger();
        AtomicBoolean restored = new AtomicBoolean();
        IOException deliberate = new IOException("deliberate second-batch failure");
        FutureTask<SelfPlayCollectionResult> task = new FutureTask<>(() -> {
            try {
                return SelfPlayCollection.run(plan, (request, context) -> {
                    submitted.incrementAndGet();
                    if (!interrupt && request.firstSeed() != plan.template().firstSeed()) {
                        ready(plan.batch(0).directory().resolve("ready"));
                        throw deliberate;
                    }
                    Files.createDirectory(request.directory());
                    new JavaProcessWorker().execute(new JavaProcessRequest(plan.runtime(),
                            SelfPlayCollectionProcessFixture.class.getName(), List.of(), request.directory()), context);
                    throw new AssertionError("Waiting child returned");
                }, message -> { });
            } finally {
                restored.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread caller = new Thread(task, "collection-child-check");
        caller.start();
        try {
            if (interrupt) {
                for (int index = 0; index < 2; index++) {
                    Path ready = plan.batch(index).directory().resolve("ready");
                    ready(ready);
                    children.add(ProcessHandle.of(Long.parseLong(Files.readString(ready))).orElseThrow());
                }
                caller.interrupt();
            }
            try {
                task.get(25, TimeUnit.SECONDS);
                throw new AssertionError("Failed/cancelled collection returned success");
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                require(interrupt ? cause instanceof InterruptedException && restored.get()
                                : cause instanceof ExecutionException execution && execution.getCause() == deliberate,
                        "Original failure or interruption changed: " + cause);
            }
            if (!interrupt) {
                long pid = Long.parseLong(Files.readString(plan.batch(0).directory().resolve("ready")));
                ProcessHandle.of(pid).ifPresent(children::add);
            }
            require(children.stream().noneMatch(ProcessHandle::isAlive), "Coordinator returned before child cleanup");
            require(submitted.get() == 2 && !Files.exists(plan.batch(2).directory()), "Failure refilled the window");
            require(!Files.exists(plan.directory().resolve("manifest.json"))
                    && Files.exists(plan.batch(0).directory().resolve("process.log")), "Failed output was not preserved");
        } finally {
            caller.interrupt();
            caller.join(30000);
            for (ProcessHandle child : children)
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.onExit().get(10, TimeUnit.SECONDS);
                }
            require(!caller.isAlive(), "Collection caller did not stop");
        }
    }

    private static SelfPlayCollectionPlan plan(Path root, int batches, int parallelism, boolean value) throws Exception {
        Files.createDirectory(root);
        Path jar = root.resolve("fixture with spaces.jar");
        String resource = "jobs/SelfPlayCollectionProcessFixture.class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             InputStream input = SelfPlayCollectionSelfCheck.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null)
                throw new IOException("Missing compiled collection fixture");
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var runtime = new JavaProcessRuntime(java, List.of(pinned(jar)), 64);
        var model = pinned(Files.writeString(root.resolve("model.bin"), "synthetic model"));
        return new SelfPlayCollectionPlan(runtime, new SelfPlayRequest(
                value ? Optional.of(model) : Optional.empty(), model, model,
                1930, 4, 8, 9_000_000_000L, 8, Long.MIN_VALUE, root.resolve("collection")), batches, parallelism);
    }

    private static SelfPlayRequest request(SelfPlayRequest source, long seed, Path directory) {
        return new SelfPlayRequest(source.value(), source.anchor(), source.policy(), source.lastYear(),
                source.candidates(), source.iterations(), seed, source.games(), source.splitSeed(), directory);
    }

    private static SelfPlayResult complete(
            SelfPlayCollectionPlan plan, SelfPlayRequest request, Workplace context
    ) throws Exception {
        return complete(plan, request, context, fingerprint(request));
    }

    private static SelfPlayResult complete(
            SelfPlayCollectionPlan plan, SelfPlayRequest request, Workplace context, String fingerprint
    ) throws Exception {
        Files.createDirectory(request.directory());
        Path batch = Files.createDirectory(request.directory().resolve("batch")).toRealPath();
        Files.createDirectory(batch.resolve("cache"));
        Path log = Files.writeString(request.directory().resolve("process.log"), "synthetic fixture");
        var manifest = SelfPlayCollectionFiles.write(batch.resolve("manifest.json"),
                object("complete", true, "dataset_fingerprint", fingerprint));
        var cache = SelfPlayCollectionFiles.write(batch.resolve("cache/manifest.json"),
                object("complete", true, "dataset_fingerprint", fingerprint));
        Properties receipt = new Properties();
        receipt.setProperty("schema", "jreferee-self-play-worker-v1");
        receipt.setProperty("complete", "true");
        receipt.setProperty("job", context.jobId().toString());
        receipt.setProperty("java", plan.runtime().javaExecutable().toString());
        receipt.setProperty("heap_mib", Integer.toString(plan.runtime().heapMiB()));
        receipt.setProperty("last_year", Integer.toString(request.lastYear()));
        receipt.setProperty("candidates", Integer.toString(request.candidates()));
        receipt.setProperty("iterations", Integer.toString(request.iterations()));
        receipt.setProperty("first_seed", Long.toString(request.firstSeed()));
        receipt.setProperty("games", Integer.toString(request.games()));
        receipt.setProperty("split_seed", Long.toString(request.splitSeed()));
        receipt.setProperty("dataset", fingerprint);
        receipt.setProperty("batch.manifest.sha256", manifest.sha256());
        receipt.setProperty("cache.manifest.sha256", cache.sha256());
        for (int index = 0; index < plan.runtime().classPath().size(); index++)
            pin(receipt, "classpath." + index, plan.runtime().classPath().get(index));
        if (request.value().isPresent())
            pin(receipt, "value", request.value().orElseThrow());
        else
            receipt.setProperty("value", "baseline");
        pin(receipt, "anchor", request.anchor());
        pin(receipt, "policy", request.policy());
        try (Writer writer = Files.newBufferedWriter(request.directory().resolve("worker-complete.properties"))) {
            receipt.store(writer, "Synthetic worker receipt");
        }
        return new SelfPlayResult(request.directory(), log, fingerprint, manifest, cache);
    }

    private static String fingerprint(SelfPlayRequest request) {
        return ReceiptDocument.hash(Long.toString(request.firstSeed()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void pin(Properties properties, String prefix, FileVerificationRequest file) {
        properties.setProperty(prefix + ".file", file.file().toString());
        properties.setProperty(prefix + ".sha256", file.expectedSha256());
    }

    private static FileVerificationRequest pinned(Path file) throws IOException {
        return new FileVerificationRequest(file, ReceiptDocument.hash(Files.readAllBytes(file)));
    }

    private static Workplace context() {
        return new LocalWorkplace(UUID.randomUUID(), () -> false);
    }

    private static void ready(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(file) && System.nanoTime() < deadline)
            Thread.sleep(20);
        require(Files.exists(file), "Child did not become ready: " + file);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(10, TimeUnit.SECONDS), "Out-of-order fixture timed out");
    }

    private static void expect(Class<? extends Exception> type, CollectionChecked action) throws Exception {
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

interface CollectionChecked { void run() throws Exception; }

final class SelfPlayCollectionProcessFixture {

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("ready.pending"), Long.toString(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE_NEW);
        Files.move(Path.of("ready.pending"), Path.of("ready"), StandardCopyOption.ATOMIC_MOVE);
        Thread.sleep(120000);
    }

}