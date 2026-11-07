package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable runtime-independent pipeline configuration. */
public record PipelineConfig(
        Path input,
        Path output,
        Path primers,
        int threads,
        double evalue,
        AmpliconOptions ampliconOptions,
        boolean gel,
        TadpoleOptions tadpole
) {
    public PipelineConfig {
        input = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        primers = Objects.requireNonNull(primers, "primers").toAbsolutePath().normalize();
        Objects.requireNonNull(ampliconOptions, "ampliconOptions");
        Objects.requireNonNull(tadpole, "tadpole");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (!(evalue > 0.0)) {
            throw new IllegalArgumentException("evalue must be > 0");
        }
    }

    /** Configuration with the default Tadpole settings. */
    public PipelineConfig(Path input, Path output, Path primers, int threads, double evalue,
                          AmpliconOptions ampliconOptions, boolean gel) {
        this(input, output, primers, threads, evalue, ampliconOptions, gel, TadpoleOptions.DEFAULTS);
    }

    /** Allowed primer mismatches; also used as BBDuk {@code hdist} when baiting reads. */
    public int mismatches() {
        return ampliconOptions.maxTotalMismatches();
    }
}
