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
    void mixedPcrAndQPcrAssaysAreReportedInOneRun() throws IOException {
        // "assay" has no probe in the primer file; "taq" is a qPCR assay with one.
        Sample sample = sampleWithHits();
        sample.addBlastResult("taq-F", TestHits.plusHit("taq-F", "contig1", 2101));
        sample.addBlastResult("taq-R", TestHits.minusHit("taq-R", "contig1", 2581));
        sample.addBlastResult("taq-P", TestHits.plusHit("taq-P", "contig1", 2300));
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sample));
        Map<String, String> primers = Map.of(
                "assay-F", PRIMER_20, "assay-R", PRIMER_20,
                "taq-F", PRIMER_20, "taq-R", PRIMER_20, "taq-P", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        List<String> lines = Files.readAllLines(report);

        assertEquals(3, lines.size(), "header + PCR row + qPCR row expected, got: " + lines);
        assertTrue(lines.get(0).endsWith("Probe\tProbeLocation\tProbeSize\tProbeMismatches"));

        String[] pcrRow = lines.get(1).split("\t", -1);
        assertEquals("assay", pcrRow[1]);
        assertEquals(17, pcrRow.length, "probe columns must be padded for probe-less assays");
        assertEquals("", pcrRow[13]);

        String[] qpcrRow = lines.get(2).split("\t", -1);
        assertEquals("taq", qpcrRow[1]);
        assertEquals("taq-P", qpcrRow[13]);
        assertEquals("2300-2319", qpcrRow[14]);
    }

    @Test
    void assayWithDefinedProbeStaysNegativeWhenProbeDoesNotHit() throws IOException {
        // The primer file defines assay-P, but no probe hit was found: the primer
        // pair alone must NOT produce a row for this assay.
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sampleWithHits()));
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
    @Test
    void degeneratePrimerVariantsPairAcrossSuffixes() throws IOException {
        Sample sample = new Sample();
        sample.setName("sampleA");
        sample.setFileType("fasta");
        sample.addBlastResult("assay-F_0", TestHits.plusHit("assay-F_0", "contig1", 101));
        sample.addBlastResult("assay-R_1", TestHits.minusHit("assay-R_1", "contig1", 581));
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sample));
        Map<String, String> primers = Map.of("assay-F_0", PRIMER_20, "assay-R_1", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        List<String> lines = Files.readAllLines(report);
        assertEquals(2, lines.size());
        String[] row = lines.get(1).split("\t", -1);
        assertEquals("assay", row[1]);
        assertEquals("assay-F_0", row[6]);
        assertEquals("assay-R_1", row[7]);
    }

    @Test
    void nonConformingHitIdsAreSkippedInsteadOfAborting() throws IOException {
        Sample sample = sampleWithHits();
        sample.addBlastResult("plainname", TestHits.plusHit("plainname", "contig1", 900));
        sample.addBlastResult("odd-X", TestHits.plusHit("odd-X", "contig1", 950));
        Map<String, Sample> samples = new HashMap<>(Map.of("sampleA", sample));
        Map<String, String> primers = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        assertEquals(2, Files.readAllLines(report).size(), "only the valid assay row is expected");
    }

    @Test
    void samplesWithoutHitsProduceNoRows() throws IOException {
        Sample silent = new Sample();
        silent.setName("silent");
        silent.setFileType("fasta");
        Map<String, Sample> samples = new HashMap<>(Map.of("silent", silent, "sampleA", sampleWithHits()));
        Map<String, String> primers = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);

        Path report = ConsolidatedReport.write(tempDir, samples, primers, DEFAULTS);
        List<String> lines = Files.readAllLines(report);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("sampleA\t"));
    }

}
