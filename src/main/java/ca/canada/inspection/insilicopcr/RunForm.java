package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.pipeline.InputValidator;
import ca.canada.inspection.pipeline.PipelineConfig;

import java.nio.file.Path;

/**
 * What the window's form holds when **Run** is pressed, and the run it asks for: the one path
 * from the form to a {@link PipelineConfig}, apart from the window so it can be tested.
 *
 * @param evalueText the e-value field as typed
 */
record RunForm(Path input, Path output, Path primers, int threads, int mismatches, String evalueText,
               AmpliconSettings amplicon, FastqSettings fastq) {

    /** The run, or what is wrong with the form (a message for the window). */
    sealed interface Outcome {
        record Run(PipelineConfig config) implements Outcome {
        }

        record Invalid(String message) implements Outcome {
        }
    }

    /** Checks the form, every value before any is used, and builds the run. */
    Outcome toConfig() {
        String pathProblem = InputValidator.validate(input, output, primers);
        if (!pathProblem.isBlank()) {
            return new Outcome.Invalid(pathProblem);
        }
        double evalue;
        try {
            evalue = Double.parseDouble(evalueText.trim());
        } catch (NumberFormatException e) {
            evalue = Double.NaN;
        }
        if (!PipelineConfig.validEvalue(evalue)) {
            return new Outcome.Invalid("E-value must be a number greater than 0, e.g. "
                    + PipelineConfig.DEFAULT_EVALUE_TEXT + ".");
        }
        // The window checks every read, as the command line does by default; it has its own gel
        // viewer, so no --gel picture. The settings dialogs only accept valid values.
        try {
            return new Outcome.Run(PipelineConfig.builder(input, output, primers)
                    .threads(threads)
                    .evalue(evalue)
                    .ampliconOptions(amplicon.toOptions(mismatches))
                    .tadpole(fastq.tadpole())
                    .readMinFraction(fastq.readMinFraction())
                    .readExtension(fastq.readExtension())
                    .bbtoolsMemory(fastq.bbtoolsMemoryGiB())
                    .build());
        } catch (IllegalArgumentException e) {
            return new Outcome.Invalid(e.getMessage());
        }
    }
}
