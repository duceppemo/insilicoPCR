package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QaLogInputsTest {

    @TempDir
    Path dir;

    private static final String HEADER = "In Silico PCR version: 0.10.1\nPrimer File: /p.fasta\nInput File(s) :\n"
            + "/data/S_R1.fastq.gz\n/data/S_R2.fastq.gz\n";

    @Test
    void stopsAtTheBlankLineBeforeTheFastqSection() throws IOException {
        Path log = Files.writeString(dir.resolve("QAlog.txt"), HEADER
                + "\nFASTQ mode Tadpole settings: k=31 mincountseed=2 mincov=1 mincontig=60\n"
                + "FASTQ mode diagnostics: /out/consolidated_report/report_fastq_diagnostics.tsv\n");

        assertEquals(List.of("/data/S_R1.fastq.gz", "/data/S_R2.fastq.gz"), QaLogInputs.read(log));
    }

    @Test
    void readsLogsWrittenBy0100WithoutTheBlankLine() throws IOException {
        // 0.10.0 wrote the FASTQ lines straight after the inputs; they are not input paths.
        Path log = Files.writeString(dir.resolve("QAlog.txt"), HEADER
                + "FASTQ mode Tadpole settings: k=31 mincountseed=2 mincov=1 mincontig=60\n"
                + "  sample S, assay BA: not in the mini-assembly (...; assembly: NOT_ASSEMBLED)\n");

        assertEquals(List.of("/data/S_R1.fastq.gz", "/data/S_R2.fastq.gz"), QaLogInputs.read(log));
    }

    @Test
    void appendedFastqSectionIsSeparatedFromTheInputs() throws IOException {
        // The GUI's QA log header ends without a newline after the last input.
        Files.writeString(dir.resolve("QAlog.txt"), HEADER.stripTrailing());
        LogFiles.appendQaLog(dir, List.of("FASTQ mode Tadpole settings: k=31", "FASTQ mode diagnostics: x.tsv"));

        List<String> lines = Files.readAllLines(dir.resolve("QAlog.txt"));
        assertEquals("/data/S_R2.fastq.gz", lines.get(4));
        assertEquals("", lines.get(5));
        assertEquals("FASTQ mode Tadpole settings: k=31", lines.get(6));
        assertEquals(List.of("/data/S_R1.fastq.gz", "/data/S_R2.fastq.gz"), QaLogInputs.read(dir.resolve("QAlog.txt")));
    }
}
