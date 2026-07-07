package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;

import java.util.Map;

public record PipelineContext(
        PipelineConfig config,
        RuntimeDependencies dependencies,
        PipelineDirectories directories,
        Map<String, Sample> samples,
        Map<String, String> primers,
        int childJavaMemoryGiB
) {
    public boolean hasFastqSamples() {
        return samples.values().stream().anyMatch(Sample::isFastq);
    }
}
