package ca.canada.inspection.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class ParallelStageRunner {
    private ParallelStageRunner() {
    }

    static <T> void run(String stageName, Collection<T> items, int requestedThreads, Consumer<T> action) {
        if (items.isEmpty()) {
            return;
        }

        int poolSize = Math.clamp(requestedThreads, 1, items.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(poolSize)) {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> action.accept(item)));
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
}
