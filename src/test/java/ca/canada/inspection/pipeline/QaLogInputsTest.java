package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.InputCheck;
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
    void sampleNamesPairMatesFromTheLoggedListEvenWhenTheFilesAreGone() {
        // None of these files exist: names must come from the logged list alone.
        assertEquals(java.util.Set.of("ERR2938678", "SRR642185"), QaLogInputs.sampleNames(List.of(
                "/gone/ERR2938678_1.fastq.gz", "/gone/ERR2938678_2.fastq.gz",
                "/gone/SRR642185_1.fastq.gz", "/gone/SRR642185_2.fastq.gz")));
    }

    @Test
    void sampleNamesSkipNonSequenceEntriesAndSubfoldersOfAFolderInput() throws IOException {
        Path input = Files.createDirectories(dir.resolve("input"));
        Files.createDirectories(input.resolve("sub"));
        Files.writeString(input.resolve("T.fastq.gz"), "");
        Files.writeString(input.resolve("md5sums.txt"), "");
        // A folder input is logged entry by entry, subfolders included; the pipeline runs only T.
        assertEquals(java.util.Set.of("T"), QaLogInputs.sampleNames(List.of(
                input.resolve("T.fastq.gz").toString(), input.resolve("md5sums.txt").toString(),
                input.resolve("sub").toString(), input.resolve("samplesheet.csv").toString())));
    }

    @Test
    void sampleNamesFollowThePipelinesPairingRules() {
        // /y/D_2.fq has no mate in its own folder, so it stays a sample of its own, as in the pipeline.
        assertEquals(java.util.Set.of("A", "B_1", "B_2", "B_3", "C_1", "C_2", "D", "D_2"), QaLogInputs.sampleNames(List.of(
                "/x/A_R1_001.fastq.gz", "/x/A_R2_001.fastq.gz",        // Illumina suffix: always a pair
                "/x/B_1.fastq.gz", "/x/B_2.fastq.gz", "/x/B_3.fastq.gz", // _3 present: sequential samples
                "/x/C_1.fasta", "/x/C_2.fasta",                           // FASTA _1/_2 are distinct samples
                "/x/D_1.fq", "/y/D_2.fq", "/x/D_2.fq")));                  // mate looked up in its own folder
    }

    @Test
    void theRunSectionIsSeparatedFromTheInputs() throws IOException {
        Path input = Files.createDirectory(dir.resolve("reads"));
        Files.writeString(input.resolve("S_R1.fastq.gz"), "");
        Files.writeString(input.resolve("S_R2.fastq.gz"), "");
        QaLogWriter.write(dir, "0.0.0", Path.of("/bbmap"), Path.of("/blast"), Path.of("/primers.fasta"), input,
                List.of("FASTQ mode Tadpole settings: k=31", "FASTQ mode diagnostics: x.tsv"));

        List<String> lines = Files.readAllLines(dir.resolve("QAlog.txt"));
        int inputs = lines.indexOf("Input File(s) :");
        assertEquals(input.resolve("S_R2.fastq.gz").toString(), lines.get(inputs + 2));
        assertEquals("", lines.get(inputs + 3));
        assertEquals("FASTQ mode Tadpole settings: k=31", lines.get(inputs + 4));
        assertEquals(List.of(input.resolve("S_R1.fastq.gz").toString(), input.resolve("S_R2.fastq.gz").toString()),
                QaLogInputs.read(dir.resolve("QAlog.txt")));
    }

    @Test
    void samplesLeftOutForAFaultyFileAreReadBackFromTheLog() throws IOException {
        // Their files are in the input list, but they are not samples without a product.
        List<String> section = RunDetails.inputIssueLines(List.of(
                new InputCheck.Issue("failed", List.of("failed_R1.fastq.gz"), "the file holds no read", "the file is empty"),
                new InputCheck.Issue("failed", List.of("failed_R2.fastq.gz"), "the file holds no read", "the file is empty"),
                new InputCheck.Issue("cut short", List.of("cut short.fastq"), "the file is incomplete",
                        "it ends inside read 2, after 1 complete read")));
        Path log = Files.writeString(dir.resolve("QAlog.txt"), HEADER + "/data/failed_R1.fastq.gz\n\n"
                + String.join("\n", section) + "\nFASTQ mode Tadpole settings: k=31\n");

        assertEquals(List.of(
                "Input not analysed: a file of these samples is faulty, and nothing was run on them.",
                "Sample not analysed: failed",
                "  failed_R1.fastq.gz: the file holds no read (the file is empty)",
                "  failed_R2.fastq.gz: the file holds no read (the file is empty)",
                "Sample not analysed: cut short",
                "  cut short.fastq: the file is incomplete (it ends inside read 2, after 1 complete read)",
                ""), section);
        assertEquals(java.util.Set.of("failed", "cut short"), QaLogInputs.samplesNotAnalysed(log));
        assertEquals(List.of("/data/S_R1.fastq.gz", "/data/S_R2.fastq.gz", "/data/failed_R1.fastq.gz"),
                QaLogInputs.read(log), "the input list still ends at the blank line");
    }

    @Test
    void aLogWithoutFaultyInputNamesNoSample() throws IOException {
        Path log = Files.writeString(dir.resolve("QAlog.txt"), HEADER);

        assertEquals(List.of(), RunDetails.inputIssueLines(List.of()));
        assertEquals(java.util.Set.of(), QaLogInputs.samplesNotAnalysed(log));
    }
}
