package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.ConsolidatedReport;

import java.nio.file.Path;

/** Writes the final consolidated PCR/qPCR report. */
final class ConsolidatedReportWriter {
    Path write(PipelineContext context) {
        return ConsolidatedReport.write(
                context.directories().consolidated(),
                context.samples(),
                context.primers(),
                context.config().ampliconOptions()
        );
    }
}
