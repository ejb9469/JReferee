package _app;

import jobs.*;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static _app.WorkerAppSupport.*;

/**
 * One explicitly planned parallel collection.
 * No training or automatic retries.
 */
public final class SelfPlayCollectionApp {

    private SelfPlayCollectionApp() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: SelfPlayCollectionApp <collection.properties>");
        SelfPlayCollectionPlan plan = read(Path.of(args[0]));
        run(plan, System.out, () -> SelfPlayCollection.run(plan, System.out::println));
    }

    static SelfPlayCollectionPlan read(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Properties properties = load(file);
        int batches = Integer.parseInt(take(properties, "batches"));
        int parallelism = Integer.parseInt(take(properties, "parallelism"));
        var template = SelfPlayWorkerApp.read(properties, file.getParent());
        return new SelfPlayCollectionPlan(template.runtime(), template.request(), batches, parallelism);
    }

    static SelfPlayCollectionResult run(
            SelfPlayCollectionPlan plan, PrintStream output, Callable<SelfPlayCollectionResult> action
    ) throws Exception {

        Objects.requireNonNull(plan, "plan");
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
        }, "self-play-collection-shutdown");
        boolean registered = false;
        long started = System.nanoTime();
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            registered = true;
            output.println("Collection: " + plan.directory());
            output.println("Batches: " + plan.batches() + "; games: " + plan.games()
                    + "; parallelism: " + plan.parallelism());
            output.println("Seeds: " + plan.template().firstSeed() + ".."
                    + Math.addExact(plan.template().firstSeed(), plan.games() - 1L));
            SelfPlayCollectionResult result = action.call();
            output.println("Completed collection: " + result.directory());
            output.println("Manifest SHA-256: " + result.manifest().sha256());
            output.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
            output.println("No training, TEST access, automatic admission or promotion.");
            return result;
        } catch (InterruptedException exception) {
            caller.interrupt();
            throw exception;
        } finally {
            finished.countDown();
            if (registered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException exception) {
                    output.println("JVM shutdown in progress; collection cleanup finished.");
                }
            }
        }

    }

}