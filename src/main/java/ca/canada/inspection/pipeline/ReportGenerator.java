package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.nio.file.Path;
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
        qaLog.write(context, Dispatcher.VERSION,
                diagnostics.qaLogLines(context.config().tadpole(), context.config().readMinFraction()));
    }
}
