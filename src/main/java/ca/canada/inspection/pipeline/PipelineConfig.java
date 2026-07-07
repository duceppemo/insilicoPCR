package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable runtime-independent pipeline configuration. */
public record PipelineConfig(
        Path input,
        Path output,
        Path primers,
        int threads,
        int mismatches,
        double evalue
) {
    public PipelineConfig {
        input = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        primers = Objects.requireNonNull(primers, "primers").toAbsolutePath().normalize();
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (mismatches < 0) {
            throw new IllegalArgumentException("mismatches must be >= 0");
        }
        if (!(evalue > 0.0)) {
            throw new IllegalArgumentException("evalue must be > 0");
        }
    }
}
