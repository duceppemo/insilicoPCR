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
        assertEquals("every site fails the 3' rule; the best has a 3' penalty of 3, more than 0 (--3p-penalty)"
                + " (1 mismatch or indel)", reverse.reason());
        assertTrue(Double.isNaN(reverse.siteTm()));
    }

    @Test
    void anOligoThatCannotReachTheMinimumOnAPerfectMatchIsSaidToAnnealNowhere() {
        Sample sample = sample("s", Sample.FileType.FASTA);
        sample.hits().add("a-F", plus("a-F", 101, PRIMER), 60);
        NoProduct reverse = of(panel("a-F", PRIMER, "a-R", AT_RICH), sample).getFirst();
        assertTrue(reverse.reason().startsWith("its Tm on a perfect match is "), reverse.reason());
        assertTrue(reverse.reason().endsWith(" °C, below the 55 °C a site needs: it anneals nowhere at this"
                + " temperature (lower the annealing temperature, --anneal-temp or the assay's ta=, or raise"
                + " --tm-margin)"), reverse.reason());
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
