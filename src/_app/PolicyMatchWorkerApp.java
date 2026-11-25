package _app;

import agents.execution.*;
import domain.Nation;
import io.training.*;
import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static _app.WorkerAppSupport.*;
import static io.training.SelfPlayFiles.object;

/**
 * Runs independent pairs in bounded, isolated JVMs.
 * NOTE: There is no `PolicyMatchWorker`, because the existing `JavaProcessWorker`
 *  already supplies the required mechanism.
 *  This class is named as such for styling, vis a vis `SelfPlayWorkerApp` et al.
 */
public final class PolicyMatchWorkerApp {

    private PolicyMatchWorkerApp() { }

    record Configuration(
            JavaProcessRuntime runtime, Optional<FileVerificationRequest> value,
            FileVerificationRequest anchor, FileVerificationRequest reference, FileVerificationRequest candidate,
            int lastYear, int candidates, int iterations, long firstSeed, int rotations, int parallelism,
            Path directory
    ) {
        Configuration {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(candidate, "candidate");
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            int pairs = PolicyMatchApp.schedule(rotations, firstSeed).size() / 2;
            if (lastYear < 1902 || candidates < 1 || iterations < 1
                    || parallelism < 1 || parallelism > pairs)
                throw new IllegalArgumentException("Invalid year, search budget or pair parallelism");
        }

        List<String> arguments() {
            return List.of(value.map(file -> file.file().toString()).orElse("baseline"),
                    anchor.file().toString(), reference.file().toString(), candidate.file().toString(),
                    Integer.toString(lastYear), Integer.toString(candidates), Integer.toString(iterations),
                    Long.toString(firstSeed), Integer.toString(rotations), directory.toString());
        }

        List<FileVerificationRequest> inputs() {
            List<FileVerificationRequest> files = new ArrayList<>(runtime.classPath());
            value.ifPresent(files::add);
            files.addAll(List.of(anchor, reference, candidate));
            return List.copyOf(files);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: PolicyMatchWorkerApp <match.properties>");
        Configuration configuration = read(Path.of(args[0]));
        lifecycle(System.out, () -> {
            System.out.println("BEGIN PARALLEL PAIRED POLICY MATCH");
            System.out.println("Output: " + configuration.directory());
            System.out.println("Pairs=" + configuration.rotations() * 7
                    + "; parallelism=" + configuration.parallelism());
            long started = System.nanoTime();
            String report = run(configuration, new JavaProcessWorker(), System.out::println);
            PolicyMatchApp.print(report);
            System.out.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
            System.out.println("END PARALLEL PAIRED POLICY MATCH");
            return report;
        });
    }

    static Configuration read(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Path base = file.getParent();
        Properties properties = load(file);
        Path java = base.resolve(take(properties, "java"));
        int heap = Integer.parseInt(take(properties, "heap_mib"));
        int count = Integer.parseInt(take(properties, "classpath.count"));
        if (count < 1)
            throw new IllegalArgumentException("classpath.count must be positive");
        List<FileVerificationRequest> classPath = new ArrayList<>();
        for (int index = 0; index < count; index++)
            classPath.add(pinned(properties, base, "classpath." + index));
        String valuePath = take(properties, "value.file");
        Optional<FileVerificationRequest> value = valuePath.equals("baseline") ? Optional.empty()
                : Optional.of(new FileVerificationRequest(base.resolve(valuePath), take(properties, "value.sha256")));
        Configuration configuration = new Configuration(new JavaProcessRuntime(java, classPath, heap),
                value, pinned(properties, base, "anchor"), pinned(properties, base, "reference"),
                pinned(properties, base, "candidate"),
                Integer.parseInt(take(properties, "last_year")),
                Integer.parseInt(take(properties, "candidates")),
                Integer.parseInt(take(properties, "iterations")),
                Long.parseLong(take(properties, "first_pair_seed")),
                Integer.parseInt(take(properties, "rotations")),
                Integer.parseInt(take(properties, "parallelism")), base.resolve(take(properties, "output")));
        finish(properties, configuration.directory());
        return configuration;
    }

    static String run(
            Configuration configuration, Worker<JavaProcessRequest, ProcessResult> worker, Consumer<String> progress
    ) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(progress, "progress");
        Workplace context = context(progress);
        try {
            verify(configuration, context);
            String[] arguments = configuration.arguments().toArray(String[]::new);
            GameRunner.Options reference = PolicyMatchApp.reference(arguments, false);
            AgentSettings candidate = PolicyMatchApp.candidate(arguments, false, reference);
            Path directory = configuration.directory();
            Map<String, Object> plan = PolicyMatchApp.prepare(
                    directory, reference, candidate, configuration.rotations(), false);
            byte[] planned = (SelfPlayFiles.json(plan) + "\n").getBytes(StandardCharsets.UTF_8);
            FileVerificationRequest planPin = pin(directory.resolve("plan.json"), planned);
            Map<String, Object> planReceipt = object("bytes", planned.length, "sha256", planPin.expectedSha256());
            List<FileVerificationRequest> metadata = new ArrayList<>(List.of(planPin));
            Map<String, Object> execution = object(
                    "schema", "jreferee-parallel-policy-match-v1",
                    "plan_sha256", planPin.expectedSha256(), "parallelism", configuration.parallelism(),
                    "java", configuration.runtime().javaExecutable().toString(),
                    "heap_mib", configuration.runtime().heapMiB(),
                    "inputs", configuration.inputs().stream().map(input -> object(
                            "file", input.file().toString(), "sha256", input.expectedSha256())).toList());
            Path executionFile = directory.resolve("parallel-plan.json");
            PolicyMatchApp.write(executionFile, execution);
            metadata.add(pin(executionFile, (SelfPlayFiles.json(execution) + "\n").getBytes(StandardCharsets.UTF_8)));
            verify(metadata, context);
            Files.createDirectory(directory.resolve("workers"));

            int pairs = configuration.rotations() * Nation.values().length;
            List<Job<ProcessResult>> jobs = new ArrayList<>();
            List<List<Map<String, Object>>> results = new ArrayList<>(Collections.nCopies(pairs, null));
            List<Map<String, Object>> rows = new ArrayList<>();
            String[] latest = new String[pairs];
            StringBuilder written = new StringBuilder();
            Path journal = directory.resolve("games.jsonl");
            LocalWorkforce workforce = new LocalWorkforce(configuration.parallelism(), configuration.parallelism());
            try {
                try (BufferedWriter output = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.APPEND)) {
                    int completed = 0, recorded = 0;
                    while (completed < pairs) {
                        context.checkCancelled();
                        for (int index = 0; index < jobs.size(); index++) {
                            if (results.get(index) != null)
                                continue;
                            Job<ProcessResult> job = jobs.get(index);
                            String message = job.progress().orElse("");
                            if (!message.isEmpty() && !message.equals(latest[index])) {
                                latest[index] = message;
                                progress.accept("Pair " + index + ": " + message);
                            }
                            if (!job.isDone())
                                continue;
                            ProcessResult result;
                            try {
                                result = job.get();
                            } catch (ExecutionException exception) {
                                throw new ExecutionException("Pair " + index + " failed: "
                                        + PolicyMatchApp.pairDirectory(directory, index), exception.getCause());
                            }
                            context.checkCancelled();
                            Path pairFile = PolicyMatchApp.pairDirectory(directory, index).resolve("pair.json");
                            if (result == null || result.pid() <= 0
                                    || !pairFile.resolveSibling("process.log").equals(result.log()))
                                throw new IOException("Unexpected process result for pair " + index);
                            byte[] bytes = PolicyMatchApp.bytes(pairFile);
                            List<Map<String, Object>> games = PolicyMatchApp.pairRows(
                                    bytes, plan, index, planPin.expectedSha256());
                            metadata.add(pin(pairFile, bytes));
                            results.set(index, games);
                            completed++;
                            progress.accept("Completed " + completed + "/" + pairs + " pairs; pair " + index);
                        }
                        while (recorded < pairs && results.get(recorded) != null) {
                            for (var row : results.get(recorded)) {
                                String line = SelfPlayFiles.json(row) + "\n";
                                output.write(line);
                                written.append(line);
                                rows.add(row);
                            }
                            output.flush();
                            recorded++;
                        }
                        while (jobs.size() < pairs && jobs.size() - completed < configuration.parallelism()) {
                            context.checkCancelled();
                            int index = jobs.size();
                            Path working = Files.createDirectory(PolicyMatchApp.pairDirectory(directory, index));
                            List<String> childArguments = new ArrayList<>(List.of(
                                    "--pair", Integer.toString(index), planPin.expectedSha256()));
                            childArguments.addAll(configuration.arguments());
                            jobs.add(workforce.submit(worker, new JavaProcessRequest(
                                    configuration.runtime(), "_app.PolicyMatchApp", childArguments, working)));
                            progress.accept("Submitted pair " + index);
                        }
                        if (completed < pairs)
                            Thread.sleep(250);
                    }
                }
            } finally {
                for (Job<?> job : jobs)
                    job.cancel(true);
                workforce.close();
            }

            metadata.add(pin(journal, written.toString().getBytes(StandardCharsets.UTF_8)));
            verify(metadata, context);
            Map<String, Object> report = PolicyMatchApp.pending(directory, plan, rows, planReceipt, message -> {
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException("Interrupted during match readback");
                progress.accept(message);
            });
            verify(metadata, context);
            verify(configuration, context);
            context.checkCancelled();
            return PolicyMatchApp.publish(directory, report);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static Workplace context(Consumer<String> progress) {
        Objects.requireNonNull(progress, "progress");
        return new Workplace() {
            private final UUID id = UUID.randomUUID();
            @Override public UUID jobId() { return id; }
            @Override public void reportProgress(String message) { progress.accept(message); }
            @Override public void checkCancelled() throws InterruptedException {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Match coordinator interrupted");
            }
        };
    }

    private static void verify(Configuration configuration, Workplace context) throws Exception {
        context.checkCancelled();
        if (!Files.isRegularFile(configuration.runtime().javaExecutable()))
            throw new NoSuchFileException("Java executable is missing: " + configuration.runtime().javaExecutable());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
            if (!System.getenv().getOrDefault(variable, "").isBlank())
                throw new IOException("Unset " + variable + "; worker JVM options must be explicit");
        verify(configuration.inputs(), context);
    }

    private static void verify(List<FileVerificationRequest> files, Workplace context) throws Exception {
        FileVerificationWorker verifier = new FileVerificationWorker();
        for (var file : files)
            verifier.execute(file, context);
    }

    private static FileVerificationRequest pin(Path file, byte[] bytes) throws Exception {
        return new FileVerificationRequest(file,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    static String lifecycle(PrintStream output, Callable<String> action) throws Exception {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(action, "action");
        Thread caller = Thread.currentThread();
        CountDownLatch finished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            caller.interrupt();
            boolean interrupted = false;
            while (true) {
                try {
                    finished.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();
        }, "parallel-policy-match-shutdown");
        boolean registered = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            registered = true;
            return action.call();
        } catch (InterruptedException exception) {
            caller.interrupt();
            throw exception;
        } finally {
            finished.countDown();
            if (registered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException exception) {
                    output.println("JVM shutdown in progress; match cleanup finished.");
                }
            }
        }
    }

}