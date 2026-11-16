package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqProcessorTest {

    @Test
    void onlyTheFirstBaitStoppingOnTheReadsLeavesTheSampleOut() {
        // BBDuk stopping on a sample's reads (exit 1) failed the run, every other sample with it.
        ProgramFailedException refused = new ProgramFailedException("BBDuk", 1, false, "BBDuk failed with exit code 1");
        assertTrue(FastqProcessor.refusedTheReads(true, refused));
        // Not the reads' fault: out of memory (run again alone), killed, or a later step on the program's own files.
        assertFalse(FastqProcessor.refusedTheReads(true, new ProgramFailedException("BBDuk", 1, true, "out of memory")));
        assertFalse(FastqProcessor.refusedTheReads(true, new ProgramFailedException("BBDuk", 137, false, "killed")));
        assertFalse(FastqProcessor.refusedTheReads(false, refused));
    }
}
