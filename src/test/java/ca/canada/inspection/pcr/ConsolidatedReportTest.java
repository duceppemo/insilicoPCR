package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsolidatedReportTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);

    @TempDir
    Path tempDir;

    private static Sample sampleWithHits() {
        Sample sample = new Sample();
        sample.setName("sampleA");
        sample.setFileType("fasta");
        sample.addContig("contig1", "chromosome");
        // Productive pair: 500 bp product.
        sample.addBlastResult("assay-F", TestHits.plusHit("assay-F", "contig1", 101));
        sample.addBlastResult("assay-R", TestHits.minusHit("assay-R", "contig1", 581));
        // Decoy reverse hit on the same strand as the forward primer: must not be reported.
        sample.addBlastResult("assay-R", TestHits.plusHit("assay-R", "contig1", 5000));
        return sample;
    }

    @Test
    void writesOneRowPerProductivePair() throws IOException {
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sampleWithHits()));
        Map<String, String> primers = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        List<String> lines = Files.readAllLines(report);

        assertEquals(2, lines.size(), "header + one amplicon row expected, got: " + lines);
        assertTrue(lines.get(0).startsWith("Sample\tGene\tGenomeLocation\tAmpliconSize"));
        assertTrue(lines.get(0).endsWith("Forward3PrimeMismatches\tReverse3PrimeMismatches\tStrand"));

        String[] row = lines.get(1).split("\t", -1);
        assertEquals("sampleA", row[0]);
        assertEquals("assay", row[1]);
        assertEquals("101-600", row[2]);
        assertEquals("500", row[3]);
        assertEquals("contig1", row[4]);
        assertEquals("chromosome", row[5]);
        assertEquals("+", row[12]);
    }

    @Test
    void qPcrModeRequiresProbeInsideAmplicon() throws IOException {
        Sample sample = sampleWithHits();
        // Probe inside the amplicon.
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig1", 300));
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sample));
        Map<String, String> primers = Map.of(
                "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        List<String> lines = Files.readAllLines(report);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("Probe\tProbeLocation\tProbeSize\tProbeMismatches"));
        String[] row = lines.get(1).split("\t", -1);
        assertEquals("assay-P", row[13]);
        assertEquals("300-319", row[14]);
    }

    @Test
    void qPcrModeWritesNothingWhenProbeIsOutsideAmplicon() throws IOException {
        Sample sample = sampleWithHits();
        sample.addBlastResult("assay-P", TestHits.plusHit("assay-P", "contig1", 900)); // outside 101-600
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sample));
        Map<String, String> primers = Map.of(
                "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        assertEquals(1, Files.readAllLines(report).size(), "only the header is expected");
    }

    @Test
    void existingReportIsNotOverwritten() throws IOException {
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sampleWithHits()));
        Map<String, String> primers = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);

        Path first = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        Path second = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);

        assertEquals("report.tsv", first.getFileName().toString());
        assertEquals("report(1).tsv", second.getFileName().toString());
    }
}
