package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.ConsolidatedReport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes the final consolidated PCR/qPCR report. */
final class ConsolidatedReportWriter {
    Path write(PipelineContext context, Map<String, List<List<String>>> extraRows) {
        return ConsolidatedReport.write(
                context.directories().consolidated(),
                context.samples(),
                context.primers(),
                context.config().ampliconOptions(),
                extraRows
        );
    }
}
