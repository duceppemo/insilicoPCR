package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.NearMiss;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.report.NoProductReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An assay without a product is said to be, with the rule that decided it. */
class NoProductTest {

    /** About 60 °C on a perfect match, as most primers. */
    private static final String PRIMER = "GAAGTCGGACTACCTGGTGA";
    /** About 20 °C: anneals nowhere at 60. */
    private static final String AT_RICH = "TTTATTAATATTTAATAT";
    private static final AmpliconOptions GATED = AmpliconOptions.builder().tmGate(TmGate.DEFAULT).build();

    private static PrimerPanel panel(String... idAndSequence) {
        Map<String, String> sequences = new LinkedHashMap<>();
        for (int i = 0; i < idAndSequence.length; i += 2) {
            sequences.put(idAndSequence[i], idAndSequence[i + 1]);
        }
        return PrimerPanel.of(sequences);
    }

    private static Sample sample(String name, Sample.FileType type) {
        Sample sample = new Sample(name);
        sample.setFileType(type);
        return sample;
    }

    private static BlastResult plus(String oligo, int at, String sequence) {
        return TestHits.plusHit(oligo, "c1", at, sequence, sequence);
    }

    private static BlastResult minus(String oligo, int at, String sequence) {
        return TestHits.minusHit(oligo, "c1", at, sequence, sequence);
    }

    private static List<NoProduct> of(PrimerPanel primers, Sample... samples) {
        Map<String, Sample> bySample = new LinkedHashMap<>();
        for (Sample sample : samples) {
            bySample.put(sample.getName(), sample);
        }
        return NoProduct.of(bySample, primers, GATED, Products.ofRun(bySample, primers, GATED, Map.of()));
    }

