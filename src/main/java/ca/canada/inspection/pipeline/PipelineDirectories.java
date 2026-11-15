package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record PipelineDirectories(Path output, Path detailed, Path consolidated) {
    /** The folder of a run's consolidated reports, in its output folder. */
    static final String CONSOLIDATED = "consolidated_report";

    public static PipelineDirectories create(Path output) {
        PipelineDirectories directories = new PipelineDirectories(
                output,
                output.resolve("detailed_report"),
                output.resolve(CONSOLIDATED)
        );
        directories.createAll();
        return directories;
    }

    public Path primerDatabase() {
        return output.resolve("primer_tmp.fasta");
    }

    /** The files of sample {@code sampleName}. */
    public SampleFiles sample(String sampleName) {
        return SampleFiles.of(detailed, sampleName);
    }

    /**
     * Makes the folder of sample {@code sampleName}, without the decompressed assembly a run killed
     * while searching it left there ({@code blast_query_*.fasta}, a whole genome).
     */
    public void createSampleDirectory(String sampleName) {
        Path directory = sample(sampleName).directory();
        createDirectory(directory);
        try (java.nio.file.DirectoryStream<Path> left = Files.newDirectoryStream(directory, BlastQuery.LEFT_BEHIND)) {
            for (Path file : left) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // A leftover is only clutter; the search writes a new one under its own name.
        }
    }

    private void createAll() {
        createDirectory(detailed);
        createDirectory(consolidated);
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new PipelineException("Unable to create directory: " + directory, e);
        }
    }
}
