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
        List<String> command = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"),
                Path.of("stats.txt"), 17, 2);

        assertTrue(command.contains("jgi.BBDuk"));
        assertTrue(command.contains("ref=primers.fasta"));
        assertTrue(command.contains("k=17"));
        assertTrue(command.contains("hdist=2"));
        assertTrue(command.contains("in1=s_R1.fastq.gz"));
        assertTrue(command.contains("in2=s_R2.fastq.gz"));
        assertTrue(command.contains("outm=out.fq.gz"));
        assertTrue(command.contains("stats=stats.txt"));
        assertTrue(command.contains("overwrite=t"));
        assertTrue(command.contains("-Xmx6g"));
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

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-cp", "bbtools.jar", "assemble.Tadpole",
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
        assertEquals(dir.resolve("s_baitStats.txt"), BbtoolsCommands.baitStats(dir, "s"));
    }
}
