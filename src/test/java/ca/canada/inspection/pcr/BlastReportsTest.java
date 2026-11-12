package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastReportsTest {

    private static final Map<String, String> PRIMERS = Map.of("assay-F", PRIMER_20);
    private static final AmpliconOptions OPTIONS = AmpliconOptions.withMismatches(0);

    @TempDir
    Path detailedDir;

    private static String hitLine(int mismatches) {
        return String.join("\t",
                "contig1", "assay-F", "20", String.valueOf(mismatches), "0", "1e-5", "40.1",
                "20", "20", "101", "120", PRIMER_20, "1", "20", PRIMER_20);
    }

    private Sample sample(String name) {
        Sample sample = new Sample();
        sample.setName(name);
        sample.setFileType("fasta");
        return sample;
    }

    @Test
    void attachesAcceptedHitsFromNestedSampleDirectories() throws IOException {
        Path sampleDir = Files.createDirectories(detailedDir.resolve("sampleA"));
        Files.writeString(sampleDir.resolve("sampleA.tsv"),
                "qseqid\tsseqid\tpositive\tmismatch\tgaps\tevalue\tbitscore\tslen\tlength\tqstart\tqend\tqseq\tsstart\tsend\tsseq\n"
                        + hitLine(0) + "\n"
                        + hitLine(3) + "\n" // over budget: rejected
                        + "\n");

        Map<String, Sample> samples = new HashMap<>();
        samples.put("sampleA", sample("sampleA"));
        BlastReports.attachHits(detailedDir, samples, PRIMERS, OPTIONS);

        assertEquals(1, samples.get("sampleA").blastResults().get("assay-F").size());
    }

    @Test
    void reportsWithoutMatchingSampleAreSkippedWithoutFailing() throws IOException {
        Files.writeString(detailedDir.resolve("stale.tsv"), hitLine(0) + "\n");
        Map<String, Sample> samples = new HashMap<>();
        samples.put("sampleA", sample("sampleA"));

        BlastReports.attachHits(detailedDir, samples, PRIMERS, OPTIONS);
        assertTrue(samples.get("sampleA").blastResults().isEmpty());
    }

    @Test
    void missingDirectoryFailsLoudly() {
        Map<String, Sample> samples = new HashMap<>();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> BlastReports.attachHits(detailedDir.resolve("ghost"), samples, PRIMERS, OPTIONS));
    }

    @Test
    void trimmedProbeEndsAreComparedAgainstTheSampleFasta() throws IOException {
        // 15-mer probe with one mismatch at position 3: BLAST aligns positions 4..15
        // only (a mismatch followed by 2 matches does not pay at +1/-3).
        String probe = "CCGGTTCGCTCAATG";
        String site = "CCAGTTCGCTCAATG";
        Path fasta = detailedDir.resolve("sampleB.fasta");
        Files.writeString(fasta, ">other\nACGT\n>contig7 some description\n"
                + "ATATATATAT\nATATATATAT\n" + site + "GAGAGAGAGA\n");
        Path sampleDir = Files.createDirectories(detailedDir.resolve("sampleB"));
        Files.writeString(sampleDir.resolve("sampleB.tsv"), String.join("\t",
                "contig7", "assay-P", "12", "0", "0", "0.01", "24.3", "15", "12",
                "24", "35", probe.substring(3), "4", "15", probe.substring(3)) + "\n");

        Sample sample = sample("sampleB");
        sample.setFile(fasta);
        Map<String, Sample> samples = new HashMap<>();
        samples.put("sampleB", sample);
        Map<String, String> primers = Map.of("assay-P", probe);

        // Before the fix this hit was rejected at any probe budget (3 unaligned bases).
        BlastReports.attachHits(detailedDir, samples, primers, new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000));
        var hit = samples.get("sampleB").blastResults().get("assay-P").getFirst();
        assertEquals(1, hit.mismatch());
        assertEquals(21, hit.qstart());
        assertEquals(site, hit.qseq());

        Map<String, Sample> strict = new HashMap<>();
        Sample again = sample("sampleB");
        again.setFile(fasta);
        strict.put("sampleB", again);
        BlastReports.attachHits(detailedDir, strict, primers, new AmpliconOptions(0, 0, 5, 0, 5, 0, 5000));
        assertTrue(strict.get("sampleB").blastResults().isEmpty());
    }
}
