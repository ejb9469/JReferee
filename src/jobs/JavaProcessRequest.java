package jobs;

import java.nio.file.Path;
import java.util.*;

public record JavaProcessRequest(
        JavaProcessRuntime runtime, String mainClass, List<String> arguments, Path directory
) implements JobRequest<ProcessResult> {

    public JavaProcessRequest {
        Objects.requireNonNull(runtime, "runtime");
        if (mainClass == null || !mainClass.matches("[\\w$]+(?:\\.[\\w$]+)*"))
            throw new IllegalArgumentException("Invalid Java main class");
        arguments = List.copyOf(arguments);
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

}