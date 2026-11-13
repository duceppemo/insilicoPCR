package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.InputCheck;

/** Finds the samples of the input, in name order, and checks their files. */
public final class SampleRepository {
    /** The samples whose files can be analysed, and what was left out and why. */
    public InputCheck.Result check(PipelineConfig config) {
        return InputCheck.scan(config.input(), config.inputCheck(), config.threads());
    }
}
