package ca.canada.inspection.pipeline;

import ca.canada.inspection.commandpcr.CommandMethods;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Sample;

import java.util.HashMap;

public final class ReportGenerator {
    public void generate(PipelineContext context) {
        HashMap<String, Sample> samples = new HashMap<>(context.samples());
        HashMap<String, String> primers = new HashMap<>(context.primers());

        CommandMethods.addContigDict(samples);
        CommandMethods.parseBlastOutput(
                context.directories().consolidated(),
                context.directories().detailed(),
                primers,
                context.config().mismatches(),
                samples
        );
        CommandMethods.makeConsolidatedReport(
                context.directories().consolidated(),
                java.io.File.separator,
                samples,
                primers
        );
        CommandMethods.makeQALog(
                context.directories().output().resolve("QAlog.txt"),
                Dispatcher.version,
                context.directories().output(),
                context.config().input(),
                context.config().primers(),
                context.dependencies().bbmapDirectory(),
                context.dependencies().blastBinDirectory()
        );
    }
}
