package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

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
        List<String> command = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"), 17, 2);

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
    }

    @Test
    void secondBaitIsExactRegardlessOfMismatchBudget() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), paired, Path.of("second.fq.gz"));

        assertTrue(command.contains("ref=first.fq.gz"));
        assertTrue(command.contains("hdist=0"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("k=")));
    }

    @Test
    void singleEndReadsUseInOption() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), List.of(Path.of("s.fastq.gz")),
                Path.of("second.fq.gz"));

        assertTrue(command.contains("in=s.fastq.gz"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("in1=")));
    }

    @Test
    void assemblyRunsTadpoleWithOverwriteAndGivenSettings() {
        List<String> command = commands.assemble(Path.of("second.fq.gz"), Path.of("asm.fasta"),
                new TadpoleOptions(25, 2, 1, 80));

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
                () -> commands.secondBait(Path.of("first.fq.gz"), three, Path.of("second.fq.gz")));
    }

    @Test
    void sampleFileNamesFollowTheDetailedReportLayout() {
        Path dir = Path.of("detailed_report", "s");
        assertEquals(dir.resolve("s_targetMatches.fastq.gz"), BbtoolsCommands.targetMatches(dir, "s"));
        assertEquals(dir.resolve("s_doubleTargetMatches.fastq.gz"), BbtoolsCommands.doubleTargetMatches(dir, "s"));
        assertEquals(dir.resolve("s_assembly.fasta"), BbtoolsCommands.assembly(dir, "s"));
        assertEquals(dir.resolve("s_merged.fastq.gz"), BbtoolsCommands.mergedReads(dir, "s"));
        assertEquals(dir.resolve("s_unmerged.fastq.gz"), BbtoolsCommands.unmergedReads(dir, "s"));
        assertEquals(dir.resolve("s_baitedReads.fasta"), BbtoolsCommands.baitedReadsFasta(dir, "s"));
        // Must not end in .tsv, or it would be parsed as a sample's contig hits.
        assertEquals(dir.resolve("s_baitedReads.blast.txt"), BbtoolsCommands.baitedReadsHits(dir, "s"));
        assertEquals(dir.resolve("s_extensionSeeds.fasta"), BbtoolsCommands.extensionSeeds(dir, "s"));
        assertEquals(dir.resolve("s_extendedReads.fasta"), BbtoolsCommands.extendedReads(dir, "s"));
        assertEquals(dir.resolve("s_extendedReads.blast.txt"), BbtoolsCommands.extendedReadsHits(dir, "s"));
        assertEquals(dir.resolve("s_extensionCandidates.fasta"), BbtoolsCommands.extensionCandidates(dir, "s"));
        assertEquals(dir.resolve("s_extensionCandidateReads.fastq.gz"), BbtoolsCommands.candidateReads(dir, "s"));
    }

    @Test
    void readsOfCandidateProductsAreFetchedFromTheInputWithTheirMates() {
        List<String> command = commands.recruit(Path.of("candidates.fasta"), paired, Path.of("reads.fq.gz"), 31);

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
                Path.of("extended.fasta"), 31, 2000);

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp", "bbtools.jar", "assemble.Tadpole",
                "in=seeds.fasta", "out=extended.fasta", "mode=extend", "el=2000", "er=2000", "extra=second.fq.gz",
                "k=31", "branchmult1=1", "branchmult2=1", "mincountextend=2", "overwrite=t", "threads=4"), command);
    }

    @Test
    void mergeKeepsUnmergedMates() {
        List<String> command = commands.mergePairs(Path.of("first.fq.gz"), Path.of("m.fq.gz"), Path.of("u.fq.gz"));

        assertTrue(command.contains("jgi.BBMerge"));
        assertTrue(command.contains("in=first.fq.gz"));
        assertTrue(command.contains("interleaved=t"));
        assertTrue(command.contains("out=m.fq.gz"));
        assertTrue(command.contains("outu=u.fq.gz"));
    }
}
