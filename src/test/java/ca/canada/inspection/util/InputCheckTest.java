package ca.canada.inspection.util;

import ca.canada.inspection.util.InputCheck.Depth;
import ca.canada.inspection.util.InputCheck.Issue;
import ca.canada.inspection.util.InputCheck.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputCheckTest {

    private static final String READ = "@r\nACGT\n+\nIIII\n";

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private void gzip(String name, String content) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(name)))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void goodFilesMakeTheSamplesAndNothingIsSaid() throws IOException {
        write("genome.fasta", ">c1\nACGT\n");
        gzip("zipped.fna.gz", ">c1\nACGT\n");
        write("reads_R1.fastq", READ + READ);
        gzip("reads_R2.fastq.gz", READ + READ);
        write("notes.txt", "not a sequence file, not looked at");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(List.of("genome", "reads", "zipped"), List.copyOf(result.samples().keySet()));
        assertEquals(2, result.samples().get("reads").files().size());
        assertEquals(4, result.files());
        assertEquals(List.of(), result.issues());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void anEmptyLibraryIsLeftOutAndTheOthersRun() throws IOException {
        write("good_R1.fastq", READ);
        write("good_R2.fastq", READ);
        gzip("failed_R1.fastq.gz", "");
        gzip("failed_R2.fastq.gz", "");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(Set.of("good"), result.samples().keySet());
        assertEquals(Set.of("failed"), result.samplesNotAnalysed());
        assertEquals(List.of(
                "failed_R1.fastq.gz: the file holds no read (the file is empty). Sample failed is not analysed.",
                "failed_R2.fastq.gz: the file holds no read (the file is empty). Sample failed is not analysed."),
                result.warnings());
    }

    @Test
    void oneFaultyMateLeavesTheWholeSampleOut() throws IOException {
        // Run alone, the good mate would be taken for single-end reads.
        write("half_R1.fastq", READ + READ);
        write("half_R2.fastq", READ + "@r2\nACGT\n+\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 1);

        assertTrue(result.samples().isEmpty());
        Issue issue = result.issues().getFirst();
        assertEquals("half", issue.sample());
        assertEquals(List.of("half_R2.fastq"), issue.files());
        assertEquals("the file is incomplete", issue.problem());
        assertEquals(List.of(issue.line(), "No sample of " + dir + " can be analysed."), result.warnings());
    }

    @Test
    void matesWithDifferentNumbersOfReadsAreLeftOut() throws IOException {
        write("pair_R1.fastq", READ + READ + READ);
        write("pair_R2.fastq", READ + READ);
        write("single.fastq", READ);

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(Set.of("single"), result.samples().keySet());
        assertEquals(List.of(new Issue("pair", List.of("pair_R1.fastq", "pair_R2.fastq"),
                "the two files of the pair hold different numbers of reads", "3 and 2")), result.issues());
    }

    @Test
    void theQuickCheckSeesEmptyFilesButNotWhatComesLater() throws IOException {
        write("pair_R1.fastq", READ + READ + READ);
        write("pair_R2.fastq", READ + "@r2\nACGT\n+\n");
        write("empty.fastq", "");

        Result result = InputCheck.scan(dir, Depth.QUICK, 2);

        assertEquals(Set.of("pair"), result.samples().keySet());
        assertEquals(Set.of("empty"), result.samplesNotAnalysed());
    }

    @Test
    void faultyAssembliesAreNamedToo() throws IOException {
        write("empty.fasta", "");
        write("headers.fasta", ">c1\n>c2\n");
        write("text.fasta", "this is not a sequence\n");
        write("reads.fasta", READ);
        write("plain.fasta.gz", ">c1\nACGT\n");
        write("good.fa", ">c1\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 3);

        assertEquals(Set.of("good"), result.samples().keySet());
        assertEquals(List.of(
                "empty.fasta: the file holds no sequence (the file is empty)",
                "headers.fasta: the file holds no sequence (header lines only)",
                "plain.fasta.gz: the file cannot be read (not a gzip file, or a damaged one (Not in GZIP format))",
                "reads.fasta: not a FASTA file (the first line does not start with >)",
                "text.fasta: not a FASTA file (the first line does not start with >)"),
                result.issues().stream().map(Issue::describe).toList());
    }

    @Test
    void aSingleFileIsCheckedLikeAFolder() throws IOException {
        write("only_R1.fastq", "");

        Result result = InputCheck.scan(dir.resolve("only_R1.fastq"), Depth.FULL, 4);

        assertTrue(result.samples().isEmpty());
        assertEquals(1, result.files());
        assertEquals("only", result.issues().getFirst().sample());
    }

    @Test
    void aFolderWithoutSequenceFilesSaysSo() throws IOException {
        write("notes.txt", "nothing here");

        Result result = InputCheck.scan(dir, Depth.FULL, 1);

        assertEquals(0, result.files());
        assertEquals(List.of("No FASTA or FASTQ file in " + dir + ": nothing to analyse."), result.warnings());
    }

    @Test
    void theDepthIsReadFromItsOptionValue() {
        assertEquals(Depth.FULL, Depth.parse("full"));
        assertEquals(Depth.QUICK, Depth.parse("QUICK"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Depth.parse("none"));
        assertEquals("--input-check must be full or quick, not: none", error.getMessage());
    }
}
