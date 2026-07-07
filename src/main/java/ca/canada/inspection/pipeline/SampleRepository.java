package ca.canada.inspection.pipeline;

import ca.canada.inspection.commandpcr.CommandMethods;
import ca.canada.inspection.insilicopcr.Sample;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SampleRepository {
    public Map<String, Sample> load(PipelineConfig config) {
        return new LinkedHashMap<>(CommandMethods.createSampleDict(config.input()));
    }
}
