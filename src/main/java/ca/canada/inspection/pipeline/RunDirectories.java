package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record RunDirectories(Path detailedDir, Path consolidatedDir, Path tempDir) {
    public static RunDirectories createUnder(Path outputDir) {
        var directories = new RunDirectories(
                outputDir.resolve("detailed_report"),
                outputDir.resolve("consolidated_report"),
                outputDir.resolve(".tmp")
        );
        try {
            Files.createDirectories(directories.detailedDir());
            Files.createDirectories(directories.consolidatedDir());
            Files.createDirectories(directories.tempDir());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create output directories under " + outputDir, e);
        }
        return directories;
    }
}
