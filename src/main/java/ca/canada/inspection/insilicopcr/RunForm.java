package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.pipeline.InputValidator;
import ca.canada.inspection.pipeline.PipelineException;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.pipeline.PipelineConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * What the window's form holds when **Run** is pressed, and the run it asks for: the one path
 * from the form to a {@link PipelineConfig}, apart from the window so it can be tested.
 *
 * @param annealTempText the annealing temperature field as typed, °C
 * @param evalueText     the e-value field as typed
 */
record RunForm(Path input, Path output, Path primers, int threads, String annealTempText, String evalueText,
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
        String pathProblem;
        try {
            pathProblem = InputValidator.validate(input, output, primers);
        } catch (PipelineException | java.io.UncheckedIOException e) {
            // A folder that cannot be listed (another user's, without permission): Run did nothing, the
            // exception escaping on the window's thread with a trace nobody sees.
            // In the words the command line uses: "... (<folder>: permission denied)".
            List<String> lines = RunFailure.lines(e);
            String causes = lines.stream().skip(1).map(line -> line.strip().replaceFirst("^Cause: ", ""))
                    .collect(java.util.stream.Collectors.joining("; "));
            String message = lines.getFirst().replaceFirst("^Error: ", "");
            return new Outcome.Invalid(causes.isEmpty() ? message : message + " (" + causes + ")");
        }
        if (!pathProblem.isBlank()) {
            return new Outcome.Invalid(pathProblem);
        }
        double evalue;
        try {
            evalue = Double.parseDouble(evalueText.trim());
        } catch (NumberFormatException e) {
            evalue = Double.NaN;
        }
        if (PipelineConfig.tooSmallForANumber(evalueText, evalue)) {
            // 1e-400 was told it was not above 0.
            return new Outcome.Invalid("E-value " + PipelineConfig.tooSmallMessage(evalueText) + ".");
        }
        if (!PipelineConfig.validEvalue(evalue)) {
            return new Outcome.Invalid("E-value must be a number greater than 0, e.g. "
                    + PipelineConfig.DEFAULT_EVALUE_TEXT + ".");
        }
        double annealTemp;
        try {
            annealTemp = Double.parseDouble(annealTempText.trim());
        } catch (NumberFormatException e) {
            annealTemp = Double.NaN;
        }
        if (!Double.isFinite(annealTemp) || annealTemp < 0 || annealTemp > 100) {
            return new Outcome.Invalid("Annealing temperature must be 0 to 100 °C, e.g. "
                    + java.math.BigDecimal.valueOf(ca.canada.inspection.pcr.TmGate.DEFAULT_ANNEAL_TEMP).stripTrailingZeros().toPlainString() + ".");
        }
        // The window checks every read, as the command line does by default; it has its own gel
        // viewer, so no --gel picture. The settings dialogs only accept valid values.
        try {
            return new Outcome.Run(PipelineConfig.builder(input, output, primers)
                    .threads(threads)
                    .evalue(evalue)
                    .ampliconOptions(amplicon.toOptions(annealTemp))
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
