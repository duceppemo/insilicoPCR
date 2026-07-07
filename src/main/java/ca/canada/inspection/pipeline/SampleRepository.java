package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.SequenceFileUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Loads input FASTA/FASTQ files into deterministic sample order. */
public final class SampleRepository {
    public Map<String, Sample> load(PipelineConfig config) {
        return new LinkedHashMap<>(SequenceFileUtils.createSampleMap(config.input()));
    }
}
