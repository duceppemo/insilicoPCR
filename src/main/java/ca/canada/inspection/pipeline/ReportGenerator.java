package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.pcr.HtmlReport;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.nio.file.Path;
import java.util.ArrayList;
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

        ReadLevelStage.Settings search = new ReadLevelStage.Settings(context.dependencies().blastn(),
                context.directories().primerDatabase(), context.config().evalue(), context.config().threads(),
                context.config().readMinFraction());
        Map<String, ReadEvidence> readEvidence = context.hasFastqSamples()
                ? ReadLevelStage.run(search, context.directories().detailed(), context.samples(), context.primers(),
                        context.config().ampliconOptions())
                : Map.of();
        ReadExtensionStage.Result extension = ReadExtensionStage.Result.NONE;
        if (context.hasFastqSamples()) {
            extension = ReadExtensionStage.run(new ReadExtensionStage.Settings(search,
                            new BbtoolsCommands(context.dependencies().javaCommand(), context.dependencies().bbtoolsJar(),
                                    context.childJavaMemoryGiB(), context.config().threads()),
                            context.dependencies().bbmapDirectory(), context.config().readExtension(),
                            context.expectedSizes()),
                    context.directories().detailed(), context.samples(), context.primers(),
                    context.config().ampliconOptions(), readEvidence);
        }

        Path report = consolidatedReport.write(context, FastqDiagnosticsStage.extraRows(
                context.samples(), context.primers(), context.config().ampliconOptions(), readEvidence, extension));
        if (context.config().gel()) {
            String baseName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            GelImageWriter.write(report, context.config().output().resolve("synthetic_gel_" + baseName + ".png"),
                    context.samples().keySet());
        }
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report, context.samples(),
                context.primers(), context.config().ampliconOptions(), readEvidence, extension);
        diagnostics.logLines().forEach(System.err::println);
        Path html = HtmlReport.write(report, context.samples().keySet(), RunDetails.fastqSampleNames(context.samples()),
                context.primers(), context.expectedSizes(), diagnostics.findings(),
                RunDetails.of(Dispatcher.VERSION, context.config().input(), context.config().primers(),
                        context.config().output(), context.config().ampliconOptions(),
                        context.hasFastqSamples() ? context.config().readMinFraction() : null,
                        context.config().readExtension()));
        System.out.println("HTML report written to " + html);
        List<String> qaLines = new ArrayList<>(context.expectedSizes().qaLogLines());
        qaLines.addAll(diagnostics.qaLogLines(context.config().tadpole(), context.config().readMinFraction(),
                context.config().readExtension()));
        qaLog.write(context, Dispatcher.VERSION, qaLines);
    }
}
