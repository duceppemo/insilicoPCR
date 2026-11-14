package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelStageRunnerTest {

    @Test
    void everyItemIsRunAndCounted() {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        List<Integer> progress = Collections.synchronizedList(new ArrayList<>());

        ParallelStageRunner.run("Test", List.of(1, 2, 3, 4), 3, RunMonitor.CONSOLE, seen::add,
                (done, total) -> {
                    assertEquals(4, total);
                    progress.add(done);
                });

        assertEquals(List.of(1, 2, 3, 4), seen.stream().sorted().toList());
        assertEquals(List.of(1, 2, 3, 4), progress.stream().sorted().toList());
    }

    @Test
    void noItemsIsNothingToDo() {
        ParallelStageRunner.run("Test", List.<String>of(), 4, item -> {
            throw new AssertionError("ran");
        });
    }

    @Test
    void aFailedItemFailsTheStageAndKeepsItsCause() {
        // An item without a result would otherwise read as one without a primer hit.
        PipelineException failed = new PipelineException("blastn failed with exit code 3: blastn -query a.fasta");

        StageException error = assertThrows(StageException.class, () -> ParallelStageRunner.run("BLAST",
                List.of("a", "b"), 2, item -> {
                    if (item.equals("a")) {
                        throw failed;
                    }
                }));

        assertEquals("BLAST failed", error.getMessage());
        assertSame(failed, error.getCause());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void afterAFailureNoFurtherItemIsStarted() {
        // One thread: the items run in order, and the first one fails.
        List<Integer> started = Collections.synchronizedList(new ArrayList<>());

        assertThrows(StageException.class, () -> ParallelStageRunner.run("BBDuk", List.of(1, 2, 3, 4, 5), 1,
                item -> {
                    started.add(item);
                    throw new PipelineException("BBDuk failed with exit code 1: bbduk");
                }));

        assertEquals(List.of(1), started);
    }

    @Test
    void anErrorInAnItemIsNotForTheUser() {
        StageException error = assertThrows(StageException.class, () -> ParallelStageRunner.run("BLAST",
                List.of("a"), 1, item -> {
                    throw new IllegalStateException("bug");
                }));

        assertTrue(!RunFailure.isForTheUser(error));
    }

    @Test
    void aCancelledRunStartsNoItem() {
        RunMonitor cancelled = new RunMonitor() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        StageException error = assertThrows(StageException.class, () -> ParallelStageRunner.run("BLAST",
                List.of("a", "b"), 2, cancelled, item -> {
                    throw new AssertionError("ran");
                }, ParallelStageRunner.Progress.NONE));

        assertInstanceOf(CancellationException.class, error.getCause());
    }

    @Test
    void anInterruptedWaitStopsTheStage() {
        Thread.currentThread().interrupt();
        try {
            StageException error = assertThrows(StageException.class, () -> ParallelStageRunner.run("BLAST",
                    List.of("a"), 1, item -> {
                    }));
            assertEquals("BLAST interrupted", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
