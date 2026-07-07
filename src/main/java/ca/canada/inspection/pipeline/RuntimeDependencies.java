package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;

import java.nio.file.Path;

public record RuntimeDependencies(AppPaths.RuntimeLayout layout) {
    public static RuntimeDependencies discover() {
        try {
            return new RuntimeDependencies(AppPaths.discover());
        } catch (RuntimeException e) {
            throw new DependencyException("Unable to discover bundled runtime dependencies", e);
        }
    }

    public Path appRoot() {
        return layout.appRoot();
    }

    public Path bbmapDirectory() {
        return layout.bbmapDirectory();
    }

    public Path blastBinDirectory() {
        return layout.blastBinDirectory();
    }

    public String javaCommand() {
        return layout.javaCommand();
    }

    public Path blastn() {
        return layout.blastnExecutable();
    }

    public Path makeblastdb() {
        return layout.makeblastdbExecutable();
    }
}