    @Test
    void anAssayWithAProductIsNotListed() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        sample.hits().add("a-R", minus("a-R", 400, PRIMER), 60);
        assertEquals(List.of(), of(panel("a-F", PRIMER, "a-R", PRIMER), sample));
    }

    @Test
    void anOligoWithoutAnAcceptedSiteIsNamedWithItsBestSiteAndTheRuleItFails() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        BlastResult weak = minus("a-R", 400, PRIMER);
        sample.hits().nearMiss("a-R", new NearMiss(minus("a-R", 900, PRIMER), 41.2, 0));
        sample.hits().nearMiss("a-R", new NearMiss(weak, 51.46, 0));
        sample.hits().nearMiss("a-R", new NearMiss(minus("a-R", 700, PRIMER), Double.NaN, 3));
        // A probe BLAST did not find at all.
        List<NoProduct> found = of(panel("a-F", PRIMER, "a-R", PRIMER, "a-P", PRIMER), sample);

        assertEquals(2, found.size(), found.toString());
        NoProduct reverse = found.getFirst();
        assertEquals(List.of("s", "a", "a-R", "c1:400-419(-)"), List.of(reverse.sample(), reverse.assay(),
                reverse.oligo(), reverse.site()));
        assertEquals("no site reaches 55 °C; the best melts at 51.5 °C", reverse.reason());
        assertEquals(51.46, reverse.siteTm());
        assertEquals(55.0, reverse.neededTm());
        NoProduct probe = found.get(1);
        assertEquals("a-P", probe.oligo());
        assertEquals("no site: BLAST found no match", probe.reason());
        assertEquals("", probe.site());
    }

    @Test
    void aSiteThatFailsTheThreePrimeRuleSaysByHowMuch() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        String mismatched = PRIMER.substring(0, 19) + "C";
        sample.hits().nearMiss("a-R", new NearMiss(minus("a-R", 800, PRIMER), Double.NaN, 6));
        sample.hits().nearMiss("a-R", new NearMiss(TestHits.minusHit("a-R", "c1", 400, mismatched, PRIMER), Double.NaN,
                3));
        // Equal penalties: the longer alignment.
        sample.hits().nearMiss("a-R", new NearMiss(minus("a-R", 600, PRIMER.substring(5)), Double.NaN, 3));
        NoProduct reverse = of(panel("a-F", PRIMER, "a-R", PRIMER), sample).getFirst();
        assertEquals("no site is accepted; the best fails the 3' rule, with a 3' penalty of 3, more than 0"
                + " (--3p-penalty) (1 mismatch or indel)", reverse.reason());
        assertTrue(Double.isNaN(reverse.siteTm()));
    }

    @Test
    void aRealSiteWithAThreePrimeMismatchOutranksAChanceMatch() {
        // A few bases matching by chance pass the 3' rule and melt at 10 °C: not the site to send the user to.
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        String mismatched = PRIMER.substring(0, 19) + "C";
        sample.hits().nearMiss("a-R", new NearMiss(minus("a-R", 900, PRIMER.substring(11)), 9.8, 0, 55));
        sample.hits().nearMiss("a-R", new NearMiss(TestHits.minusHit("a-R", "c1", 400, mismatched, PRIMER), Double.NaN,
                3, 55));
        NoProduct reverse = of(panel("a-F", PRIMER, "a-R", PRIMER), sample).getFirst();
        assertTrue(reverse.site().startsWith("c1:400-"), reverse.site());
        assertTrue(reverse.reason().startsWith("no site is accepted; the best fails the 3' rule"), reverse.reason());
        // Of two chance matches, or two real sites, the old order holds.
        assertTrue(new NearMiss(minus("a-R", 1, PRIMER), 50, 0, 55)
                .closerThan(new NearMiss(TestHits.minusHit("a-R", "c1", 400, mismatched, PRIMER), Double.NaN, 3, 55)));
        assertTrue(new NearMiss(minus("a-R", 1, PRIMER.substring(11)), 9.8, 0, 55)
                .closerThan(new NearMiss(TestHits.minusHit("a-R", "c1", 1, "AAAAAAAAAA" + PRIMER.substring(10), PRIMER),
                        Double.NaN, 9, 55)));
    }

    @Test
    void anOligoThatCannotReachTheMinimumOnAPerfectMatchIsSaidToBeUnlikelyToAnneal() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        NoProduct reverse = of(panel("a-F", PRIMER, "a-R", AT_RICH), sample).getFirst();
        assertTrue(reverse.reason().startsWith("no site reaches 55 °C, and its Tm on a perfect match is "),
                reverse.reason());
        assertTrue(reverse.reason().endsWith(" °C: it is unlikely to anneal at this temperature (lower the annealing"
                + " temperature, --anneal-temp or the assay's ta=, or raise --tm-margin)"), reverse.reason());
    }

    @Test
    void aDegenerateOligoIsUnlikelyToAnnealOnlyWhenEveryOneOfItsSequencesIs() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        // a-R_1 melts at 60 °C on a perfect match: it could anneal, BLAST found no site of either.
        NoProduct one = of(panel("a-F", PRIMER, "a-R_0", AT_RICH, "a-R_1", PRIMER), sample).getFirst();
        assertEquals("no site: BLAST found no match", one.reason());
        NoProduct both = of(panel("a-F", PRIMER, "a-R_0", AT_RICH, "a-R_1", AT_RICH.replace('A', 'T')), sample)
                .getFirst();
        assertTrue(both.reason().startsWith("no site reaches 55 °C, and its Tm on a perfect match is "),
                both.reason());
    }

    @Test
    void aProductOfOnePrimerAloneIsNotTheAssaysAndIsNoted() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        // a-F at an inverted repeat: a product of a-F alone; a-R anneals nowhere.
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        sample.hits().add("a-F", minus("a-F", 400, PRIMER), 60);
        PrimerPanel primers = panel("a-F", PRIMER, "a-R", "CCAGCAGATTCTTTTTTACATCA");
        Map<String, Sample> bySample = Map.of("s", sample);
        List<ReportRow> rows = Products.ofRun(bySample, primers, GATED, Map.of());
        assertEquals(List.of(ReportRow.Pairing.ONE_PRIMER), rows.stream().map(ReportRow::pairing).toList());
        NoProduct reverse = NoProduct.of(bySample, primers, GATED, rows).getFirst();
        assertEquals("a-R", reverse.oligo());
        assertEquals("no site: BLAST found no match; the report has a product of one of its primers alone",
                reverse.reason());
    }

    @Test
    void anAssayWhoseProductIsReportedUnderAnotherOfItsReactionSaysSo() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        for (String assay : List.of("a", "b")) {
            sample.hits().add(assay + "-F", plus(assay + "-F", 101, PRIMER), 60);
            sample.hits().add(assay + "-R", minus(assay + "-R", 400, PRIMER), 60);
        }
        Map<String, String> sequences = new LinkedHashMap<>();
        for (String id : List.of("a-F", "a-R", "b-F", "b-R")) {
            sequences.put(id, PRIMER);
        }
        PrimerPanel primers = PrimerPanel.of(sequences, ExpectedSizes.NONE,
                MixGroups.fromHeaders(Map.of("a-F", "mix=m", "b-F", "mix=m")));
        Map<String, Sample> bySample = Map.of("s", sample);
        List<ReportRow> rows = Products.ofRun(bySample, primers, GATED, Map.of());
        assertEquals(List.of("a"), rows.stream().map(ReportRow::assay).distinct().toList());
        List<NoProduct> found = NoProduct.of(bySample, primers, GATED, rows);
        assertEquals(1, found.size(), found.toString());
        assertEquals("b", found.getFirst().assay());
        assertEquals("its product is reported under a: the two assays share a reaction (mix=) and make it at the same"
                + " place", found.getFirst().reason());
        assertTrue(found.getFirst().reportedUnderAnother());
    }

    /** Assays a and b, every primer {@link #PRIMER}, in one reaction; a makes its product at 101-419. */
    private static PrimerPanel mixedWithA(Sample sample) {
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        sample.hits().add("a-R", minus("a-R", 400, PRIMER), 60);
        Map<String, String> sequences = new LinkedHashMap<>();
        for (String id : List.of("a-F", "a-R", "b-F", "b-R")) {
            sequences.put(id, PRIMER);
        }
        return PrimerPanel.of(sequences, ExpectedSizes.NONE,
                MixGroups.fromHeaders(Map.of("a-F", "mix=m", "b-F", "mix=m")));
    }

    @Test
    void aProductOfOnePrimerAloneAtAnotherAssaysPlaceIsNotSaidToBeReportedUnderIt() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        PrimerPanel primers = mixedWithA(sample);
        // b-F alone at a's place, an inverted repeat; b-R anneals nowhere.
        sample.hits().add("b-F", plus("b-F", 101, PRIMER), 60);
        sample.hits().add("b-F", minus("b-F", 400, PRIMER), 60);
        List<NoProduct> found = of(primers, sample);
        assertEquals(1, found.size(), found.toString());
        assertEquals(List.of("b", "b-R", "no site: BLAST found no match"),
                List.of(found.getFirst().assay(), found.getFirst().oligo(), found.getFirst().reason()));
        assertTrue(!found.getFirst().reportedUnderAnother());
    }

    @Test
    void anAssayWhoseDesignedProductIsReportedUnderAnotherSaysSoThoughItHasAProductOfOnePrimer() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        PrimerPanel primers = mixedWithA(sample);
        sample.hits().add("b-F", plus("b-F", 101, PRIMER), 60);
        sample.hits().add("b-R", minus("b-R", 400, PRIMER), 60);
        // b-F alone elsewhere, reported under b.
        sample.hits().add("b-F", minus("b-F", 2300, PRIMER), 60);
        Map<String, Sample> bySample = Map.of("s", sample);
        List<ReportRow> rows = Products.ofRun(bySample, primers, GATED, Map.of());
        assertTrue(rows.stream().anyMatch(row -> row.assay().equals("b")
                && row.pairing() == ReportRow.Pairing.ONE_PRIMER), rows.toString());
        List<NoProduct> found = NoProduct.of(bySample, primers, GATED, rows);
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.getFirst().reason().startsWith("its product is reported under a: "),
                found.getFirst().reason());
    }

    @Test
    void sitesTooCloseForTheMinimumLengthNameIt() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        sample.hits().add("a-R", minus("a-R", 400, PRIMER), 60);
        Map<String, Sample> bySample = Map.of("s", sample);
        PrimerPanel primers = panel("a-F", PRIMER, "a-R", PRIMER);
        AmpliconOptions longOnly = AmpliconOptions.builder().minLength(1000).tmGate(TmGate.DEFAULT).build();
        NoProduct none = NoProduct.of(bySample, primers, longOnly, Products.ofRun(bySample, primers, longOnly,
                Map.of())).getFirst();
        assertEquals("every oligo has a site, but no forward and reverse site face each other on one sequence 1000 to"
                + " 5000 bp apart (--min-length, --max-length)", none.reason());
    }

    @Test
    void sitesThatPairIntoNoProductAreSaidToAndAMiniAssemblyIsNamed() {
        // Both primers on the plus strand: no product, though each has a site.
        Sample reads = sample("r", Sample.FileType.FASTQ);
        reads.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        reads.hits().add("a-R", plus("a-R", 400, PRIMER), 60);
        NoProduct none = of(panel("a-F", PRIMER, "a-R", PRIMER), reads).getFirst();
        assertEquals("", none.oligo());
        assertEquals("every oligo has a site in the mini-assembly (the FASTQ diagnostics say what the reads show),"
                + " but no forward and reverse site face each other on one sequence within 5000 bp (--max-length)",
                none.reason());
    }

    @Test
    void ofADegenerateOligoTheVariantThatCameClosestIsNamed() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        sample.hits().nearMiss("a-R_0", new NearMiss(minus("a-R_0", 400, PRIMER), 30, 0));
        sample.hits().nearMiss("a-R_1", new NearMiss(minus("a-R_1", 500, PRIMER), 50, 0));
        assertEquals("a-R_1", of(panel("a-F", PRIMER, "a-R_0", PRIMER, "a-R_1", PRIMER), sample).getFirst().oligo());
    }

    @Test
    void theFileListsEveryRowBesideTheReport(@TempDir Path dir) throws IOException {
        NoProduct row = new NoProduct("s", "a", "a-R", "no site reaches 55 °C", "c1:1-20(+)", 51.46, 55);
        Path file = NoProductReport.write(dir.resolve("report(2).tsv"), List.of(row));
        assertEquals("report(2)_no_product.tsv", file.getFileName().toString());
        assertEquals(List.of(NoProductReport.HEADER, "s\ta\ta-R\tno site reaches 55 °C\tc1:1-20(+)\t51.5\t55.0"),
                Files.readAllLines(file));
    }

    @Test
    void aRejectedSitesTmNeverReadsAsTheMinimumItFailed() {
        assertEquals(49.9, NoProduct.below(49.979, 50), 1e-9);
        assertEquals(51.46, NoProduct.below(51.46, 55), 1e-9, "far below: as computed, rounded when shown");
        assertEquals(52.4, NoProduct.below(52.47, 52.5), 1e-9);
        assertTrue(Double.isNaN(NoProduct.below(Double.NaN, 50)));
    }
}
