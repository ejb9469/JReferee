package jobs;

import java.util.Objects;
import java.util.concurrent.*;

/**
 * Fixed concurrency and a bounded waiting queue.
 * No persistence or automatic retries.
 * All computation is local.
 */
public final class LocalWorkforce implements Workforce {

    private final ThreadPoolExecutor        executor;
    private final ThreadLocal<Boolean>      executing = new ThreadLocal<>();

    public LocalWorkforce(int parallelism, int queueCapacity) {

        if (parallelism < 1 || queueCapacity < 1)
            throw new IllegalArgumentException("Parallelism and queue capacity must be positive");
        executor = new ThreadPoolExecutor(
                parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());

    }

    @Override
    public <I extends JobRequest<O>, O extends JobResult> Job<O> submit(Worker<I, O> worker, I request) {

        Objects.requireNonNull(worker, "worker");
        LocalJob<O> job = new LocalJob<>(worker, request, executor, executing);
        executor.execute(job);
        return job;

    }

    @Override
    public void close() {

        if (Boolean.TRUE.equals(executing.get()))
            throw new IllegalStateException("A worker cannot close its own workforce");
        executor.shutdown();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }

    }

}