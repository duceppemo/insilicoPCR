package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.util.InputCheck;

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
        TadpoleOptions tadpole,
        double readMinFraction,
        ReadExtensionCaller.Mode readExtension,
        InputCheck.Depth inputCheck
) {
    public PipelineConfig {
        input = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        primers = Objects.requireNonNull(primers, "primers").toAbsolutePath().normalize();
        Objects.requireNonNull(ampliconOptions, "ampliconOptions");
        Objects.requireNonNull(tadpole, "tadpole");
        Objects.requireNonNull(readExtension, "readExtension");
        Objects.requireNonNull(inputCheck, "inputCheck");
        ReadLevelCaller.requireFraction(readMinFraction);
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (!(evalue > 0.0)) {
            throw new IllegalArgumentException("evalue must be > 0");
        }
    }

    /** Configuration with the default FASTQ-mode settings. */
    public PipelineConfig(Path input, Path output, Path primers, int threads, double evalue,
                          AmpliconOptions ampliconOptions, boolean gel) {
        this(input, output, primers, threads, evalue, ampliconOptions, gel, TadpoleOptions.DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ReadExtensionCaller.Mode.DEFAULT,
                InputCheck.Depth.FULL);
    }

    /** Configuration with the default read extension and the full input check. */
    public PipelineConfig(Path input, Path output, Path primers, int threads, double evalue,
                          AmpliconOptions ampliconOptions, boolean gel, TadpoleOptions tadpole,
                          double readMinFraction) {
        this(input, output, primers, threads, evalue, ampliconOptions, gel, tadpole, readMinFraction,
                ReadExtensionCaller.Mode.DEFAULT, InputCheck.Depth.FULL);
    }

    /** Allowed primer mismatches; also used as BBDuk {@code hdist} when baiting reads. */
    public int mismatches() {
        return ampliconOptions.maxTotalMismatches();
    }
}
