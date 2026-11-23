package jobs;

import io.training.SelfPlayFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

import static io.training.SelfPlayFiles.object;

/**
 * Real Python children and synthetic receipts. No PyTorch, production data or fitting.
 */
public final class PolicyTrainingWorkerSelfCheck {

    private static final PolicyTrainingSettings SETTINGS =
            new PolicyTrainingSettings(2, 8, 32, 16384, 0.0001, 0.0001, 1.0, 9_000_000_000L, 1);
    private static final String MODULE = "worker_fixture.train";
    private static final String FIXTURE = """
            import hashlib, json, os, signal, sys, time
            from pathlib import Path
            assert sys.flags.isolated == 1 and sys.flags.dont_write_bytecode == 1
            assert sys.flags.utf8_mode == 1
            assert sys.stdout.write_through and sys.stdout.encoding.lower().replace('-', '') == 'utf8'
            mode, template = sys.argv[1:3]
            args = sys.argv[3:]
            verify = '--verify-only' in args
            if mode == ('hang-verify' if verify else 'hang-fit'):
                if os.name != 'nt':
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                Path('partial.txt').write_text('preserve cancelled stage')
                Path('ready.pending').write_text(str(os.getpid()))
                Path('ready.pending').rename('ready')
                print('fixture waiting', flush=True)
                time.sleep(120)
            if verify:
                if mode == 'verify-fail':
                    print('deliberate verification failure', file=sys.stderr)
                    sys.exit(17)
                print('fixture verification complete')
                if mode == 'child-changed-sources':
                    Path(sys.path[0]).write_bytes(b'changed after module loading')
            else:
                output = Path(args[args.index('--output') + 1])
                output.mkdir()
                (output / 'partial.txt').write_text('preserve failed fit')
                if mode == 'fit-fail':
                    print('deliberate fit failure', file=sys.stderr)
                    sys.exit(23)
                if mode != 'missing-report':
                    report = json.loads(Path(template).read_text(encoding='utf-8'))
                    raw = b'synthetic checkpoint, not a trained model'
                    (output / 'policy.pt').write_bytes(raw)
                    report['checkpoint_sha256'] = hashlib.sha256(raw).hexdigest()
                    (output / 'training.json').write_text(json.dumps(report), encoding='utf-8')
                    if mode == 'corrupt-checkpoint':
                        (output / 'policy.pt').write_bytes(b'corrupt')
                    if mode == 'invalid-utf8':
                        (output / 'training.json').write_bytes(b'{"versions":"\\xff"}')
                for index in range(2000):
                    print('fixture ' + 'x' * 256)
                print('stderr retained', file=sys.stderr)
                print('UTF-8 ' + chr(233))
                print('fixture fit complete')
            """;

    private PolicyTrainingWorkerSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: PolicyTrainingWorkerSelfCheck <python-executable>");
        Path python = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Files.createTempDirectory("jreferee-training-worker-");
        try {
            successAndFailures(root, python);
            preflight(root, python);
            sourceGuards(root, python);
            launcherLifecycle(root, python);
            for (String stage : List.of("verify", "fit"))
                for (boolean interrupt : List.of(false, true))
                    cancelled(root, python, stage, interrupt);
            System.out.println("PolicyTrainingWorkerSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static void successAndFailures(Path root, Path python) throws Exception {

        try (Workforce workforce = new LocalWorkforce(1, 2)) {
            for (String mode : List.of(
                    "success", "epoch-zero", "trained-tie", "verify-fail", "fit-fail", "missing-report",
                    "incomplete", "wrong-settings", "wrong-dataset", "wrong-roots", "wrong-initial", "wrong-split",
                    "wrong-selection", "wrong-readback", "wrong-steps", "promotion", "corrupt-checkpoint",
                    "invalid-utf8", "changed-initial", "changed-manifest", "changed-sources",
                    "child-changed-sources", "changed-initial-after-fit", "changed-manifest-after-fit"
            )) {
                PolicyTrainingFixture fixture = fixture(root.resolve(mode), python, mode);
                var job = workforce.submit(fixture.worker(), fixture.request());
                Path attempt = fixture.request().directory();
                boolean success = List.of("success", "epoch-zero", "trained-tie").contains(mode);
                if (success) {
                    var result = get(job);
                    int selected = mode.equals("epoch-zero") ? 0 : mode.equals("trained-tie") ? 1 : 2;
                    require(result.selectedEpoch() == selected, "Wrong selected epoch");
                    require(Files.size(result.trainingLog()) > 300000, "Large child log lost");
                    String log = Files.readString(result.trainingLog());
                    require(log.contains("stderr retained") && log.contains("UTF-8 \u00e9"), "Child output lost");
                    Properties receipt = new Properties();
                    try (Reader reader = Files.newBufferedReader(attempt.resolve("worker-complete.properties"))) {
                        receipt.load(reader);
                    }
                    require(receipt.getProperty("job").equals(job.id().toString())
                            && receipt.getProperty("seed").equals("9000000000")
                            && receipt.getProperty("split_seed").equals("7"), "Durable request identity differs");
                    require(failure(workforce.submit(fixture.worker(), fixture.request()))
                            instanceof FileAlreadyExistsException, "Existing attempt was accepted");
                } else {
                    Throwable failure = failure(job);
                    require(failure instanceof IOException, "Expected explicit failure: " + failure);
                    require(!Files.exists(attempt.resolve("worker-complete.properties")), "Failure published success");
                    require(Files.exists(attempt.resolve("request.properties"))
                            && Files.exists(attempt.resolve("verify/process.log")), "Failure lost diagnostics");
                    if (mode.equals("verify-fail")) {
                        require(failure.getMessage().contains("17"), "Verification exit code lost");
                        require(!Files.exists(attempt.resolve("fit")), "Fitting started after verification failed");
                    } else if (mode.equals("fit-fail")) {
                        require(failure.getMessage().contains("23")
                                && Files.exists(attempt.resolve("model/partial.txt")), "Failed fit output lost");
                    } else if (mode.equals("changed-initial") || mode.equals("changed-manifest")
                            || mode.equals("child-changed-sources")) {
                        require(!Files.exists(attempt.resolve("fit")), "Changed inputs reached fitting");
                    }
                }
            }
        }

    }

    private static void preflight(Path root, Path python) throws Exception {

        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            for (String mode : List.of("bad-model", "bad-source", "bad-manifest", "missing-manifest",
                    "incomplete-cache", "mixed-split", "empty-validation", "duplicate")) {
                PolicyTrainingFixture fixture = fixture(root.resolve(mode), python, "success");
                var request = fixture.request();
                if (mode.equals("bad-model"))
                    Files.writeString(request.initialPolicy().file(), "changed");
                else if (mode.equals("bad-source"))
                    Files.writeString(fixture.runtime().sources().file(), "changed");
                else if (mode.equals("bad-manifest"))
                    Files.writeString(request.cacheManifests().getFirst().file(), "changed");
                else if (mode.equals("missing-manifest"))
                    Files.delete(request.cacheManifests().getFirst().file());
                else if (mode.equals("duplicate"))
                    request = new PolicyTrainingRequest(
                            List.of(request.cacheManifests().getFirst(), request.cacheManifests().getFirst()),
                            request.initialPolicy(), request.anchor(), 7, SETTINGS, request.directory());
                else {
                    Path manifest = request.cacheManifests().getLast().file();
                    Map<String, Object> changed = cache("2".repeat(64),
                            mode.equals("mixed-split") ? 8 : 7, 0, mode.equals("empty-validation") ? 0 : 2);
                    if (mode.equals("incomplete-cache"))
                        changed.put("complete", false);
                    write(manifest, changed);
                    request = new PolicyTrainingRequest(
                            List.of(request.cacheManifests().getFirst(), pinned(manifest)),
                            request.initialPolicy(), request.anchor(), 7, SETTINGS, request.directory());
                }
                require(failure(workforce.submit(fixture.worker(), request)) instanceof IOException,
                        "Invalid inputs were accepted");
                require(!Files.exists(request.directory()), "Preflight failure created an attempt");
            }
        }
        try {
            new PolicyTrainingSettings(1, 8, 32, 16384, Double.NaN, 0, 1, 7, 1);
            throw new AssertionError("Nonfinite settings accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("settings"), "Unexpected settings error");
        }

    }

    private static void sourceGuards(Path root, Path python) throws Exception {

        PolicyTrainingFixture fixture = fixture(root.resolve("source-guards"), python, "success");
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            for (String mode : List.of("missing-module", "missing-package", "shadowed-module")) {
                Path directory = Files.createDirectory(root.resolve(mode));
                var runtime = fixture.runtime();
                if (!mode.equals("missing-module")) {
                    Path sources = directory.resolve("sources.zip");
                    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(sources))) {
                        zip.putNextEntry(new ZipEntry(
                                mode.equals("shadowed-module") ? "os.py" : "worker_fixture/train.py"));
                        zip.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                    runtime = new PythonProcessRuntime(python, pinned(sources));
                }
                String module = mode.equals("missing-module") ? "json.tool"
                        : mode.equals("shadowed-module") ? "os" : MODULE;
                var request = new PythonProcessRequest(runtime, module, List.of(), directory);
                require(failure(workforce.submit(new PythonProcessWorker(), request)) instanceof IOException,
                        "Unpinned module or namespace package was accepted");
                if (mode.equals("shadowed-module"))
                    require(Files.readString(directory.resolve("process.log")).contains(
                            "Requested module is not from the pinned source ZIP"), "Wrong origin was not diagnosed");
                else
                    require(!Files.exists(directory.resolve("process.log")), "Invalid ZIP launched a child");
            }
        }

    }

    private static void cancelled(Path root, Path python, String stage, boolean interrupt) throws Exception {

        PolicyTrainingFixture fixture = fixture(root.resolve("cancel-" + stage + "-" + interrupt), python, "hang-" + stage);
        Path directory = fixture.request().directory().resolve(stage);
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            var job = workforce.submit(fixture.worker(), fixture.request());
            cancelled(job, workforce, directory, interrupt, false);
            require(!Files.exists(fixture.request().directory().resolve("worker-complete.properties")),
                    "Cancellation published success");
            if (stage.equals("verify"))
                require(!Files.exists(fixture.request().directory().resolve("fit")), "Cancelled verification fitted");
        }

    }

    private static void cancelled(
            Job<?> job, Workforce workforce, Path directory, boolean interrupt, boolean requireLauncher
    ) throws Exception {

        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((!Files.exists(directory.resolve("ready")) || !Files.exists(directory.resolve("process.pid")))
                    && System.nanoTime() < deadline)
                Thread.sleep(20);
            require(Files.exists(directory.resolve("ready")), "Python fixture did not become ready");
            long pid = Long.parseLong(Files.readString(directory.resolve("ready")));
            long launchedPid = Long.parseLong(Files.readString(directory.resolve("process.pid")).strip());
            child = ProcessHandle.of(pid).orElseThrow();
            ProcessHandle launched = ProcessHandle.of(launchedPid).orElseThrow();
            require(!requireLauncher || pid != launchedPid, "Fixture did not create a separate interpreter");
            List<ProcessHandle> owned = new ArrayList<>(List.of(launched));
            try (var descendants = launched.descendants()) {
                owned.addAll(descendants.toList());
            }
            require(owned.contains(child),
                    "Interpreter is not owned by recorded process: launched=" + launchedPid + ", interpreter=" + pid);
            require(child.isAlive() && launched.isAlive() && job.cancel(interrupt),
                    "Cancellation did not reach live processes");
            require(get(workforce.submit((PolicyTrainingCleanupRequest input, Workplace workplace) ->
                                    new PolicyTrainingCleanupResult(input.owned().stream().noneMatch(ProcessHandle::isAlive)),
                            new PolicyTrainingCleanupRequest(owned))).stopped(),
                    "Execution slot released before the launcher and Python stopped");
            require(Files.exists(directory.resolve("partial.txt")), "Cancellation removed partial output");
        } finally {
            try {
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                    child.onExit().get(10, TimeUnit.SECONDS);
                }
            } finally {
                job.cancel(true);
            }
        }

    }

    private static void launcherLifecycle(Path root, Path python) throws Exception {

        Path jar = root.resolve("waiting launcher.jar");
        String resource = "jobs/PolicyTrainingProcessFixture.class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             InputStream input = PolicyTrainingWorkerSelfCheck.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null)
                throw new IOException("Missing compiled launcher fixture");
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> launcher = List.of(java.toString(), "--disable-@files", "-Xmx64m",
                "-cp", jar.toString(), "jobs.PolicyTrainingProcessFixture");
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            for (String mode : List.of("success", "verify-fail", "cancel-false", "cancel-true",
                    "nested-cancel-false", "nested-cancel-true")) {
                Path directory = Files.createDirectory(root.resolve("launcher-" + mode));
                List<String> command = new ArrayList<>(launcher);
                if (mode.startsWith("nested-"))
                    command.addAll(launcher);
                command.addAll(List.of(python.toString(), "-I", "-B", "-u", "-X", "utf8", "-c",
                        FIXTURE, mode.contains("cancel-") ? "hang-verify" : mode, "unused", "--verify-only"));
                Job<ProcessResult> job = workforce.submit((PolicyTrainingLauncherRequest input, Workplace workplace) ->
                        ChildProcess.run(new ProcessBuilder(input.command()).directory(input.directory().toFile()),
                                "fixture launcher", workplace), new PolicyTrainingLauncherRequest(command, directory));
                if (mode.contains("cancel-"))
                    cancelled(job, workforce, directory, mode.endsWith("-true"), true);
                else if (mode.equals("verify-fail"))
                    require(failure(job) instanceof IOException exception && exception.getMessage().contains("17"),
                            "Launcher lost the interpreter exit code");
                else {
                    var result = get(job);
                    require(Files.readString(result.log()).contains("fixture verification complete"),
                            "Launcher lost Python output");
                }
            }
        }

    }

    private static PolicyTrainingFixture fixture(Path root, Path python, String mode) throws Exception {

        Files.createDirectory(root);
        Path sources = root.resolve("sources with spaces.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(sources))) {
            zip.putNextEntry(new ZipEntry("worker_fixture/__init__.py"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("worker_fixture/train.py"));
            zip.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var runtime = new PythonProcessRuntime(python, pinned(sources));
        Path model = Files.writeString(root.resolve("initial policy.pt"), "synthetic initial model");
        Path anchor = Files.writeString(root.resolve("anchor.bin"), "synthetic anchor");
        List<FileVerificationRequest> caches = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Path path = Files.createDirectory(root.resolve("cache-" + index)).resolve("manifest.json");
            write(path, cache(Integer.toString(index + 1).repeat(64), 7, index == 0 ? 9 : 0, index == 0 ? 0 : 2));
            caches.add(pinned(path));
        }
        var request = new PolicyTrainingRequest(caches, pinned(model), pinned(anchor), 7, SETTINGS,
                root.resolve("attempt with spaces"));
        var inputs = PolicyTrainingReceipts.inputs(request, workplace());
        double first = mode.equals("epoch-zero") ? 2.0 : 1.5;
        double best = mode.equals("epoch-zero") ? 2.0 : mode.equals("trained-tie") ? 1.5 : 1.0;
        int selected = mode.equals("epoch-zero") ? 0 : mode.equals("trained-tie") ? 1 : 2;
        Map<String, Object> report = object(
                "schema", "jreferee-self-play-policy-training-v1", "model_schema", "jreferee-type-conditioned-gru-v1",
                "complete", !mode.equals("incomplete"), "anchor_updated", false, "value_trained", false,
                "test_evaluated", false, "promotion_performed", mode.equals("promotion"), "optimizer_resumed", false,
                "anchor_sha256", request.anchor().expectedSha256(),
                "initial_policy", object("sha256", request.initialPolicy().expectedSha256()),
                "self_play_split_seed", "7", "datasets", inputs.datasets(), "policy_roots", inputs.roots(),
                "settings", SETTINGS.values(),
                "objective", "mean_over_roots(sum_candidate_q_times_negative_sum_conditional_logp)",
                "selection", "minimum_validation_root_cross_entropy_including_epoch_zero; strict_improvement",
                "versions", object("python", "fixture", "numpy", "not-used", "torch", "not-used"),
                "initial_validation", metric(2.0), "best_epoch", selected,
                "best_validation_cross_entropy", best,
                "improved_over_initial", !mode.equals("epoch-zero"),
                "selected_validation", metric(best),
                "epochs", List.of(
                        object("epoch", 1, "roots", 9, "optimizer_steps", 2, "validation", metric(first)),
                        object("epoch", 2, "roots", 9, "optimizer_steps", mode.equals("wrong-steps") ? 1 : 2,
                                "validation", metric(best))));
        switch (mode) {
            case "wrong-settings" -> report.put("settings", Map.of());
            case "wrong-dataset" -> report.put("datasets", List.of());
            case "wrong-roots" -> report.put("policy_roots", object("TRAIN", 8, "VALIDATION", 2));
            case "wrong-initial" -> report.put("initial_policy", object("sha256", "0".repeat(64)));
            case "wrong-split" -> report.put("self_play_split_seed", "8");
            case "wrong-selection" -> report.put("best_epoch", 1);
            case "wrong-readback" -> report.put("selected_validation", metric(1.1));
        }
        Path template = root.resolve("report.json");
        write(template, report);
        var worker = new PolicyTrainingWorker(runtime, (process, workplace) -> {
            require(process.module().equals("neural.self_play_train"), "Wrong trainer module");
            List<String> args = process.arguments();
            require(args.subList(0, 3).equals(List.of("--cache",
                            caches.getFirst().file().getParent().toString(), caches.getLast().file().getParent().toString())),
                    "Cache arguments differ");
            for (var entry : SETTINGS.values().entrySet()) {
                int flag = args.indexOf("--" + entry.getKey().replace('_', '-'));
                require(flag >= 0 && args.get(flag + 1).equals(entry.getValue().toString()), "Settings lost");
            }
            require(args.get(args.indexOf("--initial-policy") + 1).equals(model.toString())
                    && args.get(args.indexOf("--anchor") + 1).equals(anchor.toString()), "Model arguments differ");
            if (args.contains("--output"))
                require(Path.of(args.get(args.indexOf("--output") + 1))
                        .equals(request.directory().resolve("model")), "Output escaped the attempt");
            List<String> fixtureArgs = new ArrayList<>(List.of(mode, template.toString()));
            fixtureArgs.addAll(args);
            var result = new PythonProcessWorker().execute(new PythonProcessRequest(
                    runtime, MODULE, fixtureArgs, process.directory()), workplace);
            if (args.contains("--verify-only"))
                switch (mode) {
                    case "changed-initial" -> Files.writeString(model, "changed");
                    case "changed-manifest" -> Files.writeString(caches.getFirst().file(), "changed");
                    case "changed-sources" -> Files.writeString(sources, "changed");
                }
            else
                switch (mode) {
                    case "changed-initial-after-fit" -> Files.writeString(model, "changed");
                    case "changed-manifest-after-fit" -> Files.writeString(caches.getFirst().file(), "changed");
                }
            return result;
        });
        return new PolicyTrainingFixture(worker, request, runtime);

    }

    private static Map<String, Object> cache(String fingerprint, long seed, long train, long validation) {
        return object("schema", "jreferee-self-play-cache-v1", "complete", true, "test_opened", false,
                "byte_order", "LITTLE_ENDIAN", "split_seed", Long.toString(seed), "dataset_fingerprint", fingerprint,
                "policy_examples", train + validation, "policy_splits", object(
                        "TRAIN", List.of(object("examples", train)), "VALIDATION", List.of(object("examples", validation))));
    }

    private static Map<String, Object> metric(double score) {
        return object("split", "VALIDATION", "roots", 2, "cross_entropy", score);
    }

    private static Workplace workplace() {
        return new LocalWorkplace(new UUID(0, 1), () -> false);
    }

    private static void write(Path path, Object value) throws IOException {
        Files.writeString(path, SelfPlayFiles.json(value), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static FileVerificationRequest pinned(Path path) throws Exception {
        return new FileVerificationRequest(path,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    private static <T> T get(Future<T> job) throws Exception {
        return job.get(20, TimeUnit.SECONDS);
    }

    private static Throwable failure(Job<?> job) throws Exception {
        try {
            get(job);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
        throw new AssertionError("Expected failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}

record PolicyTrainingFixture(
        PolicyTrainingWorker worker, PolicyTrainingRequest request, PythonProcessRuntime runtime
) { }

record PolicyTrainingCleanupRequest(List<ProcessHandle> owned) implements JobRequest<PolicyTrainingCleanupResult> {
    PolicyTrainingCleanupRequest {
        owned = List.copyOf(owned);
    }
}

record PolicyTrainingCleanupResult(boolean stopped) implements JobResult { }

record PolicyTrainingLauncherRequest(List<String> command, Path directory) implements JobRequest<ProcessResult> {
    PolicyTrainingLauncherRequest {
        command = List.copyOf(command);
    }
}

final class PolicyTrainingProcessFixture {

    public static void main(String[] args) throws Exception {
        Process child = new ProcessBuilder(Arrays.asList(args)).inheritIO().start();
        System.exit(child.waitFor());
    }

}