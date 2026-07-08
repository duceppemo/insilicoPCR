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
        consolidatedReport.write(context);
        qaLog.write(context, Dispatcher.VERSION);
    }
}
