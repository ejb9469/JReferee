package jobs;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Future handle and execution wrapper. Cleanup, not Future state, owns the slot.
 */
final class LocalJob<O extends JobResult> implements Job<O>, Runnable {

    private final UUID id = UUID.randomUUID();
    private final FutureTask<O> task;
    private final LocalWorkplace workplace;
    private final ThreadPoolExecutor executor;
    private final ThreadLocal<Boolean> executing;

    <I extends JobRequest<O>> LocalJob(
            Worker<I, O> worker, I request, ThreadPoolExecutor executor, ThreadLocal<Boolean> executing
    ) {

        this.executor = executor;
        this.executing = executing;
        this.workplace = new LocalWorkplace(id, this::isCancelled);
        this.task = new FutureTask<>(() -> {
            workplace.checkCancelled();
            O result = worker.execute(request, workplace);
            workplace.checkCancelled();
            return result;
        });

    }

    @Override
    public void run() {

        executing.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            executing.remove();
        }

    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public Optional<String> progress() {
        return workplace.progress();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {

        if (!task.cancel(mayInterruptIfRunning))
            return false;
        executor.remove(this);
        return true;

    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public boolean isDone() {
        return task.isDone();
    }

    @Override
    public O get() throws InterruptedException, ExecutionException {
        return task.get();
    }

    @Override
    public O get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return task.get(timeout, unit);
    }

}