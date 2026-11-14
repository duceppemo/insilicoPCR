package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reads the per-sample BLAST TSV reports and attaches the accepted primer hits to the samples. */
public final class BlastReports {

    private BlastReports() {
    }

    /** Where the BLAST stage writes the report of the sample {@code sampleName}. */
    public static Path reportFile(Path detailedDir, String sampleName) {
        return detailedDir.resolve(sampleName).resolve(sampleName + ".tsv");
    }

    /**
     * Reads the BLAST report of each of {@code samples} (see {@link #reportFile}) and attaches
     * its accepted hits. Reports of other samples, left by an earlier run in the same output
     * folder, are not read.
     */
    public static void attachHits(Path detailedDir, Map<String, Sample> samples,
                                  PrimerPanel primers, AmpliconOptions options) {
        samples.forEach((sampleName, sample) ->
                parseSingleReport(reportFile(detailedDir, sampleName), sampleName, sample, primers, options));
    }

    private static void parseSingleReport(Path report, String sampleName, Sample sample,
                                          PrimerPanel primers, AmpliconOptions options) {
        // Trimmed hit ends are compared with the sequences the sample was searched in
        // (TrimmedEnds), read alongside the report one query at a time.
        List<Path> searched = searchedFiles(sample);
        try (BufferedReader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8);
             QueryTemplates templates = new QueryTemplates(searched)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                BlastHitParser.parse(line, sampleName, primers, options, templates)
                        .ifPresent(hit -> sample.addBlastResult(hit.subjectID(), hit));
            }
            if (!templates.missing().isEmpty()) {
                System.err.println("Warning: " + templates.missing().size() + " sequences with a trimmed primer or"
                        + " probe hit were not found in " + searched + " (e.g. " + templates.missing().iterator().next()
                        + "); the trimmed bases of their hits are not compared.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read BLAST report: " + report, e);
        }
    }

    /** The FASTA files BLAST searched for this sample (see the BLAST stage). */
    private static List<Path> searchedFiles(Sample sample) {
        if (sample.isFastq()) {
            return sample.getAssemblyFile() == null ? List.of() : List.of(sample.getAssemblyFile());
        }
        return sample.files().stream().filter(Files::isRegularFile).toList();
    }
}
