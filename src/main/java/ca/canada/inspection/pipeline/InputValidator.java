package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Methods;

import java.nio.file.Files;
import java.nio.file.Path;

public final class InputValidator {
    private InputValidator() {}

    public static String validate(Path inputFile, Path outDir, Path primerFile) {
        if (inputFile == null || outDir == null || primerFile == null) {
            return "Please enter an input file, a reference file, and an output file";
        }
        if ((!Files.isDirectory(inputFile) && !Methods.verifyFastaFormat(inputFile)) || !Methods.verifyFastaFormat(primerFile)) {
            return "Input and primer files must be in fasta format (or fastq for input)";
        }
        if (!Methods.verifyPrimerFile(primerFile)) {
            return "A primer file which contains probe primers must also have fwd and rev primers with the same name";
        }
        if (Files.isDirectory(inputFile)) {
            if (Methods.noFastaFile(inputFile)) {
                return "Input directory must contain at least one valid fastq/fasta file";
            }
            // A faulty file among good ones does not stop the run: the run names it, leaves
            // its sample out and analyses the others (InputCheck).
        }
        return "";
    }
}
