package jobs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Runs a trusted module from a pinned source ZIP using an existing Python environment.
 * The module must not launch further processes.
 */
public final class PythonProcessWorker
        implements Worker<PythonProcessRequest, ProcessResult> {

    private static final String BOOTSTRAP = """
            import importlib.util, os, runpy, sys
            archive = sys.argv.pop(1)
            module = sys.argv.pop(1)
            sys.path.insert(0, archive)
            spec = importlib.util.find_spec(module)
            expected = archive + os.sep + module.replace('.', os.sep) + '.py'
            if spec is None or spec.origin != expected:
                raise ImportError('Requested module is not from the pinned source ZIP: ' + module)
            runpy.run_module(module, run_name='__main__', alter_sys=True)
            """;

    @Override
    public ProcessResult execute(PythonProcessRequest request, Workplace workplace)
            throws IOException, InterruptedException {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workplace, "workplace");
        verify(request.runtime(), workplace);
        if (!Files.isDirectory(request.directory()))
            throw new NoSuchFileException("Python working directory is missing: " + request.directory());
        try (ZipFile sources = new ZipFile(request.runtime().sources().file().toFile())) {
            String module = request.module().replace('.', '/');
            requireSource(sources, module + ".py");
            for (int end = module.indexOf('/'); end >= 0; end = module.indexOf('/', end + 1))
                requireSource(sources, module.substring(0, end) + "/__init__.py");
        }
        List<String> command = new ArrayList<>(List.of(
                request.runtime().pythonExecutable().toString(), "-I", "-B", "-u", "-X", "utf8",
                "-c", BOOTSTRAP, request.runtime().sources().file().toString(), request.module()));
        command.addAll(request.arguments());
        ProcessResult result = ChildProcess.run(
                new ProcessBuilder(command).directory(request.directory().toFile()), "Python", workplace);
        verify(request.runtime(), workplace);
        return result;

    }

    static void verify(PythonProcessRuntime runtime, Workplace workplace) throws IOException, InterruptedException {
        workplace.checkCancelled();
        if (!Files.isRegularFile(runtime.pythonExecutable()))
            throw new NoSuchFileException("Python executable is missing: " + runtime.pythonExecutable());
        new FileVerificationWorker().execute(runtime.sources(), workplace);
    }

    private static void requireSource(ZipFile sources, String name) throws IOException {
        var entry = sources.getEntry(name);
        if (entry == null || entry.isDirectory())
            throw new IOException("Pinned source ZIP is missing " + name);
    }

}