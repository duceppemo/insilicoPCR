package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlastPipelineTest {

    @Test
    void searchesThatAllFinishedPass() {
        List<Future<?>> searches = List.of(CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> BlastPipeline.requireAllSucceeded(searches));
    }

    @Test
    void aFailedSearchFailsTheStageWithItsCause() {
        // An empty report would otherwise read as a sample without any primer hit.
        IllegalStateException failed = new IllegalStateException(
                "BLAST failed with exit code 3 for query /data/broken.fasta");
        List<Future<?>> searches = List.of(CompletableFuture.completedFuture(null),
                CompletableFuture.failedFuture(failed));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BlastPipeline.requireAllSucceeded(searches));

        assertEquals("BLAST failed with exit code 3 for query /data/broken.fasta", error.getMessage());
        assertSame(failed, error.getCause());
    }

    @Test
    void aSearchThatNeverRanFailsTheStage() {
        // What shutdownNow leaves behind: a queued task that will never complete.
        List<Future<?>> searches = List.of(new FutureTask<Void>(() -> null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BlastPipeline.requireAllSucceeded(searches));

        assertEquals("BLAST was stopped before every sample was searched", error.getMessage());
    }
}
