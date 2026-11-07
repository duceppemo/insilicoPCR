package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadLevelStageTest {

    @TempDir
    Path dir;

    @Test
    void singleEndSampleIgnoresMergeOutputLeftByAnEarlierPairedRun() throws IOException {
        // A reused output folder: an earlier paired run of sample S left merge output.
        Files.writeString(BbtoolsCommands.mergedReads(dir, "S"), "@old\nACGT\n+\nIIII\n");
        Files.writeString(BbtoolsCommands.unmergedReads(dir, "S"), "");

        assertEquals(List.of(BbtoolsCommands.targetMatches(dir, "S")), ReadLevelStage.readInputs(dir, "S", false));
        assertEquals(List.of(BbtoolsCommands.mergedReads(dir, "S"), BbtoolsCommands.unmergedReads(dir, "S")),
                ReadLevelStage.readInputs(dir, "S", true));
    }

    @Test
    void unmergedMatesShareAFragmentAndMergedReadsStandAlone() throws IOException {
        Path merged = Files.writeString(dir.resolve("m.fq"), "@m1\nAAAA\n+\nIIII\n");
        Path unmerged = Files.writeString(dir.resolve("u.fq"),
                "@p1/1\nCCCC\n+\nIIII\n@p1/2\nGGGG\n+\nIIII\n@p2/1\nTTTT\n+\nIIII\n@p2/2\nACGT\n+\nIIII\n");
        Path fasta = dir.resolve("reads.fasta");

        ReadLevelStage.ReadSet reads = ReadLevelStage.writeReadsFasta(List.of(merged, unmerged), true, fasta);

        assertEquals(5, reads.lengths().size());
        assertEquals("m1", reads.descriptions().get("read_0"));
        assertEquals(Map.of("read_1", "read_1", "read_2", "read_1", "read_3", "read_3", "read_4", "read_3"),
                reads.fragments());
        List<String> lines = Files.readAllLines(fasta);
        assertEquals(">read_2 p1/2", lines.get(4));
    }

    @Test
    void singleEndReadsAreTheirOwnFragments() throws IOException {
        Path reads = Files.writeString(dir.resolve("s.fq"), "@a\nAAAA\n+\nIIII\n@b\nCCCC\n+\nIIII\n");

        ReadLevelStage.ReadSet set = ReadLevelStage.writeReadsFasta(List.of(reads), false, dir.resolve("s.fasta"));

        assertTrue(set.fragments().isEmpty());
        assertEquals(2, set.lengths().size());
    }
}
