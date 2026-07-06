package ca.canada.inspection.insilicopcr.pipeline;

import ca.canada.inspection.insilicopcr.Methods;

import java.io.IOException;
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
            try (var files = Files.list(inputFile)) {
                var invalid = files
                        .filter(path -> !Files.isDirectory(path))
                        .filter(path -> path.getFileName() != null)
                        .filter(path -> {
                            var name = path.getFileName().toString();
                            return name.contains(".fasta") || name.contains(".fna") || name.contains(".ffn") || name.contains(".fastq");
                        })
                        .anyMatch(path -> !Methods.verifyFastaFormat(path));
                if (invalid) {
                    return "Input directory contains non-fastq/fasta format files";
                }
            } catch (IOException e) {
                return "Unable to read input directory: " + e.getMessage();
            }
        }
        return "";
    }
}
