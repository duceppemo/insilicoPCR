package ca.canada.inspection.insilicopcr.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record RunDirectories(Path detailedDir, Path consolidatedDir) {
    public static RunDirectories createUnder(Path outputDir) {
        var directories = new RunDirectories(
                outputDir.resolve("detailed_report"),
                outputDir.resolve("consolidated_report")
        );
        try {
            Files.createDirectories(directories.detailedDir());
            Files.createDirectories(directories.consolidatedDir());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create output directories under " + outputDir, e);
        }
        return directories;
    }
}
