package jobs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Runs one trusted Java main class. The child must not launch further processes.
 */
public final class JavaProcessWorker
        implements Worker<JavaProcessRequest, ProcessResult> {

    @Override
    public ProcessResult execute(JavaProcessRequest request, Workplace workplace)
            throws IOException, InterruptedException {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workplace, "workplace");
        workplace.checkCancelled();
        JavaProcessRuntime runtime = request.runtime();
        if (!Files.isRegularFile(runtime.javaExecutable()) || !Files.isDirectory(request.directory()))
            throw new IOException("Java executable or working directory is missing");
        verifyRuntime(runtime, workplace);
        String classPath = String.join(File.pathSeparator,
                runtime.classPath().stream().map(file -> file.file().toString()).toList());
        List<String> command = new ArrayList<>(List.of(
                runtime.javaExecutable().toString(), "--disable-@files",
                "-Xmx" + runtime.heapMiB() + "m", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classPath, request.mainClass()));
        command.addAll(request.arguments());
        ProcessBuilder builder = new ProcessBuilder(command).directory(request.directory().toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
            if (!builder.environment().getOrDefault(variable, "").isBlank())
                throw new IOException("Unset " + variable + "; worker JVM options must be explicit");

        ProcessResult result = ChildProcess.run(builder, "JVM", workplace);
        verifyRuntime(runtime, workplace);
        return result;

    }

    private static void verifyRuntime(JavaProcessRuntime runtime, Workplace workplace)
            throws IOException, InterruptedException {
        FileVerificationWorker verifier = new FileVerificationWorker();
        for (var file : runtime.classPath()) {
            verifier.execute(file, workplace);
            try (JarFile jar = new JarFile(file.file().toFile())) {
                var manifest = jar.getManifest();
                if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null)
                    throw new IOException("Declare all runtime JARs explicitly; manifest Class-Path is unsupported");
            }
        }
    }

}