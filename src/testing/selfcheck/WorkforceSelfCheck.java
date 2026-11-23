package testing.selfcheck;

import jobs.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local scheduling and streaming verification. No models or game archives are needed.
 */
public final class WorkforceSelfCheck {

    private static final long WAIT_SECONDS = 10;

    private WorkforceSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: WorkforceSelfCheck");
        resultsAndFailures();
        boundedQueue();
        runningCancellation(false);
        runningCancellation(true);
        closing();
        fileVerification();
        System.out.println("WorkforceSelfCheck passed.");

    }

    @SuppressWarnings("try") // Deliberately exercises the self-close guard.
    private static void resultsAndFailures() throws Exception {

        try (Workforce workforce = new LocalWorkforce(1, 2)) {
            var identity = workforce.submit((WorkforceCheckRequest<UUID> request, Workplace workplace) -> {
                workplace.reportProgress("Working");
                return new WorkforceCheckResult<>(workplace.jobId());
            }, null);
            require(result(identity).value().equals(identity.id()), "Worker received a different job identity");
            require(identity.progress().orElseThrow().equals("Working"), "Progress was not published");
            require(!(identity instanceof Workplace), "Caller handle exposes worker-only services");

            IOException expected = new IOException("Worker failure");
            var failed = workforce.submit((WorkforceCheckRequest<Void> request, Workplace workplace) -> {
                throw expected;
            }, null);
            require(failure(failed) == expected, "Original worker failure was lost");

            var selfClose = workforce.submit((WorkforceCheckRequest<Void> request, Workplace workplace) -> {
                workforce.close();
                return null;
            }, null);
            require(failure(selfClose) instanceof IllegalStateException, "Self-close was not rejected");
            var next = workforce.submit(
                    (WorkforceCheckRequest<String> request, Workplace workplace) -> new WorkforceCheckResult<>(request.value()),
                    new WorkforceCheckRequest<>("next"));
            require(result(next).value().equals("next"), "A failed worker stopped the workforce");
            var nullable = workforce.submit(
                    (WorkforceCheckRequest<Void> request, Workplace workplace) -> {
                        require(request == null, "Null request was replaced");
                        return null;
                    }, null);
            require(result(nullable) == null, "Supported null result was replaced");
        }

    }

    private static void boundedQueue() throws Exception {

        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelledRan = new AtomicBoolean();
        Worker<WorkforceCheckRequest<Integer>, WorkforceCheckResult<Integer>> blocking = (request, workplace) -> {
            entered.countDown();
            await(release);
            return new WorkforceCheckResult<>(request.value());
        };
        try (Workforce workforce = new LocalWorkforce(2, 1)) {
            var first = workforce.submit(blocking, new WorkforceCheckRequest<>(4));
            var second = workforce.submit(blocking, new WorkforceCheckRequest<>(9));
            try {
                await(entered);
                var cancelled = workforce.submit((WorkforceCheckRequest<Integer> request, Workplace workplace) -> {
                    cancelledRan.set(true);
                    return new WorkforceCheckResult<>(request.value());
                }, new WorkforceCheckRequest<>(0));
                expect(RejectedExecutionException.class, () -> workforce.submit(blocking, new WorkforceCheckRequest<>(1)));
                require(cancelled.cancel(false), "Queued cancellation failed");
                expect(CancellationException.class, () -> result(cancelled));

                var replacement = workforce.submit(
                        (WorkforceCheckRequest<String> request, Workplace workplace) -> new WorkforceCheckResult<>(request.value()),
                        new WorkforceCheckRequest<>("replacement"));
                release.countDown();
                require(result(first).value() == 4 && result(second).value() == 9, "Concurrent results changed");
                require(result(replacement).value().equals("replacement"), "Cancelled job retained queue capacity");
                require(!cancelledRan.get(), "Cancelled queued work executed");
            } finally {
                release.countDown();
            }
        }

    }

    private static void runningCancellation(boolean interrupt) throws Exception {

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch check = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        Semaphore releaseCleanup = new Semaphore(0);
        AtomicBoolean observed = new AtomicBoolean();
        AtomicBoolean cleaned = new AtomicBoolean();
        AtomicBoolean nextRan = new AtomicBoolean();
        Worker<WorkforceCheckRequest<Void>, WorkforceCheckResult<Void>> blocking = (request, workplace) -> {
            entered.countDown();
            try {
                await(check);
                workplace.checkCancelled();
            } catch (InterruptedException exception) {
                observed.set(true);
                throw exception;
            } finally {
                cleaning.countDown();
                releaseCleanup.acquireUninterruptibly();
                cleaned.set(true);
            }
            return null;
        };
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            var running = workforce.submit(blocking, null);
            Job<WorkforceCheckResult<Boolean>> next;
            try {
                await(entered);
                next = workforce.submit((WorkforceCheckRequest<Boolean> request, Workplace workplace) -> {
                    nextRan.set(true);
                    return new WorkforceCheckResult<>(cleaned.get());
                }, null);
                require(running.cancel(interrupt), "Running cancellation failed");
                check.countDown();
                await(cleaning);
                require(observed.get(), "Workplace did not observe cancellation");
                expect(CancellationException.class, () -> result(running));
                require(!nextRan.get(), "Execution slot was released before cleanup");
                expect(RejectedExecutionException.class, () -> workforce.submit(blocking, null));
            } finally {
                check.countDown();
                releaseCleanup.release();
            }
            require(result(next).value(), "Next job started before cancelled worker cleanup finished");
        }

    }

    private static void closing() throws Exception {

        LocalWorkforce workforce = new LocalWorkforce(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        FutureTask<Boolean> closing = new FutureTask<>(() -> {
            Thread.currentThread().interrupt();
            closeEntered.countDown();
            workforce.close();
            return Thread.currentThread().isInterrupted();
        });
        Thread closer = new Thread(closing, "workforce-close-check");
        try {
            var first = workforce.submit((WorkforceCheckRequest<Integer> request, Workplace workplace) -> {
                entered.countDown();
                await(release);
                return new WorkforceCheckResult<>(request.value());
            }, new WorkforceCheckRequest<>(1));
            await(entered);
            var second = workforce.submit(
                    (WorkforceCheckRequest<Integer> request, Workplace workplace) -> new WorkforceCheckResult<>(request.value()),
                    new WorkforceCheckRequest<>(2));
            closer.start();
            await(closeEntered);
            expect(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            require(result(closing), "close did not restore its caller's interrupt flag");
            require(result(first).value() == 1 && result(second).value() == 2, "close discarded accepted work");
            expect(RejectedExecutionException.class,
                    () -> workforce.submit((WorkforceCheckRequest<Integer> request, Workplace workplace) ->
                            new WorkforceCheckResult<>(request.value()), new WorkforceCheckRequest<>(3)));
        } finally {
            release.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            require(!closer.isAlive(), "close did not finish");
            workforce.close();
        }

    }

    private static void fileVerification() throws Exception {

        Path directory = Files.createTempDirectory("jreferee-workforce-");
        Path file = directory.resolve("payload.bin");
        byte[] bytes = new byte[131089];
        new Random(7).nextBytes(bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        FileVerificationWorker worker = new FileVerificationWorker();
        FileVerificationRequest request = new FileVerificationRequest(
                file, hash.toUpperCase(Locale.ROOT));
        try (Workforce workforce = new LocalWorkforce(1, 1)) {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            FileVerificationResult verified = result(workforce.submit(worker, request));
            require(verified.bytes() == bytes.length && verified.sha256().equals(hash)
                            && verified.file().equals(file.toAbsolutePath().normalize()),
                    "Streaming receipt differs");
            require(Arrays.equals(Files.readAllBytes(file), bytes), "Verification changed the file");

            bytes[bytes.length - 1] ^= 1;
            Files.write(file, bytes);
            require(failure(workforce.submit(worker, request)) instanceof IOException,
                    "Changed file passed verification");
            Files.delete(file);
            require(failure(workforce.submit(worker, request)) instanceof NoSuchFileException,
                    "Missing file did not fail explicitly");
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }

    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static Throwable failure(Job<?> job) throws Exception {
        try {
            result(job);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
        throw new AssertionError("Expected worker failure");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for fixture");
    }

    private static void expect(Class<? extends Exception> type, WorkforceChecked action) throws Exception {
        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception))
                return;
            throw exception;
        }

        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}

record WorkforceCheckRequest<T>(T value) implements JobRequest<WorkforceCheckResult<T>> { }

record WorkforceCheckResult<T>(T value) implements JobResult { }

interface WorkforceChecked { void run() throws Exception; }