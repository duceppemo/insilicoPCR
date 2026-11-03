package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildJavaMemoryTest {

    @Test
    void recommendationIsAlwaysAtLeastOneGiB() {
        int gib = ChildJavaMemory.recommendedGiB();
        assertTrue(gib >= 1, "got " + gib);
        assertTrue(gib <= 1024, "implausible recommendation: " + gib);
    }
}
