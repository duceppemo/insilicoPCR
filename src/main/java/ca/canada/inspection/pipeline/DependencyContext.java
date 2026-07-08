package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.insilicopcr.Methods;
import javafx.scene.control.TextArea;
import java.nio.file.Files;
import java.nio.file.Path;

public record DependencyContext(
        Path bbtoolsLocation,
        Path blastLocation,
        Path javaLocation,
        String javaCommand,
        AppPaths.RuntimeLayout layout
) {
    public static DependencyContext discover(TextArea outputField) {
        var layout = AppPaths.discover();
        var context = new DependencyContext(
                layout.bbmapDirectory(),
                layout.blastBinDirectory(),
                layout.javaExecutable().getParent(),
                layout.javaCommand(),
                layout
        );
        Methods.logMessage(outputField, "Application root: " + layout.appRoot());
        Methods.logMessage(outputField, "BBMap: " + context.bbtoolsLocation());
        Methods.logMessage(outputField, "BLAST: " + context.blastLocation());
        Methods.logMessage(outputField, "Java: " + context.javaCommand());
        return context;
    }

    public Path bbmapClasspath() {
        Path jar = bbtoolsLocation().resolve("bbtools.jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }

        throw new IllegalStateException(
                "Unable to find BBMap classpath. Expected bbtools.jar or current/ under "
                        + bbtoolsLocation()
        );
    }

    public Path bbtoolsJar() {
        Path jar = bbtoolsLocation().resolve("bbtools.jar");

        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "Missing BBMap runtime: " + jar.toAbsolutePath()
            );
        }

        return jar;
    }
}
