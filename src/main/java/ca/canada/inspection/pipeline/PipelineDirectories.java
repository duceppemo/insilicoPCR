package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record PipelineDirectories(Path output, Path detailed, Path consolidated) {
    public static PipelineDirectories create(Path output) {
        PipelineDirectories directories = new PipelineDirectories(
                output,
                output.resolve("detailed_report"),
                output.resolve("consolidated_report")
        );
        directories.createAll();
        return directories;
    }

    public Path primerDatabase() {
        return output.resolve("primer_tmp.fasta");
    }

    public void createSampleDirectory(String sampleName) {
        createDirectory(detailed.resolve(sampleName));
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
