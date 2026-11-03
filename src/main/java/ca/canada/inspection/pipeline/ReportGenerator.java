package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;

public final class ReportGenerator {
    private final ContigService contigs = new ContigService();
    private final BlastReportParser blastReports = new BlastReportParser();
    private final ConsolidatedReportWriter consolidatedReport = new ConsolidatedReportWriter();
    private final QaLogWriter qaLog = new QaLogWriter();

    public void generate(PipelineContext context) {
        contigs.load(context);
        blastReports.parse(context);
        java.nio.file.Path report = consolidatedReport.write(context);
        if (context.config().gel()) {
            String baseName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            GelImageWriter.write(report, context.config().output().resolve("synthetic_gel_" + baseName + ".png"),
                    context.samples().keySet());
        }
        qaLog.write(context, Dispatcher.VERSION);
    }
}
