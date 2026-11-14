package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.SequenceFileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** The application's checks of its three paths before a run starts; the run checks them again. */
public final class InputValidator {
    private InputValidator() {}

    /** What is wrong with the paths, or an empty string. */
    public static String validate(Path inputFile, Path outDir, Path primerFile) {
        if (inputFile == null || outDir == null || primerFile == null) {
            return "Please enter an input file, a reference file, and an output file";
        }
        if ((!Files.isDirectory(inputFile) && !SequenceFileUtils.looksLikeSequenceFile(inputFile))
                || !SequenceFileUtils.looksLikeSequenceFile(primerFile)) {
            return "Input and primer files must be in fasta format (or fastq for input)";
        }
        try {
            PrimerService.read(primerFile);
        } catch (PipelineException e) {
            return e.getMessage();
        }
        if (Files.isDirectory(inputFile) && !containsSequenceFile(inputFile)) {
            // A faulty file among good ones does not stop the run: the run names it, leaves
            // its sample out and analyses the others (InputCheck).
            return "Input directory must contain at least one valid fastq/fasta file";
        }
        return "";
    }

    private static boolean containsSequenceFile(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(SequenceFileUtils::looksLikeSequenceFile);
        } catch (IOException e) {
            throw new PipelineException("Unable to list input directory: " + directory, e);
        }
    }
}
