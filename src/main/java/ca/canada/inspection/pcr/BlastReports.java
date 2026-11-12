package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks per-sample BLAST TSV reports and attaches accepted primer hits to the
 * matching samples. Shared by the command-line and GUI pipelines.
 */
public final class BlastReports {

    private BlastReports() {
    }

    public static void attachHits(Path detailedDir, Map<String, Sample> samples,
                                  Map<String, String> primers, AmpliconOptions options) {
        for (Path report : listTsvFiles(detailedDir)) {
            String sampleName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            Sample sample = samples.get(sampleName);
            if (sample == null) {
                // Output directories may be reused across runs, so stale reports from
                // other sample sets are expected; still say so instead of hiding it.
                System.err.println("Warning: ignoring BLAST report with no matching sample in this run: " + report);
                continue;
            }
            parseSingleReport(report, sampleName, sample, primers, options);
        }
    }

    private static List<Path> listTsvFiles(Path detailedDir) {
        try (Stream<Path> paths = Files.walk(detailedDir, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tsv"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list BLAST reports in: " + detailedDir, e);
        }
    }

    private static void parseSingleReport(Path report, String sampleName, Sample sample,
                                          Map<String, String> primers, AmpliconOptions options) {
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
            throw new IllegalStateException("Unable to parse BLAST report: " + report, e);
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
