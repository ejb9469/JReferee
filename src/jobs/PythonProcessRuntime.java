package jobs;

import java.nio.file.Path;
import java.util.*;

public record PythonProcessRuntime(Path pythonExecutable, FileVerificationRequest sources) {

    public PythonProcessRuntime {
        pythonExecutable = Objects.requireNonNull(pythonExecutable, "pythonExecutable")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(sources, "sources");
        if (!sources.file().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IllegalArgumentException("Use a frozen Python source ZIP");
    }

}