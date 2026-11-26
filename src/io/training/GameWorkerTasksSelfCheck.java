package io.training;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

/**
 * Checks overlap, ordered results and cleanup before a failed batch returns.
 */
public final class GameWorkerTasksSelfCheck {

    private GameWorkerTasksSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("Usage: GameWorkerTasksSelfCheck");
        orderedRefill();
        boundedWorkers();
        failedCleanup();
        interruptedCleanup();
        System.out.println("GameWorkerTasksSelfCheck passed.");
    }

    private static void orderedRefill() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch first = new CountDownLatch(1), third = new CountDownLatch(1);
        List<Integer> completed = new ArrayList<>();
        List<Integer> results = GameWorkerTasks.map(List.of(0, 1, 2, 3), 2, index -> {
            if (index == 0) {
                first.countDown();
                await(third);
            } else if (index == 1) {
                await(first);
            } else if (index == 2) {
                third.countDown();
            }
            return index * index;
        }, index -> {
            require(Thread.currentThread() == caller, "Completion observer ran on a worker");
            completed.add(index);
        });
        require(results.equals(List.of(0, 1, 4, 9)), "Results followed completion order");
        require(completed.indexOf(1) < completed.indexOf(0), "A slow first game blocked refill");
        GameWorkerTasks.map(List.of(1, 2), 1, index -> {
            require(Thread.currentThread() == caller, "Serial mode moved work off its caller");
            return index;
        });
    }

    private static void boundedWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), started = new AtomicInteger();
        List<Integer> inputs = IntStream.range(0, 24).boxed().toList();
        FutureTask<List<Integer>> result = new FutureTask<>(() ->
                GameWorkerTasks.map(inputs, 8, index -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    started.incrementAndGet();
                    entered.countDown();
                    try {
                        await(release);
                        return index;
                    } finally {
                        active.decrementAndGet();
                    }
                }));
        Thread caller = new Thread(result, "game-task-bounds-check");
        caller.start();
        try {
            await(entered);
            require(started.get() == 8 && peak.get() == 8, "Worker bound or overlap differs");
            release.countDown();
            require(result.get(10, TimeUnit.SECONDS).equals(inputs), "Batch lost or reordered results");
            require(active.get() == 0 && peak.get() == 8, "Workers escaped the bound or outlived completion");
        } finally {
            release.countDown();
            stop(caller);
        }
    }

    private static void failedCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cleaning = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        Semaphore finish = new Semaphore(0);
        AtomicBoolean cleaned = new AtomicBoolean(), extra = new AtomicBoolean();
        IOException expected = new IOException("Fixture game failed");
        FutureTask<Boolean> result = new FutureTask<>(() -> {
            try {
                GameWorkerTasks.map(List.of(0, 1, 2), 2, index -> {
                    if (index == 0) {
                        entered.countDown();
                        try {
                            await(blocked);
                        } finally {
                            cleaning.countDown();
                            finish.acquireUninterruptibly();
                            cleaned.set(true);
                        }
                    } else if (index == 1) {
                        await(entered);
                        throw expected;
                    } else {
                        extra.set(true);
                    }
                    return index;
                });
                throw new AssertionError("Failed batch returned success");
            } catch (IOException failure) {
                require(failure == expected, "Original game failure was lost");
                return cleaned.get();
            }
        });
        Thread caller = new Thread(result, "game-task-failure-check");
        caller.start();
        try {
            await(cleaning);
            require(!result.isDone(), "Failure returned before worker cleanup");
            finish.release();
            require(result.get(10, TimeUnit.SECONDS) && !extra.get(),
                    "Failure failed to drain workers or submitted another game");
        } finally {
            blocked.countDown();
            finish.release();
            stop(caller);
        }
    }

    private static void interruptedCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        FutureTask<Boolean> result = new FutureTask<>(() -> {
            try {
                GameWorkerTasks.map(List.of(0, 1, 2), 2, index -> {
                    active.incrementAndGet();
                    entered.countDown();
                    try {
                        await(release);
                        return index;
                    } finally {
                        active.decrementAndGet();
                    }
                });
                throw new AssertionError("Interrupted batch returned success");
            } catch (InterruptedIOException expected) {
                return Thread.currentThread().isInterrupted() && active.get() == 0;
            }
        });
        Thread caller = new Thread(result, "game-task-interruption-check");
        caller.start();
        try {
            await(entered);
            caller.interrupt();
            require(result.get(10, TimeUnit.SECONDS), "Interruption lost its flag or returned before cleanup");
        } finally {
            release.countDown();
            stop(caller);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IOException("Timed out waiting for fixture");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Fixture interrupted");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static void stop(Thread thread) throws InterruptedException {
        thread.interrupt();
        thread.join(10_000);
        require(!thread.isAlive(), "Fixture coordinator did not stop");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}