package jobs;

import java.util.UUID;

/**
 * Per-job services. Do not retain this context after execute returns.
 */
public interface Workplace {

    UUID jobId();

    void reportProgress(String message);

    /**
     * Checks cancellation and thread interruption without clearing the interrupt flag.
     */
    void checkCancelled() throws InterruptedException;

}