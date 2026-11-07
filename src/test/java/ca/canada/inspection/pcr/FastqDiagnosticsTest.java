package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.FastqDiagnostics.Assembly;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.ReadsWithOligo;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqDiagnosticsTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);
    private static final Map<String, String> PRIMERS = Map.of(
            "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);
    private static final Map<String, Long> ALL_IN_READS = Map.of("assay-F", 50L, "assay-R", 54L, "assay-P", 64L);

    @TempDir
    Path tempDir;

    private static Sample fastqSample() {
        Sample sample = new Sample();
        sample.setName("reads");
        sample.setFileType("fastq");
        return sample;
    }

    /**
     * Evidence with oligos in reads (that many distinct reads per oligo, no read shared
     * between oligos), no read-level call, reads able to hold amplicons up to 240 bp.
     */
    private static ReadEvidence noReadCall(Map<String, Long> readsPerOligo) {
        Map<String, Set<String>> ids = new HashMap<>();
        readsPerOligo.forEach((oligo, count) -> ids.put(oligo,
                IntStream.range(0, count.intValue()).mapToObj(i -> oligo + "#" + i).collect(Collectors.toSet())));
        return new ReadEvidence(ids, Map.of(), Map.of("assay", 240));
    }

    private static List<Finding> assess(Sample sample, ReadEvidence evidence, Map<String, Integer> lengths) {
        return FastqDiagnostics.assess(sample, PRIMERS, DEFAULTS, evidence, lengths);
    }

    @Test
    void assayReportedOnTheAssemblyIsNotListed() {
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 281));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 200));

        assertTrue(assess(sample, noReadCall(ALL_IN_READS), Map.of()).isEmpty());
    }

    @Test
    void assayWithoutOligosInReadsIsAnOrdinaryNegative() {
        assertTrue(assess(fastqSample(), noReadCall(Map.of()), Map.of()).isEmpty());
    }

    private static ReadEvidence readsWithAmplicon(int count) {
        Sample reads = readsSample();
        Map<String, Integer> lengths = new java.util.HashMap<>();
        for (int i = 1; i <= count; i++) {
            String read = "read_" + i;
            lengths.put(read, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", read, 35));
        }
        return ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS);
    }

    @Test
    void readLevelCallIsCalledFromReads() {
        Finding finding = assess(fastqSample(), readsWithAmplicon(2), Map.of()).getFirst();

        assertEquals(Status.CALLED_FROM_READS, finding.status());
        assertEquals(Assembly.NOT_ASSEMBLED, finding.assembly());
        assertEquals(new ReadsWithOligo(2, 2, 2), finding.readsWithOligo());
        assertTrue(finding.readDetail().contains("2 of 2 informative read(s) (100%), size(s) 70 bp"),
                finding.readDetail());
    }

    @Test
    void readLevelCallBelowSupportThresholdIsWeak() {
        Finding finding = assess(fastqSample(), readsWithAmplicon(1), Map.of()).getFirst();

        assertEquals(Status.WEAK_READ_SUPPORT, finding.status());
        assertTrue(finding.readDetail().contains("needs at least 2 reads"), finding.readDetail());
    }

    @Test
    void primersInReadsWithoutCompleteAmpliconAreNotInReads() {
        Finding finding = assess(fastqSample(), noReadCall(ALL_IN_READS), Map.of()).getFirst();

        assertEquals(Status.NOT_IN_READS, finding.status());
        assertEquals(Assembly.NOT_ASSEMBLED, finding.assembly());
        assertEquals(240, finding.checkableLength());
        assertTrue(finding.readDetail().contains("up to 240 bp"), finding.readDetail());
        assertTrue(finding.assemblyDetail().contains("assay-F and assay-R"), finding.assemblyDetail());
    }

    @Test
    void primersOnDifferentContigsAreSplitWithDistanceToContigEnd() {
        // Mirrors ERR2938678 in #7: forward primer 19 bp from the end of contig_6,
        // reverse primer 14 bp into contig_60.
        String contig6 = "contig_6,len=1534,cov=26.3";
        String contig60 = "contig_60,len=439,cov=53.2";
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", contig6, 1496));    // ends at 1515
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", contig60, 15));    // starts at 15

        Finding finding = assess(sample, noReadCall(ALL_IN_READS), Map.of(contig6, 1534, contig60, 439)).getFirst();

        assertEquals(Assembly.SPLIT_ACROSS_CONTIGS, finding.assembly());
        assertTrue(finding.assemblyDetail().contains(
                "assay-F contig_6:1496-1515(+), 19 bp to contig end (contig 1534 bp)"), finding.assemblyDetail());
        assertTrue(finding.assemblyDetail().contains(
                "assay-R contig_60:15-34(-), 14 bp to contig end (contig 439 bp)"), finding.assemblyDetail());
    }

    @Test
    void primersOnOneContigWithoutValidAmpliconAreNoValidPair() {
        Sample sample = fastqSample();
        // Same strand: cannot amplify.
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0,len=900", 101));
        sample.addBlastResult("assay-R", TestHits.plusHit("assay-R", "contig_0,len=900", 400));

        Finding finding = assess(sample, noReadCall(ALL_IN_READS), Map.of()).getFirst();

        assertEquals(Assembly.NO_VALID_PAIR, finding.assembly());
        assertTrue(finding.assemblyDetail().contains("contig_0"));
    }

    @Test
    void onlyOnePrimerInReadsIsPartialBait() {
        Finding finding = assess(fastqSample(), noReadCall(Map.of("assay-F", 12L)), Map.of()).getFirst();

        assertEquals(Status.PARTIAL_BAIT, finding.status());
        assertTrue(finding.readDetail().contains("reverse"));
    }

    @Test
    void expandedDegenerateOligosCountEachReadOnce() {
        // read_2 matches both expansions of the degenerate forward primer: it is one read.
        Map<String, String> primers = Map.of("assay-F_0", PRIMER_20, "assay-F_1", PRIMER_20, "assay-R", PRIMER_20);
        ReadEvidence evidence = new ReadEvidence(Map.of(
                "assay-F_0", Set.of("read_1", "read_2"),
                "assay-F_1", Set.of("read_2", "read_3"),
                "assay-R", Set.of("read_4")), Map.of(), Map.of("assay", 240));
        List<Finding> findings = FastqDiagnostics.assess(fastqSample(), primers, DEFAULTS, evidence, Map.of());

        assertEquals(new ReadsWithOligo(3, 1, 0), findings.getFirst().readsWithOligo());
    }

    @Test
    void readsContigLengths() throws IOException {
        Path fasta = Files.writeString(tempDir.resolve("asm.fasta"),
                ">contig_0,len=12,cov=3.0\nACGTACGT\nACGT\n>contig_1 desc\nAC\n");
        assertEquals(Map.of("contig_0,len=12,cov=3.0", 12, "contig_1", 2), FastqDiagnosticsReport.contigLengths(fasta));
    }

    @Test
    void writesHeaderEvenWithoutFindings() throws IOException {
        Path file = tempDir.resolve("diag.tsv");
        FastqDiagnosticsReport.write(file, List.of());
        assertEquals(List.of(FastqDiagnosticsReport.HEADER), Files.readAllLines(file));
    }

    static Sample readsSample() {
        Sample reads = new Sample();
        reads.setName("reads");
        reads.setFileType("reads");
        return reads;
    }
}
