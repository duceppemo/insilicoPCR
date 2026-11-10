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

        Map<String, ReadEvidence> readEvidence = context.hasFastqSamples()
                ? ReadLevelStage.run(new ReadLevelStage.Settings(context.dependencies().blastn(),
                        context.directories().primerDatabase(), context.config().evalue(), context.config().threads(),
                        context.config().readMinFraction()),
                        context.directories().detailed(), context.samples(), context.primers(),
                        context.config().ampliconOptions())
                : Map.of();

        Path report = consolidatedReport.write(context, FastqDiagnosticsStage.readLevelRows(
                context.samples(), context.primers(), context.config().ampliconOptions(), readEvidence));
        if (context.config().gel()) {
            String baseName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            GelImageWriter.write(report, context.config().output().resolve("synthetic_gel_" + baseName + ".png"),
                    context.samples().keySet());
        }
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report, context.samples(),
                context.primers(), context.config().ampliconOptions(), readEvidence);
        diagnostics.logLines().forEach(System.err::println);
        Path html = HtmlReport.write(report, context.samples().keySet(), RunDetails.fastqSampleNames(context.samples()),
                context.primers().keySet(), context.expectedSizes(), diagnostics.findings(),
                RunDetails.of(Dispatcher.VERSION, context.config().input(), context.config().primers(),
                        context.config().output(), context.config().ampliconOptions(),
                        context.hasFastqSamples() ? context.config().readMinFraction() : null));
        System.out.println("HTML report written to " + html);
        List<String> qaLines = new ArrayList<>(context.expectedSizes().qaLogLines());
        qaLines.addAll(diagnostics.qaLogLines(context.config().tadpole(), context.config().readMinFraction()));
        qaLog.write(context, Dispatcher.VERSION, qaLines);
    }
}
