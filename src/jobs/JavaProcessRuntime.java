package jobs;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

public record JavaProcessRuntime(
        Path javaExecutable, List<FileVerificationRequest> classPath, int heapMiB
) {

    public JavaProcessRuntime {
        javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable")
                .toAbsolutePath().normalize();
        classPath = List.copyOf(classPath);
        if (classPath.isEmpty() || heapMiB < 64)
            throw new IllegalArgumentException("Supply a classpath and at least 64 MiB heap");
        for (var file : classPath)
            if (!file.file().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    || file.file().toString().contains(File.pathSeparator))
                throw new IllegalArgumentException("Use frozen JARs, not live IDE class directories");
    }

}