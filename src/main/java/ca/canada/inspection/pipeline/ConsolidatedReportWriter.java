package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.ConsolidatedReport;

/** Writes the final consolidated PCR/qPCR report. */
final class ConsolidatedReportWriter {
    void write(PipelineContext context) {
        ConsolidatedReport.write(
                context.directories().consolidated(),
                context.samples(),
                context.primers(),
                context.config().ampliconOptions()
        );
    }
}
