package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void readSequencesReturnsOnlyTheReadsAskedFor() throws IOException {
        Path fasta = Files.writeString(dir.resolve("reads.fasta"),
                ">read_0 a/1\nAAAA\n>read_1 a/2\nCCCC\n>read_2\nGGGG\n");

        assertEquals(Map.of("read_0", "AAAA", "read_2", "GGGG"),
                ReadLevelStage.readSequences(fasta, Set.of("read_0", "read_2", "read_9")));
    }

    @Test
    void callFromHitsSetsAsideReadsCarryingThePrimerElsewhere() throws IOException {
        // Reads 0 and 1 hold a 70 bp amplicon. Reads 2 to 4 carry the forward primer in
        // an unrelated place: they would have diluted the call to 2 of 5 reads before.
        String primer = "ACGTACGTACGTACGTACGT";
        String target = "GATTACAGGCTTAACCGGTTAAGCCATGCA";
        String elsewhere = "TTTTCCCCAAAAGGGGTTTTCCCCAAAAGG";
        StringBuilder fastq = new StringBuilder();
        StringBuilder hits = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            String sequence = "A".repeat(10) + primer + (i < 2 ? target : elsewhere) + primer + "C".repeat(21);
            fastq.append("@r").append(i).append('\n').append(sequence).append("\n+\n")
                    .append("I".repeat(sequence.length())).append('\n');
            hits.append(hit("read_" + i, "assay-F", 11, 30, 1, 20, primer));
            if (i < 2) {
                hits.append(hit("read_" + i, "assay-R", 61, 80, 20, 1, primer));
            }
        }
        Path reads = Files.writeString(dir.resolve("s.fq"), fastq.toString());
        Path fasta = dir.resolve("s.fasta");
        Path hitsFile = Files.writeString(dir.resolve("s.blast.txt"), BlastTsv.HEADER + "\n" + hits);
        ReadLevelStage.ReadSet set = ReadLevelStage.writeReadsFasta(List.of(reads), false, fasta);

        ReadCall call = ReadLevelStage.callFromHits("S", hitsFile, fasta, set,
                Map.of("assay-F", primer, "assay-R", primer), AmpliconOptions.withMismatches(0), 0.3)
                .products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(2, call.informativeReads());
        assertEquals(3, call.elsewhereReads());
        assertTrue(call.isReportable());
        assertEquals("r0", call.row().get(5), "the read's original name is the description");
    }

    /** One BLAST TSV line: a perfect full-length primer hit at read positions {@code qstart-qend}. */
    private static String hit(String read, String primerId, int qstart, int qend, int sstart, int send,
                              String primer) {
        return String.join("\t", read, primerId, "20", "0", "0", "0.001", "40.1", "20", "20",
                String.valueOf(qstart), String.valueOf(qend), primer, String.valueOf(sstart), String.valueOf(send),
                primer) + "\n";
    }
}
