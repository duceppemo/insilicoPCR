package ca.canada.inspection.pipeline;

import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        // Daemon threads: work in flight when the application's window closes does not keep
        // the program running.
        try (ExecutorService executor = Executors.newFixedThreadPool(poolSize,
                Thread.ofPlatform().name(stageName, 1).daemon().factory())) {
            // Waited for in the order they end: the first to fail stops the others at once, rather
            // than after every item before it in the list has run (an hour of Tadpole, say).
            CompletionService<Void> completion = new ExecutorCompletionService<>(executor);
            for (T item : items) {
                completion.submit(() -> {
                    if (monitor.isCancelled()) {
                        throw cancelled(stageName);
                    }
                    if (failed.get()) {
                        return null;
                    }
                    try {
                        action.accept(item);
                    } catch (RuntimeException | Error e) {
                        failed.set(true);
                        throw e;
                    }
                    progress.done(done.incrementAndGet(), items.size());
                    return null;
                });
            }
            try {
                for (int i = 0; i < items.size(); i++) {
                    try {
                        completion.take().get();
                    } catch (ExecutionException e) {
                        // The items still running are stopped: their programs killed, their own
                        // failure (an interrupted program) never looked at. This one is the stage's.
                        executor.shutdownNow();
                        if (e.getCause() instanceof CancellationException cancelled) {
                            throw cancelled; // the run was cancelled: not a failure of this stage
                        }
                        throw new PipelineException(stageName + " failed", e.getCause());
                    }
                }
            } catch (InterruptedException e) {
                // The run thread is interrupted when the application cancels the run. The wait
                // took the interrupt: without passing it on, closing the pool would wait for the
                // items running to finish.
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw cancelled(stageName);
            }
        }
    }

    private static CancellationException cancelled(String stageName) {
        return new CancellationException(stageName + " was cancelled.");
    }

    /** Progress of a stage. */
    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> {
        };

        void done(int done, int total);
    }
}
