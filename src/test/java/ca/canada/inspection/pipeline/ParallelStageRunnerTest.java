package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void itemsRunOnDaemonThreads() {
        // Work in flight when the window closes must not keep the program running.
        List<Boolean> daemon = Collections.synchronizedList(new ArrayList<>());
        ParallelStageRunner.run("Test", List.of(1, 2, 3), 2, item -> daemon.add(Thread.currentThread().isDaemon()));
        assertEquals(List.of(true, true, true), daemon);
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

        PipelineException error = assertThrows(PipelineException.class, () -> ParallelStageRunner.run("BLAST",
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

        assertThrows(PipelineException.class, () -> ParallelStageRunner.run("BBDuk", List.of(1, 2, 3, 4, 5), 1,
                item -> {
                    started.add(item);
                    throw new PipelineException("BBDuk failed with exit code 1: bbduk");
                }));

        assertEquals(List.of(1), started);
    }

    @Test
    void anErrorInAnItemIsNotForTheUser() {
        PipelineException error = assertThrows(PipelineException.class, () -> ParallelStageRunner.run("BLAST",
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

        // Cancelled, not failed: the application says "Run cancelled".
        CancellationException error = assertThrows(CancellationException.class, () -> ParallelStageRunner.run("BLAST",
                List.of("a", "b"), 2, cancelled, item -> {
                    throw new AssertionError("ran");
                }, ParallelStageRunner.Progress.NONE));

        assertEquals("BLAST was cancelled.", error.getMessage());
    }

    @Test
    void anInterruptedWaitStopsTheStage() {
        // The item takes a while, so the stage is still waiting for it when it sees the interrupt;
        // an item already done would be returned without one.
        Thread.currentThread().interrupt();
        try {
            CancellationException error = assertThrows(CancellationException.class, () -> ParallelStageRunner.run("BLAST",
                    List.of("a"), 1, item -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertEquals("BLAST was cancelled.", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptStopsTheItemsRunning() throws InterruptedException {
        // A cancelled run must not wait for the work in flight (in the application, the run
        // thread is interrupted when the window is closed or the run cancelled).
        List<String> interrupted = Collections.synchronizedList(new ArrayList<>());
        Thread stage = Thread.ofPlatform().start(() -> assertThrows(CancellationException.class,
                () -> ParallelStageRunner.run("BLAST", List.of("a", "b"), 2, item -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.add(item);
                    }
                })));
        Thread.sleep(300);
        long start = System.nanoTime();
        stage.interrupt();
        stage.join(5_000);

        assertTrue(!stage.isAlive() && System.nanoTime() - start < 5_000_000_000L, "the stage waited for its items");
        assertEquals(List.of("a", "b"), interrupted.stream().sorted().toList());
    }

    @Test
    void theFirstFailureStopsTheItemsStillRunning() throws InterruptedException {
        // A sample failing at once was reported only after the others' programs had finished.
        List<String> interrupted = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        PipelineException error = assertThrows(PipelineException.class, () -> ParallelStageRunner.run("Tadpole",
                List.of("slow", "bad"), 2, item -> {
                    if (item.equals("bad")) {
                        throw new PipelineException("bad failed");
                    }
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.add(item);
                        throw new java.util.concurrent.CancellationException("interrupted");
                    }
                }));

        assertEquals("bad failed", error.getCause().getMessage(), "the failure, not the item it stopped");
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "waited for the slow item");
        assertEquals(List.of("slow"), interrupted);
    }
}
