package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ConsolidatedReport;
import ca.canada.inspection.pcr.FastqDiagnostics;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.FastqDiagnosticsReport;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns the read-level evidence of FASTQ samples into report rows and into
 * {@code <report>_fastq_diagnostics.tsv} next to the consolidated report. Shared by
 * the command-line and GUI pipelines.
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
            withStatus(Status.WEAK_READ_SUPPORT).forEach(finding -> lines.add("Warning: " + finding.message()));
            int notInReads = withStatus(Status.NOT_IN_READS).size();
            if (notInReads > 0) {
                lines.add("Note: " + notInReads + " FASTQ-mode negative(s) have both primers in the reads but no"
                        + " complete amplicon in any read; see " + file);
            }
            notes.forEach(note -> lines.add("Warning: " + note));
            return lines;
        }

        /** Lines for QAlog.txt. */
        public List<String> qaLogLines(TadpoleOptions tadpole, double readMinFraction) {
            List<String> lines = new ArrayList<>();
            if (file == null) {
                return lines;
            }
            lines.add("FASTQ mode Tadpole settings: " + String.join(" ", tadpole.arguments()));
            lines.add("FASTQ mode read-level calls need at least " + ReadLevelCaller.MIN_SUPPORTING_READS
                    + " reads and " + Math.round(100 * readMinFraction) + "% of informative reads"
                    + " (--read-min-fraction " + readMinFraction + ")");
            lines.add("FASTQ mode diagnostics: " + file);
            List<Finding> called = withStatus(Status.CALLED_FROM_READS);
            if (!called.isEmpty()) {
                lines.add("FASTQ mode: " + called.size() + " assay(s) missing from the mini-assembly were called from"
                        + " reads (rows marked 'FASTQ read-level call' in the report):");
                called.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> weak = withStatus(Status.WEAK_READ_SUPPORT);
            if (!weak.isEmpty()) {
                lines.add("FASTQ mode WARNING: " + weak.size() + " assay(s) have a complete amplicon in too few"
                        + " of the informative reads and are NOT reported (contamination, mixed sample or repeated primer"
                        + " site?):");
                weak.forEach(finding -> lines.add("  " + finding.message()));
            }
            List<Finding> notInReads = withStatus(Status.NOT_IN_READS);
            if (!notInReads.isEmpty()) {
                lines.add("FASTQ mode: " + notInReads.size() + " negative assay(s) have both primers in the reads but no"
                        + " complete amplicon in any read; inconclusive for amplicons longer than the checkable length:");
                notInReads.forEach(finding -> lines.add("  " + finding.message()));
            }
            int partial = withStatus(Status.PARTIAL_BAIT).size();
            if (partial > 0) {
                lines.add("FASTQ mode: " + partial + " negative assay(s) had only some primers in the reads"
                        + " (PARTIAL_BAIT, see diagnostics file).");
            }
            if (called.isEmpty() && weak.isEmpty() && notInReads.isEmpty() && partial == 0) {
                lines.add("FASTQ mode: no negative assay had its oligos in the baited reads.");
            }
            notes.forEach(note -> lines.add("FASTQ mode: " + note));
            return lines;
        }
    }

    private FastqDiagnosticsStage() {
    }

    /**
     * Report rows for assays the mini-assembly did not report but reads hold, keyed
     * by sample name; pass them to {@link ConsolidatedReport#write(Path, Map, Map, AmpliconOptions, Map)}.
     */
    public static Map<String, List<List<String>>> readLevelRows(Map<String, Sample> samples,
                                                                Map<String, String> primers,
                                                                AmpliconOptions options,
                                                                Map<String, ReadEvidence> evidence) {
        Map<String, List<List<String>>> rows = new HashMap<>();
        evidence.forEach((sampleName, reads) -> {
            Sample sample = samples.get(sampleName);
            if (sample == null) {
                return;
            }
            Set<String> reported = ConsolidatedReport.reportedAssays(sample, primers, options);
            List<List<String>> sampleRows = new TreeMap<>(reads.calls()).values().stream()
                    .filter(call -> !reported.contains(call.assay()) && call.isReportable())
                    .map(ReadLevelCaller::reportRow)
                    .toList();
            if (!sampleRows.isEmpty()) {
                rows.put(sampleName, sampleRows);
            }
        });
        return rows;
    }

    public static Result run(Path consolidatedReport, Map<String, Sample> samples, Map<String, String> primers,
                             AmpliconOptions options, Map<String, ReadEvidence> evidence) {
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
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
            Map<String, Integer> lengths = sample.getAssemblyFile() != null && Files.isRegularFile(sample.getAssemblyFile())
                    ? FastqDiagnosticsReport.contigLengths(sample.getAssemblyFile())
                    : Map.of();
            findings.addAll(FastqDiagnostics.assess(sample, primers, options, reads, lengths));
        }
        if (!anyFastq) {
            return new Result(null, List.of(), List.of());
        }

        List<Finding> ordered = findings.stream()
                .sorted(Comparator.comparing(Finding::status)
                        .thenComparing(Finding::sample)
                        .thenComparing(Finding::assay))
                .toList();
        String baseName = consolidatedReport.getFileName().toString().replaceFirst("\\.tsv$", "");
        Path file = consolidatedReport.resolveSibling(baseName + "_fastq_diagnostics.tsv");
        FastqDiagnosticsReport.write(file, ordered);
        return new Result(file, ordered, List.copyOf(notes));
    }
}
