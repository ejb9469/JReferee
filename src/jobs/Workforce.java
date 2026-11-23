package jobs;

/**
 * Schedules any compatible Worker/Request pair
 * and returns the correct result type.
 */
public interface Workforce extends AutoCloseable {

    /**
     * Rejects submissions when closed or full. Do not mutate a submitted request.
     * Null inputs and outputs are permitted if the worker supports them.
     */
    <I extends JobRequest<O>, O extends JobResult> Job<O> submit(Worker<I, O> worker, I request);

    /**
     * Stops submissions and waits for accepted work, including cleanup.
     * Interruption is restored after waiting, not used to discard queued work.
     * Must not be called from one of this workforce's workers.
     */
    @Override
    void close();

}