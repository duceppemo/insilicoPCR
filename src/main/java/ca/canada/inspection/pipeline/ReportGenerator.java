package ca.canada.inspection.pipeline;

import ca.canada.inspection.gel.GelImageWriter;
import ca.canada.inspection.gel.SyntheticGelImage;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.Products;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.SplitAcrossContigs;
import ca.canada.inspection.report.ConsolidatedReport;
import ca.canada.inspection.report.HtmlReport;
import ca.canada.inspection.util.SequenceFileUtils;
import ca.canada.inspection.util.Version;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * The last step of a run: writes {@code report.tsv}, the FASTQ diagnostics, the HTML report,
 * the {@code --gel} image and {@code QAlog.txt}, from the hits and the calls of the steps
 * before it.
 */
public final class ReportGenerator {
    private ReportGenerator() {
    }

    /**
     * @param readEvidence what the read-level search found, per FASTQ sample; empty without one
     * @param extension    what extending reads found; {@link ReadExtensionStage.Result#NONE} when not run
     */
    public static RunResult write(PipelineContext context, Map<String, ReadEvidence> readEvidence,
                                  ReadExtensionStage.Result extension) {
        RunMonitor monitor = context.monitor();
        // The run's rows, made once: the report file, the HTML report and the gel all show these.
        List<ReportRow> rows = Products.ofRun(context.samples(), context.primers(), context.config().ampliconOptions(),
                extraRows(context, readEvidence, extension));
        Path report = ConsolidatedReport.write(context.directories().consolidated(), rows, context.primers());
        if (context.config().gel()) {
            GelImageWriter.write(report, SyntheticGelImage.defaultPng(report), context.samples().keySet(),
                    monitor::info, monitor::warning);
        }
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report, context.samples(), context.directories(),
                context.primers(), context.config().ampliconOptions(), readEvidence, extension);
        diagnostics.logLines().forEach(monitor::warning);
        List<SplitAcrossContigs.Split> splits = assemblySplits(context);
        splits.forEach(split -> monitor.warning("Warning: " + split.message()));
        Path html = HtmlReport.write(report, rows, context.samples().keySet(), fastqSampleNames(context.samples()),
                context.primers(), diagnostics.findings(),
                RunSettings.details(Version.VERSION, context.config(), context.hasFastqSamples()),
                context.inputIssues(), splits);
        monitor.info("HTML report written to " + html);
        List<String> qaLines = new ArrayList<>(QaLogWriter.inputIssueLines(context.inputIssues()));
        qaLines.addAll(context.primers().expectedSizes().qaLogLines());
        qaLines.addAll(diagnostics.qaLogLines());
        splits.forEach(split -> qaLines.add("Primers at contig ends: " + split.message()));
        // QAlog.txt marks the run finished and names its report: a run stopped before it does not
        // write it, and the folder's log still names the last finished run's report.
        if (monitor.isCancelled()) {
            throw new CancellationException("The run was cancelled (reports).");
        }
        QaLogWriter.write(context, Version.VERSION, report, qaLines);
        return new RunResult(report, html, List.copyOf(context.samples().keySet()));
    }

    /**
     * The rows to append to each FASTQ sample's own, keyed by sample name: the products called
     * from single reads that the mini-assembly does not report, then those called by
     * extending reads.
     */
    static Map<String, List<ReportRow>> extraRows(PipelineContext context, Map<String, ReadEvidence> readEvidence,
                                                  ReadExtensionStage.Result extension) {
        Map<String, List<ReportRow>> rows = new HashMap<>();
        readEvidence.forEach((sampleName, reads) -> {
            Sample sample = context.samples().get(sampleName);
            if (sample != null) {
                List<ReportRow> sampleRows = ReadLevelCaller.reportRows(sample, context.primers(),
                        context.config().ampliconOptions(), reads);
                if (!sampleRows.isEmpty()) {
                    rows.put(sampleName, new ArrayList<>(sampleRows));
                }
            }
        });
        extension.rows().forEach((sample, list) ->
                rows.computeIfAbsent(sample, ignored -> new ArrayList<>()).addAll(list));
        return rows;
    }

    /** Assays of the assembly samples with no product whose primers point off contig ends. */
    private static List<SplitAcrossContigs.Split> assemblySplits(PipelineContext context) {
        List<SplitAcrossContigs.Split> splits = new ArrayList<>();
        for (Sample sample : context.samples().values()) {
            if (!sample.isFastq()) {
                splits.addAll(SplitAcrossContigs.find(sample, context.primers(), context.config().ampliconOptions(),
                        () -> sequenceLengths(context, sample)));
            }
        }
        return splits;
    }

    private static Map<String, Integer> sequenceLengths(PipelineContext context, Sample sample) {
        Map<String, Integer> lengths = new HashMap<>();
        context.searchedFasta(sample).stream().filter(Files::isRegularFile)
                .forEach(file -> lengths.putAll(SequenceFileUtils.sequenceLengths(file)));
        return lengths;
    }

    /** Names of the samples given as reads. */
    private static java.util.Set<String> fastqSampleNames(Map<String, Sample> samples) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        samples.forEach((name, sample) -> {
            if (sample.isFastq()) {
                names.add(name);
            }
        });
        return names;
    }
}
