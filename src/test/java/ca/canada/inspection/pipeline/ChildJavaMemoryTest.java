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
    void aChildAloneGetsTheWholeBudgetUpToTheCap() {
        assertEquals(1, ChildJavaMemory.forOneChild(1));
        assertEquals(12, ChildJavaMemory.forOneChild(12));
        assertEquals(64, ChildJavaMemory.forOneChild(250));
    }

    @Test
    void oneSampleGetsAllTheThreadsAndTheWholeBudget() {
        assertEquals(new ChildJavaMemory.Share(1, 16, 48), ChildJavaMemory.share(48, 16, 1));
    }

    @Test
    void samplesRunAtOnceShareTheThreadsAndTheBudget() {
        // -Xmx96g -t 16, 4 samples: 48 GiB in all.
        assertEquals(new ChildJavaMemory.Share(4, 4, 12), ChildJavaMemory.share(48, 16, 4));
    }

    @Test
    void noChildGetsLessThanTheMinimumHeap() {
        // 64 threads, 40 samples, 62 GiB: 15 at a time rather than 40 with 1 GiB each.
        assertEquals(new ChildJavaMemory.Share(15, 4, 4), ChildJavaMemory.share(62, 64, 40));
    }

    @Test
    void aBudgetBelowTheMinimumRunsOneSampleAtATime() {
        assertEquals(new ChildJavaMemory.Share(1, 8, 2), ChildJavaMemory.share(2, 8, 10));
    }

    @Test
    void moreSamplesThanThreadsRunAsManyAtOnceAsThereAreThreads() {
        assertEquals(new ChildJavaMemory.Share(2, 1, 50), ChildJavaMemory.share(100, 2, 10));
    }

    @Test
    void eachChildIsCapped() {
        assertEquals(new ChildJavaMemory.Share(2, 8, 64), ChildJavaMemory.share(500, 16, 2));
    }
}
