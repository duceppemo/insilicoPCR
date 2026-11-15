package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildJavaMemoryTest {

    @Test
    void theBudgetIsAtLeastOneGiB() {
        int gib = ChildJavaMemory.budgetGiB();
        assertTrue(gib >= 1, "got " + gib);
        assertTrue(gib <= 1024, "implausible budget: " + gib);
    }

    @Test
    void theBudgetIsWhatThisProgramLeavesFreeOnTheComputer() {
        long gib = 1024L * 1024 * 1024;
        // 16 GB, Java's default heap of a quarter: three quarters of 12 GB, not half of 4 GB.
        assertEquals(9, ChildJavaMemory.budgetGiB(16 * gib, 4 * gib));
        // -Xmx96g on 128 GB: at least half of the heap, as before.
        assertEquals(48, ChildJavaMemory.budgetGiB(128 * gib, 96 * gib));
        // Rounded, not truncated: 3.9 GB of half a heap is 4.
        assertEquals(4, ChildJavaMemory.budgetGiB(0, (long) (7.8 * gib)));
        // The computer's memory unknown, the heap too: the default.
        assertEquals(4, ChildJavaMemory.budgetGiB(0, Long.MAX_VALUE));
    }

    @Test
    void aChildAloneGetsTheWholeBudgetUpToTheCap() {
        assertEquals(1, ChildJavaMemory.forOneChild(1));
        assertEquals(12, ChildJavaMemory.forOneChild(12));
        assertEquals(64, ChildJavaMemory.forOneChild(250));
    }

    @Test
    void oneSampleGetsAllTheThreadsAndTheWholeBudget() {
        assertEquals(new ChildJavaMemory.Share(1, 16, 0, 48), ChildJavaMemory.share(48, 16, 1));
    }

    @Test
    void samplesRunAtOnceShareTheThreadsAndTheBudget() {
        // -Xmx96g -t 16, 4 samples: 48 GiB in all.
        assertEquals(new ChildJavaMemory.Share(4, 4, 0, 12), ChildJavaMemory.share(48, 16, 4));
    }

    @Test
    void threadsThatDoNotDivideAreLeftForSomeChildrenToTakeOneMore() {
        // -t 16, 3 samples: 5 each, and one of them at a time runs with 6; 16 in all.
        assertEquals(new ChildJavaMemory.Share(3, 5, 1, 16), ChildJavaMemory.share(48, 16, 3));
        // -t 7, 4 samples: 1 each, and 3 of them with 2.
        assertEquals(new ChildJavaMemory.Share(4, 1, 3, 12), ChildJavaMemory.share(48, 7, 4));
    }

    @Test
    void noChildGetsLessThanTheMinimumHeap() {
        // 64 threads, 40 samples, 62 GiB: 15 at a time rather than 40 with 1 GiB each.
        assertEquals(new ChildJavaMemory.Share(15, 4, 4, 4), ChildJavaMemory.share(62, 64, 40));
    }

    @Test
    void aBudgetBelowTheMinimumRunsOneSampleAtATime() {
        assertEquals(new ChildJavaMemory.Share(1, 8, 0, 2), ChildJavaMemory.share(2, 8, 10));
    }

    @Test
    void moreSamplesThanThreadsRunAsManyAtOnceAsThereAreThreads() {
        assertEquals(new ChildJavaMemory.Share(2, 1, 0, 50), ChildJavaMemory.share(100, 2, 10));
    }

    @Test
    void eachChildIsCapped() {
        assertEquals(new ChildJavaMemory.Share(2, 8, 0, 64), ChildJavaMemory.share(500, 16, 2));
    }

    @Test
    void aBudgetAskedForIsTheBudget() {
        // --bbtools-memory, on a computer shared with others.
        assertEquals(24, ChildJavaMemory.budgetGiB(24));
        assertEquals(ChildJavaMemory.budgetGiB(), ChildJavaMemory.budgetGiB(PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY));
        assertEquals(64, ChildJavaMemory.forOneChild(ChildJavaMemory.budgetGiB(200)), "each still at most 64 GB");
    }
}
