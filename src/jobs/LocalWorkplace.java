package jobs;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Worker-only services, kept separate from the caller's Future handle.
 */
final class LocalWorkplace implements Workplace {

    private final UUID id;
    private final BooleanSupplier cancelled;
    private volatile String progress;

    LocalWorkplace(UUID id, BooleanSupplier cancelled) {
        this.id = Objects.requireNonNull(id, "id");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    @Override
    public UUID jobId() {
        return id;
    }

    @Override
    public void reportProgress(String message) {
        progress = Objects.requireNonNull(message, "message");
    }

    @Override
    public void checkCancelled() throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Job cancelled or interrupted: " + id);
    }

    Optional<String> progress() {
        return Optional.ofNullable(progress);
    }

}