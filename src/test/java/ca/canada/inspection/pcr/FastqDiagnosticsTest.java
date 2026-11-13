package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.FastqDiagnostics.Assembly;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.ReadsWithOligo;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
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
    void productTheAssemblyReportsAtAnotherSizeOnlyIsStillCalledFromReads() {
        // The assembly holds the assay only where both primers land elsewhere (181 bp);
        // the reads hold the 70 bp product. Both belong in the report.
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 262));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 200));
        ReadEvidence evidence = readsWithAmplicon(3);

        Finding finding = assess(sample, evidence, Map.of()).getFirst();

        assertEquals(Status.CALLED_FROM_READS, finding.status());
        assertEquals(Assembly.OTHER_PRODUCT_ONLY, finding.assembly());
        assertEquals("reports 181 bp only", finding.assemblyDetail());
        assertTrue(finding.readDetail().startsWith("complete amplicon in 3 of 3 informative read(s) (100%), size(s) 70 bp"),
                finding.readDetail());
        assertEquals(1, FastqDiagnostics.missingFromAssembly(evidence.products("assay"), Set.of(181)).size());
    }

    @Test
    void productTheAssemblyAlreadyReportsIsNotRepeated() {
        // 72 bp on a contig, 70 bp in reads: one product, give or take a length variant.
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 153));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 125));
        ReadEvidence evidence = readsWithAmplicon(3);

        assertTrue(assess(sample, evidence, Map.of()).isEmpty());
        assertTrue(FastqDiagnostics.missingFromAssembly(evidence.products("assay"), Set.of(72)).isEmpty());
    }

    @Test
    void weakProductIsNotAddedToAnAssayTheAssemblyReports() {
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 262));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 200));

        assertTrue(assess(sample, readsWithAmplicon(1), Map.of()).isEmpty(), "one read is not a call");
    }

    @Test
    void detailListsEachProductAndTheReadsCarryingAPrimerElsewhere() {
        ReadCall weak = new ReadCall("assay", 16, 63, 554, List.of(), Set.of(100), 0.3);
        ReadEvidence evidence = new ReadEvidence(Map.of("assay-F", Set.of("read_1"), "assay-R", Set.of("read_2")),
                Map.of("assay", List.of(weak)), Map.of("assay", 240));

        Finding finding = assess(fastqSample(), evidence, Map.of()).getFirst();

        assertEquals(Status.WEAK_READ_SUPPORT, finding.status());
        assertEquals("complete amplicon in 16 of 63 informative read(s) (25%), size(s) 100 bp;"
                + " a read-level call needs at least 2 reads or read pairs and 30% of informative reads;"
                + " 554 more read(s) carry a primer elsewhere in the genome", finding.readDetail());

        // A reportable product comes first; a weak one is listed after it.
        ReadCall strong = new ReadCall("assay", 1553, 1799, 0, List.of(), Set.of(258, 266, 267), 0.3);
        ReadCall offTarget = new ReadCall("assay", 3, 3924, 0, List.of(), Set.of(119), 0.3);
        evidence = new ReadEvidence(Map.of("assay-F", Set.of("read_1"), "assay-R", Set.of("read_2")),
                Map.of("assay", List.of(offTarget, strong)), Map.of("assay", 240));

        finding = assess(fastqSample(), evidence, Map.of()).getFirst();

        assertEquals(Status.CALLED_FROM_READS, finding.status());
        assertEquals("complete amplicon in 1553 of 1799 informative read(s) (86%), size(s) 258,266,267 bp;"
                + " also complete amplicon in 3 of 3924 informative read(s) (0%), size(s) 119 bp, not reported",
                finding.readDetail());
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

    private static final PairSupport.Verdict PAIRS_SUPPORT_IT = new PairSupport.Verdict(PairSupport.Kind.SUPPORTED,
            true, 300, 9, 191, 14, 0, 0, 0, 0, 40);

    /** What extending reads for {@code assay} showed: products as {size, forward-closing, reverse-closing}. */
    private static ReadExtensionCaller.Outcome extensionOutcome(int[]... products) {
        List<ReadExtensionCaller.Product> list = new java.util.ArrayList<>();
        for (int[] product : products) {
            list.add(new ReadExtensionCaller.Product("assay", product[0], Set.of(product[0]), product[1], 150,
                    product[2], 150, List.of(), PAIRS_SUPPORT_IT));
        }
        return new ReadExtensionCaller.Outcome("assay", 150, 150, 2000, list);
    }

    @Test
    void productClosedByExtendedReadsIsCalledByExtension() {
        Finding finding = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", extensionOutcome(new int[]{1074, 142, 145}, new int[]{1303, 0, 1}))).getFirst();

        assertEquals(Status.CALLED_BY_EXTENSION, finding.status());
        assertEquals(Assembly.NOT_ASSEMBLED, finding.assembly());
        assertEquals("no complete amplicon in the reads, which could hold amplicons up to 240 bp; reads extended"
                + " through the baited reads hold a product of 1074 bp (142 of 150 forward-seeded and 145 of 150"
                + " reverse-seeded reads); too few to report: 1303 bp in 0 of 150 forward-seeded and 1 of 150"
                + " reverse-seeded reads", finding.readDetail());
        assertTrue(finding.message().contains("called by extending reads"), finding.message());
    }

    @Test
    void primersTheExtensionDidNotJoinAreNotClosedByExtension() {
        Finding none = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", extensionOutcome())).getFirst();

        assertEquals(Status.NOT_CLOSED_BY_EXTENSION, none.status());
        assertEquals("no complete amplicon in the reads, which could hold amplicons up to 240 bp; extending 150"
                + " forward-seeded and 150 reverse-seeded read(s) through the baited reads, by up to 2000 bases each"
                + " way, gave no product held by at least 2 extended reads seeded from each primer",
                none.readDetail());

        // Closed from one side only: still a negative, and the detail says what was seen.
        Finding oneSided = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS),
                Map.of(), Map.of("assay", extensionOutcome(new int[]{447, 0, 2}))).getFirst();
        assertEquals(Status.NOT_CLOSED_BY_EXTENSION, oneSided.status());
        assertTrue(oneSided.readDetail().endsWith("; too few to report: 447 bp in 0 of 150 forward-seeded and 2 of 150"
                + " reverse-seeded reads"), oneSided.readDetail());
    }

    @Test
    void productAddedByExtensionToAnAssayTheAssemblyReportsElsewhereIsListed() {
        // Target mode: the assembly reports 181 bp; the extended reads add a 1074 bp product.
        Sample sample = fastqSample();
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig_0", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig_0", 262));
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig_0", 200));
        ReadExtensionCaller.Product found = new ReadExtensionCaller.Product("assay", 1074, Set.of(1074), 12, 150,
                9, 150, List.of(), PAIRS_SUPPORT_IT);
        ReadExtensionCaller.Product again = new ReadExtensionCaller.Product("assay", 183, Set.of(183), 40, 150,
                38, 150, List.of(), PAIRS_SUPPORT_IT);

        Finding finding = FastqDiagnostics.assess(sample, PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", new ReadExtensionCaller.Outcome("assay", 150, 150, 2000, List.of(again, found),
                        Set.of(181)))).getFirst();

        assertEquals(Status.CALLED_BY_EXTENSION, finding.status());
        assertEquals(Assembly.OTHER_PRODUCT_ONLY, finding.assembly());
        assertEquals("reports 181 bp only", finding.assemblyDetail());
        assertEquals("reads extended through the baited reads hold a product of 1074 bp (12 of 150 forward-seeded and"
                + " 9 of 150 reverse-seeded reads)", finding.readDetail());

        // Nothing new from the extension: the assay is reported already, and nothing needs explaining.
        assertTrue(FastqDiagnostics.assess(sample, PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", new ReadExtensionCaller.Outcome("assay", 150, 150, 2000, List.of(again),
                        Set.of(181)))).isEmpty());
    }

    @Test
    void productTheReadPairsDoNotSupportIsWithheldWithTheReason() {
        // A wrong turn through a repeat: closed from both sides, but no read pair spans one window.
        ReadExtensionCaller.Product wrongTurn = new ReadExtensionCaller.Product("assay", 727, Set.of(727), 44, 52,
                37, 46, List.of(), new PairSupport.Verdict(PairSupport.Kind.UNSUPPORTED_WINDOW, true, 300, 1, 191, 14,
                0, 0, 0, 0, 30));
        Finding finding = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", new ReadExtensionCaller.Outcome("assay", 52, 46, 2000, List.of(wrongTurn)))).getFirst();

        assertEquals(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, finding.status());
        assertEquals("no complete amplicon in the reads, which could hold amplicons up to 240 bp; extended reads hold"
                + " a product of 727 bp (44 of 52 forward-seeded and 37 of 46 reverse-seeded reads), not reported:"
                + " the 300-base window at 191-490 is held by 1 read pair, against 14 for the median window",
                finding.readDetail());
        assertTrue(finding.message().contains("read pairs do not support the product; not reported"));

        // Too few pairs to tell is the same status, with its own reason.
        ReadExtensionCaller.Product thin = wrongTurn.withSupport(new PairSupport.Verdict(
                PairSupport.Kind.TOO_FEW_PAIRS, true, 100, 0, 0, 2, 0, 0, 0, 0, 5));
        Finding tooFew = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", new ReadExtensionCaller.Outcome("assay", 52, 46, 2000, List.of(thin)))).getFirst();
        assertEquals(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, tooFew.status());
        assertTrue(tooFew.readDetail().endsWith("not reported: too few read pairs to verify it: the median 100-base"
                + " window is held by 2, 10 needed"), tooFew.readDetail());
    }

    @Test
    void productThroughARepeatLongerThanTheReadPairsIsWithheldAsUnresolved() {
        ReadExtensionCaller.Product through = new ReadExtensionCaller.Product("assay", 1047, Set.of(1047), 40, 50,
                38, 50, List.of(), new PairSupport.Verdict(PairSupport.Kind.UNRESOLVED_REPEAT, true, 250, 5, 310, 10,
                228, 827, 16, 10, 50));
        Finding finding = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("assay", new ReadExtensionCaller.Outcome("assay", 50, 50, 2000, List.of(through)))).getFirst();

        assertEquals(Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT, finding.status());
        assertTrue(finding.readDetail().endsWith("extended reads hold a product of 1047 bp (40 of 50 forward-seeded and"
                + " 38 of 50 reverse-seeded reads), not reported: bases 228-827 are a repeat with another copy in the"
                + " sample (16 reads of that copy enter the product there and 10 leave it), longer than the 250-base"
                + " window the read pairs can verify: a real product and a wrong turn look the same there"),
                finding.readDetail());
    }

    @Test
    void withoutAnExtensionOutcomeTheStatusAndWordingAreUnchanged() {
        // The extension was off, or ran for other assays only.
        Finding finding = FastqDiagnostics.assess(fastqSample(), PRIMERS, DEFAULTS, noReadCall(ALL_IN_READS), Map.of(),
                Map.of("other", extensionOutcome(new int[]{1074, 5, 5}))).getFirst();

        assertEquals(Status.NOT_IN_READS, finding.status());
        assertEquals("no complete amplicon in the reads; reads carrying the primers could hold amplicons up to 240 bp,"
                + " so longer amplicons cannot be ruled out", finding.readDetail());
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
