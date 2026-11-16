package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.FastqDiagnostics;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.report.ConsolidatedReport;
import ca.canada.inspection.report.FastqDiagnosticsReport;
import ca.canada.inspection.util.SequenceFileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explains, from the read-level evidence of FASTQ samples, the assays the mini-assembly did not
 * report ({@code <report>_fastq_diagnostics.tsv} next to the consolidated report, and lines for
 * the run's log and {@code QAlog.txt}). The rows called from reads are made by
 * {@link ReportGenerator}. A step of {@link InSilicoPcrPipeline}.
 */
public final class FastqDiagnosticsStage {

    /**
     * @param file     TSV written, or {@code null} when the run had no FASTQ samples
     * @param findings findings in status, sample and assay order
     * @param notes    samples that could not be checked, with the reason
     */
    public record Result(Path file, List<Finding> findings, List<String> notes) {
        public List<Finding> withStatus(Status status) {
            return findings.stream().filter(finding -> finding.status() == status).toList();
        }

        /** Lines for the console or GUI log. */
        public List<String> logLines() {
            List<String> lines = new ArrayList<>();
            withStatus(Status.CALLED_FROM_READS).forEach(finding -> lines.add("Note: " + finding.message()));
            withStatus(Status.CALLED_BY_EXTENSION).forEach(finding -> lines.add("Note: " + finding.message()));
            withStatus(Status.WEAK_READ_SUPPORT).forEach(finding -> lines.add("Warning: " + finding.message()));
            withStatus(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS)
                    .forEach(finding -> lines.add("Warning: " + finding.message()));
            withStatus(Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT)
                    .forEach(finding -> lines.add("Warning: " + finding.message()));
            int notInReads = withStatus(Status.NOT_IN_READS).size();
            if (notInReads > 0) {
                lines.add("Note: " + notInReads + " FASTQ-mode negative(s) have both primers in the reads but no"
                        + " complete amplicon in any read; see " + file);
            }
            withStatus(Status.EXTENSION_IN_TANDEM_REPEAT).forEach(finding -> lines.add("Warning: " + finding.message()));
            int notClosed = withStatus(Status.NOT_CLOSED_BY_EXTENSION).size();
            if (notClosed > 0) {
                lines.add("Note: " + notClosed + " FASTQ-mode negative(s) have both primers in the reads, but neither"
                        + " a read nor the extended reads hold a complete amplicon; see " + file);
            }
            notes.forEach(note -> lines.add("Warning: " + note));
            return lines;
        }

        /** Lines for QAlog.txt. */
        public List<String> qaLogLines() {
            List<String> lines = new ArrayList<>();
            if (file == null) {
                return lines;
            }
            // The FASTQ settings are among the run's settings, above (RunSettings).
            lines.add("FASTQ mode diagnostics: " + file);
            List<Finding> called = withStatus(Status.CALLED_FROM_READS);
            if (!called.isEmpty()) {
                lines.add("FASTQ mode: " + called.size() + " assay(s) missing from the mini-assembly were called from"
                        + " reads (rows marked '" + ReadLevelCaller.ROW_PREFIX + "' in the report):");
                called.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> extended = withStatus(Status.CALLED_BY_EXTENSION);
            if (!extended.isEmpty()) {
                lines.add("FASTQ mode: " + extended.size() + " assay(s) have a product in no contig and in no read,"
                        + " called by extending reads (rows marked '" + ReadExtensionCaller.ROW_PREFIX + "' in the report; the"
                        + " extended read is the best-supported path, not an assembled consensus):");
                extended.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> weak = withStatus(Status.WEAK_READ_SUPPORT);
            if (!weak.isEmpty()) {
                lines.add("FASTQ mode WARNING: " + weak.size() + " assay(s) have a complete amplicon in too few"
                        + " of the informative reads and are NOT reported (contamination or mixed sample?):");
                weak.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> notInReads = withStatus(Status.NOT_IN_READS);
            if (!notInReads.isEmpty()) {
                lines.add("FASTQ mode: " + notInReads.size() + " negative assay(s) have both primers in the reads but no"
                        + " complete amplicon in any read; inconclusive for amplicons longer than the checkable length:");
                notInReads.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> unsupported = new ArrayList<>(withStatus(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS));
            unsupported.addAll(withStatus(Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT));
            if (!unsupported.isEmpty()) {
                lines.add("FASTQ mode WARNING: " + unsupported.size() + " assay(s) have a product that extended reads"
                        + " hold but read pairs do not confirm; NOT reported (a wrong turn through a"
                        + " repeat looks like this, and so does a real product through a repeat longer than the read"
                        + " pairs):");
                unsupported.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> inRepeat = withStatus(Status.EXTENSION_IN_TANDEM_REPEAT);
            if (!inRepeat.isEmpty()) {
                lines.add("FASTQ mode WARNING: " + inRepeat.size() + " assay(s) have both primers in the reads, but the"
                        + " reads extended from them run into a tandem repeat they cannot cross; inconclusive (confirm on"
                        + " an assembly of long reads, or by PCR):");
                inRepeat.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> notClosed = withStatus(Status.NOT_CLOSED_BY_EXTENSION);
            if (!notClosed.isEmpty()) {
                lines.add("FASTQ mode: " + notClosed.size() + " negative assay(s) have both primers in the reads, but"
                        + " neither a read nor the extended reads hold a complete amplicon:");
                notClosed.forEach(finding -> lines.add("  " + finding.message()));
            }
            int partial = withStatus(Status.PARTIAL_BAIT).size();
            if (partial > 0) {
                lines.add("FASTQ mode: " + partial + " negative assay(s) had only some primers in the reads"
                        + " (PARTIAL_BAIT, see diagnostics file).");
            }
            // Every status has its lines above (FastqDiagnosticsStageTest.everyStatusHasItsLinesInTheLog).
            if (findings.isEmpty()) {
                lines.add("FASTQ mode: no negative assay had its oligos in the baited reads.");
            }
            notes.forEach(note -> lines.add("FASTQ mode: " + note));
            return lines;
        }
    }

    private FastqDiagnosticsStage() {
    }

    /** The FASTQ diagnostics of a run, taking into account what the read extension showed. */
    public static Result run(Path consolidatedReport, Map<String, Sample> samples, PipelineDirectories directories,
                             PrimerPanel primers,
                             AmpliconOptions options, Map<String, ReadEvidence> evidence,
                             ReadExtensionStage.Result extension) {
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>(extension.notes());
        boolean anyFastq = false;

        for (Sample sample : new TreeMap<>(samples).values()) {
            if (!sample.isFastq()) {
                continue;
            }
            anyFastq = true;
            ReadEvidence reads = evidence.get(sample.getName());
            if (reads == null) {
                notes.add("sample " + sample.getName() + " was not searched at read level");
                continue;
            }
            Path assembly = directories.sample(sample.getName()).assembly();
            Map<String, Integer> lengths = Files.isRegularFile(assembly) ? SequenceFileUtils.sequenceLengths(assembly)
                    : Map.of();
            findings.addAll(FastqDiagnostics.assess(sample, primers, options, reads, lengths,
                    extension.of(sample.getName())));
        }
        if (!anyFastq) {
            return new Result(null, List.of(), List.of());
        }

        List<Finding> ordered = findings.stream()
                .sorted(Comparator.comparing(Finding::status)
                        .thenComparing(Finding::sample)
                        .thenComparing(Finding::assay))
                .toList();
        Path file = ConsolidatedReport.sibling(consolidatedReport, "_fastq_diagnostics.tsv");
        FastqDiagnosticsReport.write(file, ordered);
        return new Result(file, ordered, List.copyOf(notes));
    }
}
