package ca.canada.inspection.insilicopcr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PcrPipelineTaskTest {

    @Test
    void theLogAreaKeepsItsLatestLinesWhole() {
        // A large FASTQ run's lines slowed the window: the area keeps the latest, cut at a line start.
        String text = "line one\nline two\nline three\n";

        assertEquals(0, PcrPipelineTask.cutPoint(text, text.length(), 10), "within the limit");
        int cut = PcrPipelineTask.cutPoint(text, 20, 14);
        assertEquals("line three\n", text.substring(cut));
    }
}
