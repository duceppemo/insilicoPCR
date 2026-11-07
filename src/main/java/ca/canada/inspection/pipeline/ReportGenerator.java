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
        FastqDiagnosticsStage.Result diagnostics = FastqDiagnosticsStage.run(report,
                context.directories().detailed(), context.samples(), context.primers(),
                context.config().ampliconOptions());
        diagnostics.warnings().forEach(finding -> System.err.println("Warning: " + finding.message()));
        if (!diagnostics.warnings().isEmpty()) {
            System.err.println("Warning: " + diagnostics.warnings().size() + " FASTQ-mode negative(s) are not supported"
                    + " by the reads; see " + diagnostics.file());
        }
        diagnostics.notes().forEach(note -> System.err.println("Warning: " + note));
        qaLog.write(context, Dispatcher.VERSION, diagnostics.qaLogLines(context.config().tadpole()));
    }
}
