package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.FastqDiagnosticsReport;
import ca.canada.inspection.pcr.HtmlReport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pcr.SplitAcrossContigs;
import ca.canada.inspection.pipeline.RunMonitor.Step;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReportGenerator {
    private final ContigService contigs = new ContigService();
    private final BlastReportParser blastReports = new BlastReportParser();
    private final ConsolidatedReportWriter consolidatedReport = new ConsolidatedReportWriter();
    private final QaLogWriter qaLog = new QaLogWriter();

    public void generate(PipelineContext context) {
        contigs.load(context);
        blastReports.parse(context);

        RunMonitor monitor = context.monitor();
        ReadLevelStage.Settings search = new ReadLevelStage.Settings(context.dependencies().blastn(),
                context.directories().primerDatabase(), context.config().evalue(), context.config().threads(),
                context.config().readMinFraction(), monitor);
        Map<String, ReadEvidence> readEvidence = Map.of();
        if (context.hasFastqSamples()) {
            readEvidence = ReadLevelStage.run(search, context.directories().detailed(), context.samples(),
                    context.primers(), context.config().ampliconOptions());
            monitor.info("Completed read-level amplicon search");
        }
        monitor.stepDone(Step.READ_SEARCH);
        ReadExtensionStage.Result extension = ReadExtensionStage.Result.NONE;
        if (context.hasFastqSamples() && context.config().readExtension() != ReadExtensionCaller.Mode.OFF) {
            extension = ReadExtensionStage.run(new ReadExtensionStage.Settings(search,
                            // One sample at a time: each child gets everything.
                            BbtoolsCommands.of(context.dependencies(),
                                    ChildJavaMemory.forOneChild(context.childJavaMemoryBudgetGiB()),
                                    context.config().threads()),
                            context.dependencies().bbmapDirectory(), context.config().readExtension(),
                            context.expectedSizes()),
                    context.directories().detailed(), context.samples(), context.primers(),
                    context.config().ampliconOptions(), readEvidence);
            monitor.info("Completed read extension");
        }
        monitor.stepDone(Step.READ_EXTENSION);

        Path report = consolidatedReport.write(context, FastqDiagnosticsStage.extraRows(
                context.samples(), context.primers(), context.config().ampliconOptions(), readEvidence, extension));
        if (context.config().gel()) {
            String baseName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            GelImageWriter.write(report, context.config().output().resolve("synthetic_gel_" + baseName + ".png"),
                    context.samples().keySet());
        }
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report, context.samples(),
                context.primers(), context.config().ampliconOptions(), readEvidence, extension);
        diagnostics.logLines().forEach(monitor::warning);
        List<SplitAcrossContigs.Split> splits = assemblySplits(context);
        splits.forEach(split -> monitor.warning("Warning: " + split.message()));
        Path html = HtmlReport.write(report, context.samples().keySet(), RunDetails.fastqSampleNames(context.samples()),
                context.primers(), context.expectedSizes(), diagnostics.findings(),
                RunDetails.of(Dispatcher.VERSION, context.config().input(), context.config().primers(),
                        context.config().output(), context.config().ampliconOptions(),
                        context.hasFastqSamples() ? context.config().readMinFraction() : null,
                        context.config().readExtension()),
                context.inputIssues(), splits);
        monitor.info("HTML report written to " + html);
        List<String> qaLines = new ArrayList<>(RunDetails.inputIssueLines(context.inputIssues()));
        qaLines.addAll(context.expectedSizes().qaLogLines());
        qaLines.addAll(diagnostics.qaLogLines(context.config().tadpole(), context.config().readMinFraction(),
                context.config().readExtension()));
        splits.forEach(split -> qaLines.add("Primers at contig ends: " + split.message()));
        qaLog.write(context, Dispatcher.VERSION, qaLines);
        monitor.stepDone(Step.REPORTS);
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
                .forEach(file -> lengths.putAll(FastqDiagnosticsReport.contigLengths(file)));
        return lengths;
    }
}
