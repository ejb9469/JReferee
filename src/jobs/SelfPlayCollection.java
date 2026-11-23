package jobs;

import io.training.SelfPlayFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Bounded coordination on the calling thread, outside the worker pool.
 * Interrupt the caller to cancel; return/throw occurs after worker cleanup.
 */
public final class SelfPlayCollection {

    private SelfPlayCollection() { }

    public static SelfPlayCollectionResult run(
            SelfPlayCollectionPlan plan, Consumer<String> progress
    ) throws IOException, InterruptedException, ExecutionException {
        Objects.requireNonNull(plan, "plan");
        return run(plan, new SelfPlayWorker(plan.runtime()), progress);
    }

    static SelfPlayCollectionResult run(
            SelfPlayCollectionPlan plan, Worker<SelfPlayRequest, SelfPlayResult> worker,
            Consumer<String> progress
    ) throws IOException, InterruptedException, ExecutionException {

        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(progress, "progress");
        Workplace context = new LocalWorkplace(UUID.randomUUID(), () -> false);
        try {
            context.checkCancelled();
            Path directory = plan.directory();
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                throw new FileAlreadyExistsException(directory.toString());
            Path parent = directory.getParent();
            if (parent == null || !Files.isDirectory(parent))
                throw new NoSuchFileException("Collection parent must already exist: " + parent);
            progress.accept("Verifying collection runtime and model pins.");
            SelfPlayCollectionFiles.inputs(plan, context);
            context.checkCancelled();
            Files.createDirectory(directory);
            FileVerificationResult planned = SelfPlayCollectionFiles.write(
                    directory.resolve("plan.json"), SelfPlayCollectionFiles.plan(plan));
            SelfPlayCollectionFiles.verify(planned, context);

            List<Job<SelfPlayResult>> jobs = new ArrayList<>();
            SelfPlayResult[] results = new SelfPlayResult[plan.batches()];
            String[] latest = new String[plan.batches()];
            List<Map<String, Object>> rows = new ArrayList<>(Collections.nCopies(plan.batches(), null));
            List<FileVerificationResult> receipts = new ArrayList<>();
            StringBuilder written = new StringBuilder();
            Path journal = directory.resolve("batches.jsonl");
            LocalWorkforce workforce = new LocalWorkforce(plan.parallelism(), plan.parallelism());
            try {
                try (BufferedWriter output = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.APPEND)) {
                    int completed = 0;
                    while (completed < plan.batches()) {
                        context.checkCancelled();
                        // Observe every available completion before replenishing the window.
                        for (int index = 0; index < jobs.size(); index++) {
                            if (results[index] != null)
                                continue;
                            Job<SelfPlayResult> job = jobs.get(index);
                            String message = job.progress().orElse("");
                            if (!message.isEmpty() && !message.equals(latest[index])) {
                                latest[index] = message;
                                progress.accept("Batch " + index + ": " + message);
                            }
                            if (!job.isDone())
                                continue;
                            context.checkCancelled();
                            SelfPlayResult result;
                            try {
                                result = job.get();
                            } catch (ExecutionException exception) {
                                throw new ExecutionException("Batch " + index + " failed: "
                                        + plan.batch(index).directory(), exception.getCause());
                            }
                            Map<String, Object> row = SelfPlayCollectionFiles.completed(
                                    plan, index, job.id(), result, receipts, context);
                            String line = SelfPlayFiles.json(row) + "\n";
                            output.write(line);
                            output.flush();
                            written.append(line);
                            rows.set(index, row);
                            results[index] = result;
                            completed++;
                            progress.accept("Completed " + completed + "/" + plan.batches()
                                    + " batches; batch " + index + " verified.");
                        }
                        while (jobs.size() < plan.batches()
                                && jobs.size() - completed < plan.parallelism()) {
                            context.checkCancelled();
                            int index = jobs.size();
                            jobs.add(workforce.submit(worker, plan.batch(index)));
                            progress.accept("Submitted batch " + index + ": " + plan.batch(index).directory());
                        }
                        if (completed < plan.batches())
                            Thread.sleep(250);
                    }
                }
            } finally {
                for (Job<?> job : jobs)
                    job.cancel(true);
                workforce.close();
            }

            context.checkCancelled();
            progress.accept("Checking collection receipts before publication.");
            FileVerificationResult recorded = new ReceiptDocument(
                    journal, written.toString().getBytes(StandardCharsets.UTF_8)).receipt();
            SelfPlayCollectionFiles.verify(planned, context);
            SelfPlayCollectionFiles.verify(recorded, context);
            for (var receipt : receipts)
                SelfPlayCollectionFiles.verify(receipt, context);
            SelfPlayCollectionFiles.inputs(plan, context);
            if (Arrays.stream(results).map(SelfPlayResult::datasetFingerprint).distinct().count() != plan.batches())
                throw new IOException("Repeated batch dataset fingerprint");
            Map<String, Object> manifest = object(
                    "schema", SelfPlayCollectionFiles.SCHEMA, "complete", true,
                    "batch_count", plan.batches(), "games", plan.games(),
                    "files", object("plan.json", SelfPlayCollectionFiles.receipt(planned),
                            "batches.jsonl", SelfPlayCollectionFiles.receipt(recorded)),
                    "batches", rows, "training_performed", false, "test_opened", false,
                    "training_admission_performed", false, "promotion_performed", false);
            context.checkCancelled();
            FileVerificationResult pending = SelfPlayCollectionFiles.write(
                    directory.resolve("manifest.pending"), manifest);
            SelfPlayCollectionFiles.verify(pending, context);
            context.checkCancelled();
            Path complete = directory.resolve("manifest.json");
            Files.move(pending.file(), complete, StandardCopyOption.ATOMIC_MOVE);
            return new SelfPlayCollectionResult(directory, Arrays.asList(results),
                    new FileVerificationResult(complete, pending.bytes(), pending.sha256()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }

    }

}