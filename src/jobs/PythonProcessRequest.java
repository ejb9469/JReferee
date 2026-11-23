package jobs;

import java.nio.file.Path;
import java.util.*;

public record PythonProcessRequest(
        PythonProcessRuntime runtime, String module, List<String> arguments, Path directory
) implements JobRequest<ProcessResult> {

    public PythonProcessRequest {
        Objects.requireNonNull(runtime, "runtime");
        if (module == null || !module.matches("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*"))
            throw new IllegalArgumentException("Invalid Python module");
        arguments = List.copyOf(arguments);
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

}