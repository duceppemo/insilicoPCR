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
    void oneReadOfAPairWithoutItsMateIsRunAsSingleEndSayingSo() throws IOException {
        // The mate missing from the folder, or a run given the R1 file alone: half the data was unused unseen.
        write("lone_R1.fastq", READ);
        write("pair_R1.fastq", READ);
        write("pair_R2.fastq", READ);
        write("numbered_1.fastq", READ); // bare _1 without _2: a sample's own name, not a mate

        assertEquals(List.of("lone_R1.fastq is named as read 1 of a pair, but its mate is not in the input: sample lone"
                + " is run as single-end reads"), InputCheck.scan(dir, Depth.FULL, 2).warnings());
        assertEquals(List.of("pair_R1.fastq is named as read 1 of a pair, but its mate is not in the input: sample pair"
                + " is run as single-end reads"), InputCheck.scan(dir.resolve("pair_R1.fastq"), Depth.FULL, 2).warnings());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aLinkToNothingAndFilesOfAKindNotReadAreNamed() throws IOException {
        // Beside other inputs, both were left out without a word.
        write("good.fasta", ">c\nACGT\n");
        Files.createSymbolicLink(dir.resolve("gone.fasta"), dir.resolve("deleted.fasta"));
        write("old.fas", ">c\nACGT\n");
        write("packed.fasta.bz2", "BZh");
        write("notes.txt", "not a sequence file");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(java.util.Set.of("good"), result.samples().keySet());
        assertEquals(List.of("gone.fasta: the file cannot be read (a link to a file that does not exist). Sample gone is not analysed.",
                "old.fas is not read: input files are named " + String.join(", ", SequenceFileUtils.ACCEPTED_EXTENSIONS),
                "packed.fasta.bz2 is not read: input files are named " + String.join(", ", SequenceFileUtils.ACCEPTED_EXTENSIONS)),
                result.warnings());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aFileThatCannotBeOpenedSaysWhy() throws IOException {
        // The reason was the file's path alone (an AccessDeniedException's message), which the user knows already.
        write("locked.fasta", ">c\nACGT\n");
        write("locked_R1.fastq", READ);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> none = java.util.Set.of();
        Files.setPosixFilePermissions(dir.resolve("locked.fasta"), none);
        Files.setPosixFilePermissions(dir.resolve("locked_R1.fastq"), none);
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(dir.resolve("locked.fasta")), "run as root");

        List<String> warnings = InputCheck.scan(dir, Depth.FULL, 2).warnings();

        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("locked.fasta: the file cannot be read (permission denied)")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("locked_R1.fastq") && line.contains("permission denied")),
                warnings.toString());
    }

    @Test
    void aFileWhoseNameMakesNoSampleNameIsLeftOut() throws IOException {
        // .fasta, ..fasta and ...fasta: the samples "", "." and "..", whose results went to the report folder
        // itself or above it.
        write(".fasta", ">c\nACGT\n");
        write("..fasta", ">c\nACGT\n");
        write("...fasta", ">c\nACGT\n");
        write("good.fasta", ">c\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(java.util.Set.of("good"), result.samples().keySet());
        assertEquals(3, result.issues().size());
        assertTrue(result.issues().stream().allMatch(issue -> issue.line().contains("the file's name makes no sample name")),
                result.issues().toString());
    }

    @Test
    void theRunsOwnOutputFolderInTheInputIsNotASubfolderOfInputs() throws IOException {
        // Its primer file made it look like a folder of inputs not read, which the user was told to give as input.
        write("good.fasta", ">c\nACGT\n");
        Path output = Files.createDirectory(dir.resolve("results"));
        Files.writeString(output.resolve("primer_tmp.fasta"), ">a-F\nACGTACG\n");

        assertEquals(List.of(), InputCheck.scan(dir, Depth.FULL, 2, output).warnings());
        assertEquals(1, InputCheck.scan(dir, Depth.FULL, 2).warnings().size(), "another folder is still named");
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
    void filesThatShareASampleNameButAreNotAPairAreLeftOut() throws IOException {
        write("both.fasta", ">c1\nACGT\n");
        write("both.fastq", READ);
        write("twice.fasta", ">c1\nACGT\n");
        gzip("twice.fna.gz", ">c1\nACGT\n");
        write("ones_R1.fastq", READ);
        write("ones_R1_001.fastq", READ);
        write("kept.fasta", ">c1\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(Set.of("kept"), result.samples().keySet());
        assertEquals(List.of("both", "ones", "twice"),
                result.issues().stream().map(Issue::sample).toList());
        assertEquals(List.of("both.fasta", "both.fastq"), result.issues().getFirst().files());
        assertTrue(result.warnings().getFirst().startsWith("both.fasta and both.fastq: these files have the same"
                + " sample name (a sample is one FASTA file, one FASTQ file or the _R1 and _R2 files of a read"
                + " pair; rename the files that are not). Sample both is not analysed."), result.warnings().getFirst());
    }

    @Test
    void matesWhoseSuffixesDifferInCaseAreAPair() throws IOException {
        write("s_r1.fastq", READ);
        write("s_R2.fastq", READ);

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(List.of(), result.issues());
        assertEquals(List.of("s_r1.fastq", "s_R2.fastq"), result.samples().get("s").files().stream()
                .map(file -> file.getFileName().toString()).toList());
    }

    @Test
    void assembliesNamedLikeReadMatesAreSeparateSamples() throws IOException {
        write("strain_R1.fasta", ">c1\nACGT\n");
        write("strain_R2.fasta", ">c1\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(List.of("strain_R1", "strain_R2"), List.copyOf(result.samples().keySet()));
        assertEquals(List.of(), result.issues());
    }

    @Test
    void sampleNamesThatDifferOnlyInCaseAreLeftOut() throws IOException {
        write("iso2.fasta", ">c1\nACGT\n");
        write("ISO2.fna", ">c1\nACGT\n");
        write("other.fasta", ">c1\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(Set.of("other"), result.samples().keySet());
        assertEquals(Set.of("iso2", "ISO2"), result.samplesNotAnalysed());
        assertTrue(result.issues().getFirst().describe().endsWith(
                ": the sample name differs only in case (or in its Unicode form) from another one (ISO2, iso2 would share a results"
                        + " folder; rename one)"), result.issues().getFirst().describe());
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
        assertEquals(List.of(issue.line()), result.warnings());
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
    void aFolderWithoutSequenceFilesHasNoFile() throws IOException {
        // The run stops on it (InSilicoPcrPipeline): no warning of the check's own.
        write("notes.txt", "nothing here");

        Result result = InputCheck.scan(dir, Depth.FULL, 1);

        assertEquals(0, result.files());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void theDepthIsReadFromItsOptionValue() {
        assertEquals(Depth.FULL, Depth.parse("full"));
        assertEquals(Depth.QUICK, Depth.parse("QUICK"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Depth.parse("none"));
        assertEquals("--input-check must be full or quick, not: none", error.getMessage());
    }

    @Test
    void aContigOnOneLineAndBlankLinesAreFine() throws IOException {
        // Longer than the block the file is read in.
        write("oneline.fasta", ">chromosome 1\n\n" + "ACGTTGCA".repeat(30_000) + "\n");
        write("leadingblank.fasta", ">c1\n   \n\nACGT");
        write("blanksonly.fasta", ">c1\n  \n\t\n>c2\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(Set.of("oneline", "leadingblank"), result.samples().keySet());
        assertEquals(List.of("blanksonly.fasta: the file holds no sequence (header lines only)"),
                result.issues().stream().map(Issue::describe).toList());
    }

    @Test
    void aHeaderThatIsNotUtf8DoesNotCostTheSample() throws IOException {
        // "Listeria sérovar" written in Latin-1: one byte that is not valid UTF-8.
        byte[] fasta = ">c1 Listeria s\u00e9rovar\nACGT\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(dir.resolve("latin1.fasta"), fasta);

        Result result = InputCheck.scan(dir, Depth.FULL, 1);

        assertEquals(Set.of("latin1"), result.samples().keySet());
        assertEquals(List.of(), result.issues());
    }

    @Test
    void aGzippedAssemblyIsReadToItsEndWhateverItsSize() throws IOException {
        gzip("big.fasta.gz", ">c1\n" + "ACGTTGCA".repeat(30_000) + "\n>c2\nGGCC\n");
        // The same, cut short: the first sequence line is whole, the stream is not.
        gzip("cut.fasta.gz", ">c1\n" + "ACGTTGCA".repeat(30_000) + "\n>c2\nGGCC\n");
        byte[] whole = Files.readAllBytes(dir.resolve("cut.fasta.gz"));
        Files.write(dir.resolve("cut.fasta.gz"), java.util.Arrays.copyOf(whole, whole.length / 2));

        for (Depth depth : Depth.values()) {
            Result result = InputCheck.scan(dir, depth, 1);

            assertEquals(Set.of("big"), result.samples().keySet(), depth.toString());
            assertEquals(List.of("cut.fasta.gz: the file cannot be read (the gzip stream ends early: the file is incomplete)"),
                    result.issues().stream().map(Issue::describe).toList(), depth.toString());
        }
    }

    @Test
    void aSubfolderHoldingSequenceFilesIsNamedNotReadSilently() throws IOException {
        // One folder per sample, as sequencers' software writes them: the sample was missing without a word.
        write("top.fasta", ">c1\nACGT\n");
        Files.createDirectories(dir.resolve("S2"));
        Files.writeString(dir.resolve("S2").resolve("S2_R1.fastq"), READ);
        Files.createDirectories(dir.resolve("notes"));
        Files.writeString(dir.resolve("notes").resolve("readme.txt"), "not a sequence");

        Result result = InputCheck.scan(dir, Depth.FULL, 1);

        assertEquals(Set.of("top"), result.samples().keySet());
        assertEquals(List.of(dir.resolve("S2").toAbsolutePath().normalize()), result.subfoldersNotRead());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().getFirst().startsWith("the folder " + dir.resolve("S2").toAbsolutePath().normalize()
                + " holds sequence files, which are not read"), result.warnings().getFirst());
    }

    @Test
    void namesWindowsCannotGiveAFolderAreNamed() {
        assertEquals("the sample name ends with a space", InputCheck.windowsFolderProblem("Sample 1 "));
        assertEquals("the sample name ends with a dot", InputCheck.windowsFolderProblem("iso."));
        assertEquals("the sample name is one Windows keeps for a device", InputCheck.windowsFolderProblem("con"));
        assertEquals("the sample name is one Windows keeps for a device", InputCheck.windowsFolderProblem("COM1.v2"));
        assertEquals(null, InputCheck.windowsFolderProblem("console"));
        assertEquals(null, InputCheck.windowsFolderProblem("Sample 1"));
    }

    @Test
    void blankLinesBeforeTheFirstHeaderAreSkippedAsBlastSkipsThem() throws IOException {
        write("blank.fasta", "\n\r\n  \t\n>c1\nACGT\n");
        write("comment.fasta", ";made by hand\n#v2\n>c1\nACGT\n"); // BLAST reads it too
        write("indented.fasta", "  >c1\nACGT\n");
        write("sameId.fasta", ">c1 one\nACGT\n>c1"); // the second header has no line end
        write("noId.fasta", ">c1\nACGT\n> \nGGCC\n"); // BLAST would name it Query_2

        Result result = InputCheck.scan(dir, Depth.QUICK, 1);

        assertEquals(Set.of("blank", "comment"), result.samples().keySet());
        assertEquals(List.of(
                "indented.fasta: not a FASTA file (the first line does not start with >)",
                "noId.fasta: a sequence has no ID (a header line holds nothing after its >; give each sequence an ID)",
                "sameId.fasta: two sequences share an ID (the ID c1 names more than one sequence; give each its own"
                        + " (the report and BLAST tell sequences apart by ID))"),
                result.issues().stream().map(Issue::describe).toList());
    }

    @Test
    void anInterruptedScanEndsAtOnce() throws IOException {
        write("reads_R1.fastq", READ.repeat(50_000));
        write("reads_R2.fastq", READ.repeat(50_000));

        Thread.currentThread().interrupt();
        try {
            // The application interrupts the run thread to cancel the run.
            java.util.concurrent.CancellationException error = assertThrows(
                    java.util.concurrent.CancellationException.class, () -> InputCheck.scan(dir, Depth.FULL, 2));
            assertEquals("The run was cancelled (input check).", error.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anAssemblyWhoseSequencesShareAnIdIsLeftOut() throws IOException {
        // Two assemblies joined into one file, each with a contig named 1: BLAST takes them, and a
        // primer on each would be paired into a product of two molecules.
        write("joined.fasta", ">1 chromosome\nACGTACGT\n>2\nACGT\n>1 plasmid\nGGGG\n");
        write("fine.fasta", ">1\nACGT\n>2\nACGT\n");

        Result full = InputCheck.scan(dir, Depth.FULL, 1);

        assertEquals(List.of("fine"), List.copyOf(full.samples().keySet()));
        assertTrue(full.warnings().getFirst().contains("two sequences share an ID"), full.warnings().toString());
        assertTrue(full.warnings().getFirst().contains("the ID 1 names more than one sequence"), full.warnings().toString());
    }

    @Test
    void sequencesSharingAnIdAreLeftOutAtAQuickCheckToo() throws IOException {
        // Headers only: quick on an assembly, and what it prevents is a product of two molecules.
        write("joined.fasta", ">1 chromosome\nACGTACGT\n>1 plasmid\nGGGG\n");
        Result quick = InputCheck.scan(dir, Depth.QUICK, 1);
        assertTrue(quick.samples().isEmpty(), quick.samples().toString());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aNameWithAControlOrInvisibleCharacterIsLeftOutAndShownEscaped() throws IOException {
        // A line break wrote made-up "Sample analysed:" lines in QAlog.txt; an escape sequence set the terminal's
        // title; a zero-width space made "iso\u200b01" look like iso01.
        write("evil\nSample analysed: FAKE.fasta", ">c\nACGT\n");
        write("iso\u200b01.fasta", ">c\nACGT\n");
        write("good.fasta", ">c\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertEquals(java.util.Set.of("good"), result.samples().keySet());
        assertEquals(2, result.issues().size());
        assertTrue(result.warnings().stream().allMatch(line -> line.contains("holds a control or invisible character")
                && !line.contains("\n") && !line.contains("\u200b")), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(line -> line.startsWith("evil\\u000aSample analysed: FAKE.fasta:")),
                result.warnings().toString());
    }

    @Test
    void namesThatDifferOnlyInTheirUnicodeFormAreLeftOut() throws IOException {
        // café typed (NFC) and café decomposed (NFD): two samples that look the same, one folder on macOS.
        write("caf\u00e9.fasta", ">c\nACGT\n");
        write("cafe\u0301.fasta", ">c\nACGT\n");

        Result result = InputCheck.scan(dir, Depth.FULL, 2);

        assertTrue(result.samples().isEmpty(), result.samples().keySet().toString());
        assertTrue(result.issues().getFirst().problem().contains("Unicode form"), result.issues().toString());
    }
}
