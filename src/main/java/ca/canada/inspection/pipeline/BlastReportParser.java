package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.BlastReports;

/** Parses BLAST TSV output and attaches accepted primer hits to samples. */
final class BlastReportParser {
    void parse(PipelineContext context) {
        BlastReports.attachHits(
                context.directories().detailed(),
                context.samples(),
                context.primers(),
                context.config().ampliconOptions()
        );
    }
}
