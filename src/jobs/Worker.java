package jobs;

/**
 * Executes one request. Instances must be safe for concurrent calls.
 * Request validation belongs to the worker.
 */
@FunctionalInterface
public interface Worker<I extends JobRequest<O>, O extends JobResult> {

    O execute(I request, Workplace workplace) throws Exception;

}