package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.FastqDiagnostics;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnosticsReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs {@link FastqDiagnostics} over the FASTQ samples of a finished run and writes
 * {@code <report>_fastq_diagnostics.tsv} next to the consolidated report. Shared by
 * the command-line and GUI pipelines.
 */
public final class FastqDiagnosticsStage {

    /**
     * @param file     TSV written, or {@code null} when the run had no FASTQ samples
     * @param findings all findings, warnings first in sample/assay order
     * @param notes    samples that could not be checked, with the reason
     */
    public record Result(Path file, List<Finding> findings, List<String> notes) {
        public List<Finding> warnings() {
            return findings.stream().filter(finding -> finding.status().isWarning()).toList();
        }

        /** Lines for QAlog.txt. */
        public List<String> qaLogLines(TadpoleOptions tadpole) {
            List<String> lines = new ArrayList<>();
            if (file == null) {
                return lines;
            }
            lines.add("FASTQ mode Tadpole settings: " + String.join(" ", tadpole.arguments()));
            lines.add("FASTQ mode diagnostics: " + file);
            List<Finding> warnings = warnings();
            if (warnings.isEmpty()) {
                lines.add("FASTQ mode: no negative assay had its oligos in the baited reads.");
            } else {
                lines.add("FASTQ mode WARNINGS (" + warnings.size()
                        + "): oligos were found in the reads but no amplicon was called;"
                        + " these negatives are not supported by the reads:");
                warnings.forEach(finding -> lines.add("  " + finding.status() + ": " + finding.message()));
            }
            long partial = findings.size() - warnings.size();
            if (partial > 0) {
                lines.add("FASTQ mode: " + partial + " negative assay(s) had only some primers in the reads"
                        + " (PARTIAL_BAIT, see diagnostics file).");
            }
            notes.forEach(note -> lines.add("FASTQ mode: " + note));
            return lines;
        }
    }

    private FastqDiagnosticsStage() {
    }

    public static Result run(Path consolidatedReport, Path detailedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options) {
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean anyFastq = false;

        for (Sample sample : new TreeMap<>(samples).values()) {
            if (!sample.isFastq()) {
                continue;
            }
            anyFastq = true;
            Path sampleDir = detailedDir.resolve(sample.getName());
            Path stats = BbtoolsCommands.baitStats(sampleDir, sample.getName());
            if (!Files.isRegularFile(stats)) {
                notes.add("sample " + sample.getName() + " not checked for unsupported negatives: missing " + stats);
                continue;
            }
            Map<String, Integer> lengths = sample.getAssemblyFile() != null && Files.isRegularFile(sample.getAssemblyFile())
                    ? FastqDiagnosticsReport.contigLengths(sample.getAssemblyFile())
                    : Map.of();
            findings.addAll(FastqDiagnostics.assess(sample, primers, options,
                    FastqDiagnosticsReport.readBaitStats(stats), lengths));
        }
        if (!anyFastq) {
            return new Result(null, List.of(), List.of());
        }

        // Warnings first, keeping sample/assay order within each group.
        List<Finding> ordered = new ArrayList<>(findings.stream().filter(f -> f.status().isWarning()).toList());
        ordered.addAll(findings.stream().filter(f -> !f.status().isWarning()).toList());

        String baseName = consolidatedReport.getFileName().toString().replaceFirst("\\.tsv$", "");
        Path file = consolidatedReport.resolveSibling(baseName + "_fastq_diagnostics.tsv");
        FastqDiagnosticsReport.write(file, ordered);
        return new Result(file, List.copyOf(ordered), List.copyOf(notes));
    }
}
