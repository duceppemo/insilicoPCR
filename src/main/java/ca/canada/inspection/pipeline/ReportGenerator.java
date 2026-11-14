package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
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
    public static void write(PipelineContext context, Map<String, ReadEvidence> readEvidence,
                             ReadExtensionStage.Result extension) {
        RunMonitor monitor = context.monitor();
        Path report = ConsolidatedReport.write(context.directories().consolidated(), context.samples(),
                context.primers(), context.config().ampliconOptions(),
                FastqDiagnosticsStage.extraRows(context.samples(), context.primers(), context.config().ampliconOptions(),
                        readEvidence, extension));
        if (context.config().gel()) {
            String baseName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            GelImageWriter.write(report, context.config().output().resolve("synthetic_gel_" + baseName + ".png"),
                    context.samples().keySet(), monitor);
        }
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report, context.samples(),
                context.primers(), context.config().ampliconOptions(), readEvidence, extension);
        diagnostics.logLines().forEach(monitor::warning);
        List<SplitAcrossContigs.Split> splits = assemblySplits(context);
        splits.forEach(split -> monitor.warning("Warning: " + split.message()));
        Path html = HtmlReport.write(report, context.samples().keySet(), RunDetails.fastqSampleNames(context.samples()),
                context.primers(), diagnostics.findings(),
                RunDetails.of(Version.VERSION, context.config().input(), context.config().primers(),
                        context.config().output(), context.config().ampliconOptions(),
                        context.hasFastqSamples() ? context.config().readMinFraction() : null,
                        context.config().readExtension()),
                context.inputIssues(), splits);
        monitor.info("HTML report written to " + html);
        List<String> qaLines = new ArrayList<>(RunDetails.inputIssueLines(context.inputIssues()));
        qaLines.addAll(context.primers().expectedSizes().qaLogLines());
        qaLines.addAll(diagnostics.qaLogLines(context.config().tadpole(), context.config().readMinFraction(),
                context.config().readExtension()));
        splits.forEach(split -> qaLines.add("Primers at contig ends: " + split.message()));
        QaLogWriter.write(context, Version.VERSION, qaLines);
    }

    /** Assays of the assembly samples with no product whose primers point off contig ends. */
    private static List<SplitAcrossContigs.Split> assemblySplits(PipelineContext context) {
        List<SplitAcrossContigs.Split> splits = new ArrayList<>();
        for (Sample sample : context.samples().values()) {
            if (!sample.isFastq()) {
                splits.addAll(SplitAcrossContigs.find(sample, context.primers(), context.config().ampliconOptions(),
                        () -> sequenceLengths(sample)));
            }
        }
        return splits;
    }

    private static Map<String, Integer> sequenceLengths(Sample sample) {
        Map<String, Integer> lengths = new HashMap<>();
        sample.files().stream().filter(Files::isRegularFile)
                .forEach(file -> lengths.putAll(SequenceFileUtils.sequenceLengths(file)));
        return lengths;
    }
}
