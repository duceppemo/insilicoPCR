package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;
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

/** {@link BlastHits#read}: the one loop that judges a table's hits, as each sample's table is read. */
class BlastHitsTest {

    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of("assay-F", PRIMER_20));
    private static final AmpliconOptions OPTIONS = AmpliconOptions.withMismatches(0);

    @TempDir
    Path detailedDir;

    /** Where the run writes a sample's BLAST report (pipeline.SampleFiles). */
    private Path report(String sampleName) {
        return detailedDir.resolve(sampleName).resolve(sampleName + ".tsv");
    }

    private static String hitLine(int mismatches) {
        return String.join("\t",
                "contig1", "assay-F", "20", String.valueOf(mismatches), "0", "1e-5", "40.1",
                "20", "20", "101", "120", PRIMER_20, "1", "20", PRIMER_20);
    }

    /** Each sample's table read for its hits, the missing sequences told to {@code warning}. */
    private void attach(Map<String, Sample> samples, PrimerPanel primers, AmpliconOptions options,
                        java.util.function.Consumer<String> warning) {
        samples.forEach((name, sample) -> {
            String missing = BlastHits.missingWarning(BlastHits.read(report(name), sample.files(), sample.hits(), primers,
                    options), sample.files());
            if (!missing.isEmpty()) {
                warning.accept(missing);
            }
        });
    }

    private Sample sample(String name) {
        Sample sample = new Sample(name);
        sample.setFileType(Sample.FileType.FASTA);
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
        attach(samples, PRIMERS, OPTIONS, warning -> {});

        assertEquals(1, samples.get("sampleA").hits().byOligo().get("assay-F").size());
    }

    @Test
    void anIdWithAByteThatIsNotUtf8IsReadAsTheFastaIs() throws IOException {
        // blastn copies the ID's Latin-1 é; read strictly as UTF-8, the run stopped for every sample.
        Path sampleDir = Files.createDirectories(detailedDir.resolve("sampleA"));
        Files.write(sampleDir.resolve("sampleA.tsv"), (BlastField.HEADER + "\n" + hitLine(0).replace("contig1",
                "Qu\u00e9bec_c1") + "\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        Map<String, Sample> samples = new HashMap<>();
        samples.put("sampleA", sample("sampleA"));
        attach(samples, PRIMERS, OPTIONS, warning -> {});

        // As SequenceFileUtils.openMaybeGzip reads the same header in the FASTA.
        assertEquals("Qu\uFFFDbec_c1", samples.get("sampleA").hits().byOligo().get("assay-F").getFirst().queryID());
    }

    @Test
    void aMissingReportFailsLoudly() {
        Map<String, Sample> samples = Map.of("ghost", sample("ghost"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> attach(samples, PRIMERS, OPTIONS, warning -> {}));
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
        sample.addFile(fasta);
        Map<String, Sample> samples = new HashMap<>();
        samples.put("sampleB", sample);
        PrimerPanel primers = PrimerPanel.of(Map.of("assay-P", probe));

        // Before the fix this hit was rejected at any probe budget (3 unaligned bases).
        attach(samples, primers, new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000), warning -> {});
        var hit = samples.get("sampleB").hits().byOligo().get("assay-P").getFirst();
        assertEquals(1, hit.mismatch());
        assertEquals(21, hit.qstart());
        assertEquals(site, hit.qseq());

        Map<String, Sample> strict = new HashMap<>();
        Sample again = sample("sampleB");
        again.addFile(fasta);
        strict.put("sampleB", again);
        attach(strict, primers, new AmpliconOptions(0, 0, 5, 0, 5, 0, 5000), warning -> {});
        assertTrue(strict.get("sampleB").hits().byOligo().isEmpty());
    }

    @Test
    void aTrimmedHitOnASequenceMissingFromTheSearchedFileIsAWarningForTheMonitor() throws IOException {
        // The same probe hit as above, but the FASTA holds another sequence: the trimmed bases
        // cannot be compared, which the run's monitor is told (it went to the console only).
        String probe = "CCGGTTCGCTCAATG";
        Path fasta = detailedDir.resolve("sampleC.fasta");
        Files.writeString(fasta, ">other\nACGT\n");
        Path sampleDir = Files.createDirectories(detailedDir.resolve("sampleC"));
        Files.writeString(sampleDir.resolve("sampleC.tsv"), String.join("\t",
                "contig7", "assay-P", "12", "0", "0", "0.01", "24.3", "15", "12",
                "24", "35", probe.substring(3), "4", "15", probe.substring(3)) + "\n");
        Sample sample = sample("sampleC");
        sample.addFile(fasta);
        java.util.List<String> warnings = new java.util.ArrayList<>();

        attach(Map.of("sampleC", sample), PrimerPanel.of(Map.of("assay-P", probe)),
                new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000), warnings::add);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Warning: 1 sequences with a trimmed primer or probe hit were not"
                + " found in [" + fasta + "] (e.g. contig7)"), warnings.getFirst());
    }
}
