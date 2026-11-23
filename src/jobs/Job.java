package jobs;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * The handle returned when work is submitted.
 * Extends Java's `Future<>` for results, exceptions, waiting, & cancellation.
 * A cancelled handle can be done before its worker finishes cleaning up.
 */
public interface Job<O extends JobResult> extends Future<O> {
    // TODO: Decide whether strict, stating typing is appropriate, i.e. if we should drop "extends JobResult"

    UUID id();

    /**
     * The latest progress message.
     * This is not a log.
     */
    Optional<String> progress();

}