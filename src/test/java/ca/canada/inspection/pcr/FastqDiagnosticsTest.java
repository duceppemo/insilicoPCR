package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqDiagnosticsTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);
    private static final Map<String, String> PRIMERS = Map.of(
            "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);
    private static final Map<String, Long> ALL_BAITED = Map.of("assay-F", 50L, "assay-R", 54L, "assay-P", 64L);

    @TempDir
    Path tempDir;

    private static Sample fastqSample() {
        Sample sample = new Sample();
        sample.setName("reads");
        sample.setFileType("fastq");
        return sample;
    }

    private static List<Finding> assess(Sample sample, Map<String, Long> baited, Map<String, Integer> lengths) {
        return FastqDiagnostics.assess(sample, PRIMERS, DEFAULTS, baited, lengths);
    }

    @Test
    void reportedAssayIsNotFlagged() {
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 281));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 200));

        assertTrue(assess(sample, ALL_BAITED, Map.of()).isEmpty());
    }

    @Test
    void assayWithoutBaitedReadsIsAnOrdinaryNegative() {
        assertTrue(assess(fastqSample(), Map.of(), Map.of()).isEmpty());
    }

    @Test
    void baitedButMissingFromAssemblyIsNotAssembled() {
        List<Finding> findings = assess(fastqSample(), ALL_BAITED, Map.of());

        assertEquals(1, findings.size());
        Finding finding = findings.getFirst();
        assertEquals(Status.NOT_ASSEMBLED, finding.status());
        assertEquals("assay", finding.assay());
        assertEquals(new FastqDiagnostics.BaitedReads(50, 54, 64), finding.baitedReads());
        assertTrue(finding.detail().contains("assay-F and assay-R"), finding.detail());
        assertTrue(finding.status().isWarning());
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

        List<Finding> findings = assess(sample, ALL_BAITED, Map.of(contig6, 1534, contig60, 439));

        assertEquals(1, findings.size());
        Finding finding = findings.getFirst();
        assertEquals(Status.SPLIT_ACROSS_CONTIGS, finding.status());
        assertTrue(finding.detail().contains("assay-F contig_6:1496-1515(+), 19 bp to contig end (contig 1534 bp)"),
                finding.detail());
        assertTrue(finding.detail().contains("assay-R contig_60:15-34(-), 14 bp to contig end (contig 439 bp)"),
                finding.detail());
    }

    @Test
    void primersOnOneContigWithoutValidAmpliconAreNoValidPair() {
        Sample sample = fastqSample();
        // Same strand: cannot amplify.
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0,len=900", 101));
        sample.addBlastResult("assay-R", TestHits.plusHit("assay-R", "contig_0,len=900", 400));

        List<Finding> findings = assess(sample, ALL_BAITED, Map.of());

        assertEquals(Status.NO_VALID_PAIR, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("contig_0"));
    }

    @Test
    void missingProbeOnValidPairIsNoValidPair() {
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 281));

        assertEquals(Status.NO_VALID_PAIR, assess(sample, ALL_BAITED, Map.of()).getFirst().status());
    }

    @Test
    void onlyOnePrimerBaitedIsPartialAndNotAWarning() {
        List<Finding> findings = assess(fastqSample(), Map.of("assay-F", 12L), Map.of());

        Finding finding = findings.getFirst();
        assertEquals(Status.PARTIAL_BAIT, finding.status());
        assertTrue(finding.detail().contains("reverse"));
        assertTrue(!finding.status().isWarning());
    }

    @Test
    void expandedDegenerateOligosAreSummedPerRole() {
        Map<String, String> primers = Map.of("assay-F_0", PRIMER_20, "assay-F_1", PRIMER_20, "assay-R", PRIMER_20);
        List<Finding> findings = FastqDiagnostics.assess(fastqSample(), primers, DEFAULTS,
                Map.of("assay-F_0", 3L, "assay-F_1", 4L, "assay-R", 5L), Map.of());

        assertEquals(new FastqDiagnostics.BaitedReads(7, 5, 0), findings.getFirst().baitedReads());
    }

    @Test
    void readsBbdukStatsAndContigLengths() throws IOException {
        Path stats = Files.writeString(tempDir.resolve("stats.txt"), """
                #File\tr1.fq.gz\tr2.fq.gz
                #Total\t1600
                #Matched\t168\t10.50000%
                #Name\tReads\tReadsPct
                assay-P\t64\t4.00000%
                assay-R\t54\t3.37500%
                assay-F\t50\t3.12500%
                """);
        assertEquals(ALL_BAITED, FastqDiagnosticsReport.readBaitStats(stats));

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
}
