package jobs;

/**
 * An immutable request declaring the result type its worker must return.
 */
public interface JobRequest<O extends JobResult> { }