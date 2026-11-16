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
            return "Please choose an input file or folder, a primer file and an output folder";
        }
        if (!Files.isDirectory(inputFile) && !SequenceFileUtils.looksLikeSequenceFile(inputFile)) {
            return "The input must be a FASTA or FASTQ file, or a folder of them";
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
        if (Files.isDirectory(inputFile) && !containsSequenceFile(inputFile)) {
            // A faulty file among good ones does not stop the run: the run names it, leaves
            // its sample out and analyses the others (InputCheck).
            if (subfolderHoldsSequenceFiles(inputFile)) {
                return "The input folder holds no FASTA/FASTQ file directly, only in subfolders, which are not read:"
                        + " choose one of them, or move their files up into one folder";
            }
            return "Input directory must contain at least one valid fastq/fasta file";
        }
        return "";
    }

    private static boolean subfolderHoldsSequenceFiles(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isDirectory).anyMatch(InputValidator::containsSequenceFile);
        } catch (IOException | RuntimeException e) {
            return false; // only a better message
        }
    }

    private static boolean containsSequenceFile(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(SequenceFileUtils::looksLikeSequenceFile);
        } catch (IOException e) {
            throw new PipelineException("Unable to list input directory: " + directory, e);
        }
    }
}
