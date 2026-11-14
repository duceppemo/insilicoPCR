package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.util.InputCheck;

import java.util.List;
import java.util.Map;

public record PipelineContext(
        PipelineConfig config,
        RuntimeDependencies dependencies,
        PipelineDirectories directories,
        Map<String, Sample> samples,
        PrimerPanel primers,
        int childJavaMemoryBudgetGiB,
        List<InputCheck.Issue> inputIssues,
        RunMonitor monitor
) {
    public boolean hasFastqSamples() {
        return samples.values().stream().anyMatch(Sample::isFastq);
    }
}
