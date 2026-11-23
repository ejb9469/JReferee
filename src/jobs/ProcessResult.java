package jobs;

import java.nio.file.Path;

/**
 * The launched process's PID and merged log. A waiting launcher may own another PID.
 */
public record ProcessResult(long pid, Path log) implements JobResult { }