package jobs;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared lifecycle for trusted programs and launchers that wait for their children.
 * Detached subprocesses are unsupported.
 */
final class ChildProcess {

    private ChildProcess() { }

    static ProcessResult run(ProcessBuilder builder, String kind, Workplace workplace)
            throws IOException, InterruptedException {

        Path directory = builder.directory().toPath();
        Path log = directory.resolve("process.log");
        workplace.checkCancelled();
        Files.createFile(log);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        workplace.checkCancelled();
        Process process = builder.start();
        try {
            Path pending = directory.resolve("process-pid.pending");
            Files.writeString(pending, process.pid() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.move(pending, directory.resolve("process.pid"), StandardCopyOption.ATOMIC_MOVE);
            process.getOutputStream().close();
            workplace.reportProgress("Started " + kind + " " + process.pid() + "; log: " + log);
            try (FileChannel output = FileChannel.open(log, StandardOpenOption.READ)) {
                while (true) {
                    workplace.checkCancelled();
                    boolean finished = process.waitFor(250, TimeUnit.MILLISECONDS);
                    reportTail(output, workplace);
                    if (finished)
                        break;
                }
            }
            workplace.checkCancelled();
            if (process.exitValue() != 0)
                throw new IOException("Child " + kind + " exited " + process.exitValue() + "; log: " + log);
            return new ProcessResult(process.pid(), log);
        } finally {
            stopAndWait(process);
        }

    }

    private static void reportTail(FileChannel channel, Workplace workplace) throws IOException {

        long size = channel.size();
        long start = Math.max(0, size - 16384);
        ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
        channel.position(start);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
        String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        int end = text.lastIndexOf('\n');
        if (end < 0)
            return;
        int begin = text.lastIndexOf('\n', end - 1) + 1;
        if (start > 0 && begin == 0)
            return;
        String line = text.substring(begin, end).strip();
        if (!line.isEmpty())
            workplace.reportProgress(line);

    }

    private static void stopAndWait(Process process) {

        boolean interrupted = Thread.interrupted();
        try {
            List<ProcessHandle> owned = new ArrayList<>();
            collectTree(process.toHandle(), owned);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (ProcessHandle handle : owned) {
                if (handle.isAlive()) {
                    handle.destroy();
                    while (handle.isAlive() && deadline - System.nanoTime() > 0) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    if (handle.isAlive())
                        handle.destroyForcibly();
                }
                while (handle.isAlive()) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }

    }

    private static void collectTree(ProcessHandle process, List<ProcessHandle> owned) {
        try (var children = process.children()) {
            for (ProcessHandle child : children.toList())
                collectTree(child, owned);
        }
        // Keep each launcher alive until its children have exited and can be reaped.
        owned.add(process);
    }

}