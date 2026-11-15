package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbtoolsCommandsTest {
    private final BbtoolsCommands commands = new BbtoolsCommands("java", Path.of("bbtools.jar"), 6, 4);
    private final List<Path> paired = List.of(Path.of("s_R1.fastq.gz"), Path.of("s_R2.fastq.gz"));

    @Test
    void firstBaitUsesPrimerDatabaseShortestPrimerAndMismatchBudget() {
        List<String> command = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"), 17, 2).arguments();

        assertTrue(command.contains("jgi.BBDuk"));
        assertTrue(command.contains("ref=primers.fasta"));
        assertTrue(command.contains("k=17"));
        assertTrue(command.contains("hdist=2"));
        assertTrue(command.contains("in1=s_R1.fastq.gz"));
        assertTrue(command.contains("in2=s_R2.fastq.gz"));
        assertTrue(command.contains("outm=out.fq.gz"));
        assertTrue(command.contains("overwrite=t"));
        assertTrue(command.contains("-Xmx6g"));
        assertTrue(command.contains("-XX:+ExitOnOutOfMemoryError"));
        assertTrue(command.contains("threads=4"));
        assertTrue(command.contains("ordered=t"), "reads in the order they came, every run");
    }

    @Test
    void secondBaitIsExactRegardlessOfMismatchBudget() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), paired, Path.of("second.fq.gz")).arguments();

        assertTrue(command.contains("ref=first.fq.gz"));
        assertTrue(command.contains("hdist=0"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("k=")));
    }

    @Test
    void singleEndReadsUseInOption() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), List.of(Path.of("s.fastq.gz")),
                Path.of("second.fq.gz")).arguments();

        assertTrue(command.contains("in=s.fastq.gz"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("in1=")));
    }

    @Test
    void assemblyRunsTadpoleWithOverwriteAndGivenSettings() {
        List<String> command = commands.assemble(Path.of("second.fq.gz"), Path.of("asm.fasta"),
                new TadpoleOptions(25, 2, 1, 80)).arguments();

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp", "bbtools.jar", "assemble.Tadpole",
                "in=second.fq.gz", "out=asm.fasta", "overwrite=t", "threads=4",
                "k=25", "mincountseed=2", "mincov=1", "mincontig=80"), command);
    }

    @Test
    void defaultTadpoleSettingsKeepShortContigs() {
        // Tadpole's own mincontig=500 drops contigs spanning short amplicons (#7).
        assertEquals(List.of("k=31", "mincountseed=2", "mincov=1", "mincontig=60"),
                TadpoleOptions.DEFAULTS.arguments());
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(0, 2, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(31, 0, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(31, 2, 1, 0));
    }

    @Test
    void rejectsMoreThanTwoReadFiles() {
        List<Path> three = List.of(Path.of("a"), Path.of("b"), Path.of("c"));
        assertThrows(IllegalArgumentException.class,
                () -> commands.secondBait(Path.of("first.fq.gz"), three, Path.of("second.fq.gz")).arguments());
    }

    @Test
    void readsOfCandidateProductsAreFetchedFromTheInputWithTheirMates() {
        List<String> command = commands.recruit(Path.of("candidates.fasta"), paired, Path.of("reads.fq.gz"), 31).arguments();

        assertTrue(command.contains("jgi.BBDuk"));
        assertTrue(command.contains("ref=candidates.fasta"));
        assertTrue(command.contains("in1=s_R1.fastq.gz"));
        assertTrue(command.contains("in2=s_R2.fastq.gz"));
        assertTrue(command.contains("outm=reads.fq.gz"));
        assertTrue(command.contains("interleaved=t"), "mates stay together");
        assertTrue(command.contains("hdist=0"));
        assertTrue(command.contains("k=31"));
    }

    @Test
    void extensionFollowsTheBetterSupportedBranchBothWays() {
        List<String> command = commands.extend(Path.of("seeds.fasta"), Path.of("second.fq.gz"),
                Path.of("extended.fasta"), 31, 2000).arguments();

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp", "bbtools.jar", "assemble.Tadpole",
                "in=seeds.fasta", "out=extended.fasta", "mode=extend", "el=2000", "er=2000", "extra=second.fq.gz",
                "k=31", "branchmult1=1", "branchmult2=1", "mincountextend=2", "overwrite=t", "threads=4"), command);
    }

    @Test
    void mergeKeepsUnmergedMates() {
        List<String> command = commands.mergePairs(Path.of("first.fq.gz"), Path.of("m.fq.gz"), Path.of("u.fq.gz")).arguments();

        assertTrue(command.contains("jgi.BBMerge"));
        assertTrue(command.contains("in=first.fq.gz"));
        assertTrue(command.contains("interleaved=t"));
        assertTrue(command.contains("out=m.fq.gz"));
        assertTrue(command.contains("outu=u.fq.gz"));
        assertTrue(command.contains("ordered=t"), "reads in the order they came, every run");
    }

    @Test
    void exitCodeThreeOrBbtoolsSayingSoIsRunningOutOfMemory() {
        assertTrue(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 3, false, "Tadpole failed")));
        assertTrue(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 1, true, "Tadpole failed")));
        assertFalse(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 1, false, "Tadpole failed")));
        assertFalse(BbtoolsCommands.ranOutOfMemory(new PipelineException("Tadpole failed with exit code 3")));
    }

    @Test
    void runningOutOfMemoryBelowTheCapSaysToRaiseTheHeap() {
        String message = BbtoolsCommands.outOfMemoryMessage("Tadpole", " on sample s", 8);

        assertTrue(message.startsWith("Tadpole ran out of memory on sample s with -Xmx8g, all it could have."
                + " Give insilicoPCR more memory with Java's -Xmx option"), message);
        assertTrue(message.endsWith("use fewer threads (-t) instead."), message);
    }

    @Test
    void runningOutOfMemoryAtTheCapDoesNotSayToRaiseTheHeap() {
        String message = BbtoolsCommands.outOfMemoryMessage("BBDuk", "", 64);

        assertTrue(message.startsWith("BBDuk ran out of memory with -Xmx64g, the most insilicoPCR gives a BBTools"
                + " program."), message);
        assertFalse(message.contains("-Xmx option"), message);
    }

    @Test
    void singleEndReadsAreNotTakenForInterleavedPairs() {
        // With interleaved=t, BBDuk took consecutive reads for mates: an odd count stopped it,
        // and each matching read brought its unrelated neighbour along.
        List<String> single = commands.firstBait(Path.of("primers.fasta"), List.of(Path.of("s.fastq.gz")),
                Path.of("out.fq.gz"), 18, 0).arguments();
        assertTrue(single.contains("in=s.fastq.gz"));
        assertTrue(single.contains("interleaved=f"), single.toString());
        assertTrue(!single.contains("interleaved=t"), single.toString());

        List<String> pairs = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"), 18, 0).arguments();
        assertTrue(pairs.contains("interleaved=t"), "two files: mates stay together");
    }

    @Test
    void filesAreNamedFromTheOutputFolderSoACommaAboveItIsLeftOut(@TempDir Path dir) {
        // BBTools splits a file argument at its commas: the folders the files share are left out.
        Path run = dir.resolve("results, run 2");
        Path sampleDir = run.resolve("detailed_report").resolve("s");
        BbtoolsCommands.Command command = commands.firstBait(run.resolve("primer_tmp.fasta"),
                List.of(run.resolve("reads").resolve("s.fastq.gz")), sampleDir.resolve("s_targetMatches.fastq.gz"), 17, 0);

        assertEquals(sampleDir.toAbsolutePath(), command.directory());
        assertTrue(command.arguments().contains("ref=" + Path.of("..", "..", "primer_tmp.fasta")), command::toString);
        assertTrue(command.arguments().contains("in=" + Path.of("..", "..", "reads", "s.fastq.gz")), command::toString);
        assertTrue(command.arguments().contains("outm=s_targetMatches.fastq.gz"), command::toString);
        assertTrue(command.arguments().contains("-cp") && command.arguments().contains("bbtools.jar"));
    }

    @Test
    void aCommaLeftInAFileArgumentIsAClearError(@TempDir Path dir) {
        Path reads = dir.resolve("reads, lane 1").resolve("s.fastq.gz");
        PipelineException error = assertThrows(PipelineException.class,
                () -> commands.secondBait(dir.resolve("out").resolve("first.fq.gz"), List.of(reads),
                        dir.resolve("out").resolve("second.fq.gz")));
        assertEquals("BBTools reads a comma in a file name as a list of files, and cannot read "
                + reads.toAbsolutePath().normalize() + ": remove the comma from the name of the file or of its folder",
                error.getMessage());
    }
}
