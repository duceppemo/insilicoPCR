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
     * How the console or GUI log gives the findings of a status: each one on its own line at {@code level}, or,
     * with {@code count}, how many (its text follows the number, and the diagnostics file's name it).
     */
    private record LogEntry(Status status, String level, String count) {
    }

    /** The log's findings, status by status, in this order. */
    private static final List<LogEntry> LOG = List.of(
            new LogEntry(Status.CALLED_FROM_READS, "Note", null),
            new LogEntry(Status.CALLED_BY_EXTENSION, "Note", null),
            new LogEntry(Status.WEAK_READ_SUPPORT, "Warning", null),
            new LogEntry(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, "Warning", null),
            new LogEntry(Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT, "Warning", null),
            new LogEntry(Status.NOT_IN_READS, "Note", " FASTQ-mode negative(s) have both primers in the reads but no"
                    + " complete amplicon in any read; see "),
            new LogEntry(Status.EXTENSION_IN_TANDEM_REPEAT, "Warning", null),
            new LogEntry(Status.NOT_CLOSED_BY_EXTENSION, "Note", " FASTQ-mode negative(s) have both primers in the"
                    + " reads, but neither a read nor the extended reads hold a complete amplicon; see "));

    /**
     * How QAlog.txt gives the findings of some statuses: a heading for how many, then, when {@code listed}, each
     * one on its own line.
     */
    private record QaGroup(List<Status> statuses, java.util.function.IntFunction<String> heading, boolean listed) {
    }

    /** QAlog.txt's findings, group by group, in this order. */
    private static final List<QaGroup> QA_LOG = List.of(
            new QaGroup(List.of(Status.CALLED_FROM_READS), n -> "FASTQ mode: " + n + " assay(s) missing from the"
                    + " mini-assembly were called from reads (rows marked '" + ReadLevelCaller.ROW_PREFIX
                    + "' in the report):", true),
            new QaGroup(List.of(Status.CALLED_BY_EXTENSION), n -> "FASTQ mode: " + n + " assay(s) have a product in"
                    + " no contig and in no read, called by extending reads (rows marked '"
                    + ReadExtensionCaller.ROW_PREFIX + "' in the report; the extended read is the best-supported"
                    + " path, not an assembled consensus):", true),
            new QaGroup(List.of(Status.WEAK_READ_SUPPORT), n -> "FASTQ mode WARNING: " + n + " assay(s) have a"
                    + " complete amplicon in too few of the informative reads and are NOT reported (contamination or"
                    + " mixed sample?):", true),
            new QaGroup(List.of(Status.NOT_IN_READS), n -> "FASTQ mode: " + n + " negative assay(s) have both"
                    + " primers in the reads but no complete amplicon in any read; inconclusive for amplicons longer"
                    + " than the checkable length:", true),
            new QaGroup(List.of(Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT),
                    n -> "FASTQ mode WARNING: " + n + " assay(s) have a product that extended reads hold but read"
                    + " pairs do not confirm; NOT reported (a wrong turn through a repeat looks like this, and so"
                    + " does a real product through a repeat longer than the read pairs):", true),
            new QaGroup(List.of(Status.EXTENSION_IN_TANDEM_REPEAT), n -> "FASTQ mode WARNING: " + n + " assay(s)"
                    + " have both primers in the reads, but the reads extended from them run into a tandem repeat"
                    + " they cannot cross; inconclusive (confirm on an assembly of long reads, or by PCR):", true),
            new QaGroup(List.of(Status.NOT_CLOSED_BY_EXTENSION), n -> "FASTQ mode: " + n + " negative assay(s) have"
                    + " both primers in the reads, but neither a read nor the extended reads hold a complete"
                    + " amplicon:", true),
            new QaGroup(List.of(Status.PARTIAL_BAIT), n -> "FASTQ mode: " + n + " negative assay(s) had only some"
                    + " primers in the reads (PARTIAL_BAIT, see diagnostics file).", false));

    /**
     * @param file     TSV written, or {@code null} when the run had no FASTQ samples
     * @param findings findings in status, sample and assay order
     * @param notes    samples that could not be checked, with the reason
     */
    public record Result(Path file, List<Finding> findings, List<String> notes) {
        public List<Finding> withStatus(Status status) {
            return findings.stream().filter(finding -> finding.status() == status).toList();
        }

        /** Lines for the console or GUI log: per status ({@link #LOG}), in its order, then the notes. */
        public List<String> logLines() {
            List<String> lines = new ArrayList<>();
            for (LogEntry entry : LOG) {
                List<Finding> found = withStatus(entry.status());
                if (entry.count() == null) {
                    found.forEach(finding -> lines.add(entry.level() + ": " + finding.message()));
                } else if (!found.isEmpty()) {
                    lines.add(entry.level() + ": " + found.size() + entry.count() + file);
                }
            }
            notes.forEach(note -> lines.add("Warning: " + note));
            return lines;
        }

        /** Lines for QAlog.txt: per group of statuses ({@link #QA_LOG}), in its order. */
        public List<String> qaLogLines() {
            List<String> lines = new ArrayList<>();
            if (file == null) {
                return lines;
            }
            // The FASTQ settings are among the run's settings, above (RunSettings).
            lines.add("FASTQ mode diagnostics: " + file);
            for (QaGroup group : QA_LOG) {
                List<Finding> found = new ArrayList<>();
                group.statuses().forEach(status -> found.addAll(withStatus(status)));
                if (!found.isEmpty()) {
                    lines.add(group.heading().apply(found.size()));
                    if (group.listed()) {
                        found.forEach(finding -> lines.add("  " + finding.message()));
                    }
                }
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
