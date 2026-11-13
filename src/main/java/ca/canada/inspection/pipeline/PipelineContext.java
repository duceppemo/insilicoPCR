package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.util.InputCheck;

import java.util.List;
import java.util.Map;

public record PipelineContext(
        PipelineConfig config,
        RuntimeDependencies dependencies,
        PipelineDirectories directories,
        Map<String, Sample> samples,
        Map<String, String> primers,
        ExpectedSizes expectedSizes,
        int childJavaMemoryGiB,
        List<InputCheck.Issue> inputIssues
) {
    public boolean hasFastqSamples() {
        return samples.values().stream().anyMatch(Sample::isFastq);
    }
}
