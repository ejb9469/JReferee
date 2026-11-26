package io.training;

import jobs.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/**
 * Bounded game preparation with ordered results and caller-owned publication.
 */
final class GameWorkerTasks {  // package-private

    private GameWorkerTasks() { }

    @FunctionalInterface
    interface Operation<I, O> {
        O apply(I input) throws IOException;
    }

    private record Request<I, O>(I input, Operation<I, O> operation)
            implements JobRequest<Result<O>> { }

    private record Result<O>(O value) implements JobResult { }

    private record Pending<O>(int index, Job<Result<O>> job) { }

    static <I, O> List<O> map(List<I> inputs, int workers, Operation<I, O> operation)
            throws IOException {
        return map(inputs, workers, operation, index -> { });
    }

    static <I, O> List<O> map(
            List<I> inputs, int workers, Operation<I, O> operation, IntConsumer completed
    ) throws IOException {

        if (workers < 1)
            throw new IllegalArgumentException("Worker count must be positive");
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(completed, "completed");
        checkInterrupted();
        if (inputs.isEmpty())
            return List.of();

        if (workers == 1 || inputs.size() == 1) {
            List<O> results = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                checkInterrupted();
                results.add(Objects.requireNonNull(operation.apply(inputs.get(index)), "Game task result"));
                checkInterrupted();
                completed.accept(index);
            }
            checkInterrupted();
            return List.copyOf(results);
        }

        int parallelism = Math.min(workers, inputs.size());
        BlockingQueue<UUID> finished = new ArrayBlockingQueue<>(parallelism);
        Map<UUID, Pending<O>> active = new HashMap<>();
        List<O> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        LocalWorkforce workforce = new LocalWorkforce(parallelism, parallelism);
        Worker<Request<I, O>, Result<O>> worker = (request, workplace) -> {
            try {
                workplace.checkCancelled();
                return new Result<>(Objects.requireNonNull(
                        request.operation().apply(request.input()), "Game task result"));
            } finally {
                finished.add(workplace.jobId());
            }
        };
        try {
            int submitted = 0, received = 0;
            while (received < inputs.size()) {
                checkInterrupted();
                while (submitted < inputs.size() && active.size() < parallelism) {
                    checkInterrupted();
                    Job<Result<O>> job = workforce.submit(worker,
                            new Request<>(inputs.get(submitted), operation));
                    active.put(job.id(), new Pending<>(submitted++, job));
                }
                Pending<O> pending = active.remove(finished.take());
                if (pending == null)
                    throw new IllegalStateException("Unknown game task completion");
                results.set(pending.index(), pending.job().get().value());
                checkInterrupted();
                completed.accept(pending.index());
                received++;
            }
            checkInterrupted();
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Game preparation interrupted");
            failure.initCause(exception);
            throw failure;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException failure)
                throw failure;
            if (cause instanceof RuntimeException failure)
                throw failure;
            if (cause instanceof Error failure)
                throw failure;
            throw new IOException("Game preparation failed", cause);
        } finally {
            for (Pending<O> pending : active.values())
                pending.job().cancel(true);
            workforce.close();
        }

    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Game preparation interrupted");
    }

}