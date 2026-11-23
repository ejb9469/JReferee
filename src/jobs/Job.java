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

    UUID id();

    /**
     * The latest progress message, not the complete job log.
     */
    Optional<String> progress();

}