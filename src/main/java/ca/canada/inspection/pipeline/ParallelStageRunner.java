package ca.canada.inspection.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs one stage of a run on several items at once, and fails the stage when one item
 * fails: an item that is not done would otherwise read as one without a result.
 */
final class ParallelStageRunner {
    private ParallelStageRunner() {
    }

    static <T> void run(String stageName, Collection<T> items, int requestedThreads, Consumer<T> action) {
        run(stageName, items, requestedThreads, RunMonitor.CONSOLE, action, Progress.NONE);
    }

    /**
     * Once an item has failed, the items not yet started are skipped: the stage fails
     * anyway, and the run should say so without first running them all.
     *
     * @param monitor  checked before each item starts; a cancelled run starts no more items
     * @param progress told how many items are done, of how many, as each one finishes
     */
    static <T> void run(String stageName, Collection<T> items, int requestedThreads, RunMonitor monitor,
                        Consumer<T> action, Progress progress) {
        if (items.isEmpty()) {
            return;
        }
        int poolSize = Math.clamp(requestedThreads, 1, items.size());
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean();
        try (ExecutorService executor = Executors.newFixedThreadPool(poolSize)) {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    if (monitor.isCancelled()) {
                        throw new CancellationException(stageName + " was cancelled.");
                    }
                    if (failed.get()) {
                        return;
                    }
                    try {
                        action.accept(item);
                    } catch (RuntimeException | Error e) {
                        failed.set(true);
                        throw e;
                    }
                    progress.done(done.incrementAndGet(), items.size());
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageException(stageName + " interrupted", e);
        } catch (ExecutionException e) {
            throw new StageException(stageName + " failed", e.getCause());
        }
    }

    /** Progress of a stage. */
    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> {
        };

        void done(int done, int total);
    }
}
