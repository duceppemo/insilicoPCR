package ca.canada.inspection.insilicopcr.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.insilicopcr.Methods;
import javafx.scene.control.TextArea;

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
}
