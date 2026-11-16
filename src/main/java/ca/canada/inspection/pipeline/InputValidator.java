package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.InputCheck;

import java.nio.file.Files;
import java.nio.file.Path;

/** The application's checks of its three paths before a run starts; the run checks them again. */
public final class InputValidator {
    private InputValidator() {}

    /** What is wrong with the paths, or an empty string. */
    public static String validate(Path inputFile, Path outDir, Path primerFile) {
        if (inputFile == null || outDir == null || primerFile == null) {
            return "Please choose an input file or folder, a primer file and an output folder";
        }
        if (!Files.exists(inputFile)) {
            return "Input does not exist: " + inputFile;
        }
        // The primer file is what the run can read, whatever its name, as on the command line.
        if (!Files.isRegularFile(primerFile)) {
            return "Primer file does not exist or is not a regular file: " + primerFile;
        }
        try {
            PrimerService.read(primerFile);
        } catch (PipelineException e) {
            return e.getMessage();
        }
        // The run's own rule and words (InputCheck): the window judged files by their content, the run by
        // their names, and each said it its own way. A faulty file among good ones does not stop the run: the
        // run names it, leaves its sample out and analyses the others.
        try {
            String none = InputCheck.noInputFiles(inputFile);
            return none == null ? "" : none;
        } catch (java.io.UncheckedIOException e) {
            throw new PipelineException(e.getMessage(), e.getCause());
        }
    }
}
