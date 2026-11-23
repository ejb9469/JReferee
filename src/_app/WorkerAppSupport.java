package _app;

import jobs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Shared configuration and single-job lifecycle for the worker launchers.
 */
final class WorkerAppSupport {

    private WorkerAppSupport() { }

    static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    static FileVerificationRequest pinned(Properties properties, Path base, String name) {
        return new FileVerificationRequest(base.resolve(take(properties, name + ".file")),
                take(properties, name + ".sha256"));
    }

    static String take(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing configuration key: " + name);
        properties.remove(name);
        return value.strip();
    }

    static void finish(Properties properties, Path directory) throws IOException {
        if (!properties.isEmpty())
            throw new IllegalArgumentException("Unknown configuration keys: "
                    + new TreeSet<>(properties.stringPropertyNames()));
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(directory.toString());
        Path parent = directory.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new NoSuchFileException("Output parent must already exist: " + parent);
    }

    static <I extends JobRequest<O>, O extends JobResult> O run(
            String shutdownName, Worker<I, O> worker, I request, PrintStream output
    ) throws InterruptedException, ExecutionException {

        Objects.requireNonNull(shutdownName, "shutdownName");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        LocalWorkforce workforce = new LocalWorkforce(1, 1);
        AtomicReference<Job<O>> current = new AtomicReference<>();
        AtomicBoolean stopping = new AtomicBoolean();
        Thread shutdown = new Thread(() -> {
            stopping.set(true);
            Job<?> job = current.get();
            if (job != null)
                job.cancel(true);
            workforce.close();
        }, shutdownName);
        boolean registered = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            registered = true;
            Job<O> job = workforce.submit(worker, request);
            current.set(job);
            // Shutdown may start between submission and publishing the handle.
            if (stopping.get())
                job.cancel(true);
            output.println("Job: " + job.id());
            String previous = "";
            O result;
            while (true) {
                previous = progress(job, previous, output);
                try {
                    result = job.get(500, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    // Refresh progress; this is not a job deadline.
                }
            }
            progress(job, previous, output);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            Job<?> job = current.get();
            if (job != null && !job.isDone())
                job.cancel(true);
            workforce.close();
            if (registered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException exception) {
                    output.println("JVM shutdown in progress; worker cleanup finished.");
                }
            }
        }

    }

    private static String progress(Job<?> job, String previous, PrintStream output) {
        String latest = job.progress().orElse("");
        if (!latest.equals(previous))
            output.println(latest);
        return latest;
    }

}