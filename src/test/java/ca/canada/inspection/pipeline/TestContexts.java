package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.PrimerPanel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Run contexts for tests of the steps that only write files. */
final class TestContexts {
    private TestContexts() {
    }

    /** A context writing to {@code output}, with stand-in tool folders and no primer. */
    static PipelineContext writingTo(Path output, Map<String, Sample> samples) {
        PipelineConfig config = PipelineConfig.builder(output.resolveSibling("input"), output, output.resolve("primers.fasta")).threads(1).build();
        RuntimeDependencies dependencies = new RuntimeDependencies(new AppPaths.RuntimeLayout(output,
                output.resolve("bbmap"), output.resolve("blast"), output.resolve("java")));
        PipelineDirectories directories = new PipelineDirectories(output, output.resolve("detailed_report"),
                output.resolve(PipelineDirectories.CONSOLIDATED));
        return new PipelineContext(config, dependencies, directories, samples, PrimerPanel.EMPTY, 1, List.of(),
                RunMonitor.CONSOLE);
    }
}
