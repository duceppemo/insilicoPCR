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
        if (output.equals(input)) {
            // The run writes primer_tmp.fasta there before it lists the input: it would be a sample.
            throw new IllegalArgumentException("The output folder must not be the input folder: " + output);
        }
    }

    /** The blastn e-value both front-ends start from, as the application's field shows it. */
    public static final String DEFAULT_EVALUE_TEXT = "1e5";
    /** Every read of every FASTQ file, as the application always does. */
    public static final InputCheck.Depth DEFAULT_INPUT_CHECK = InputCheck.Depth.FULL;
    /**
     * Deliberately permissive: primer hits are filtered by the mismatch and 3'/5'-end rules
     * instead, and a short primer's e-value grows with contig length.
     */
    public static final double DEFAULT_EVALUE = Double.parseDouble(DEFAULT_EVALUE_TEXT);

    /**
     * A run of {@code input} with {@code primers} into {@code output}, every other setting at
     * its default until set: the one place the defaults of a run are, for the command line,
     * the application and the tests.
     */
    public static Builder builder(Path input, Path output, Path primers) {
        return new Builder(input, output, primers);
    }

    /** Settings of a run, at their defaults until set; {@link #build()} checks them. */
    public static final class Builder {
        private final Path input;
        private final Path output;
        private final Path primers;
        private int threads = Runtime.getRuntime().availableProcessors();
        private double evalue = DEFAULT_EVALUE;
        private AmpliconOptions ampliconOptions = AmpliconOptions.builder().build();
        private boolean gel;
        private TadpoleOptions tadpole = TadpoleOptions.DEFAULTS;
        private double readMinFraction = ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION;
        private ReadExtensionCaller.Mode readExtension = ReadExtensionCaller.Mode.DEFAULT;
        private InputCheck.Depth inputCheck = DEFAULT_INPUT_CHECK;

        private Builder(Path input, Path output, Path primers) {
            this.input = input;
            this.output = output;
            this.primers = primers;
        }

        /** Default: every processor. */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /** Default: {@link #DEFAULT_EVALUE}. */
        public Builder evalue(double evalue) {
            this.evalue = evalue;
            return this;
        }

        /** Default: no mismatch, and the defaults of {@link AmpliconOptions}. */
        public Builder ampliconOptions(AmpliconOptions ampliconOptions) {
            this.ampliconOptions = ampliconOptions;
            return this;
        }

        /** Whether to write the {@code --gel} PNG; default: no. */
        public Builder gel(boolean gel) {
            this.gel = gel;
            return this;
        }

        public Builder tadpole(TadpoleOptions tadpole) {
            this.tadpole = tadpole;
            return this;
        }

        public Builder readMinFraction(double readMinFraction) {
            this.readMinFraction = readMinFraction;
            return this;
        }

        public Builder readExtension(ReadExtensionCaller.Mode readExtension) {
            this.readExtension = readExtension;
            return this;
        }

        /** Default: {@link InputCheck.Depth#FULL}. */
        public Builder inputCheck(InputCheck.Depth inputCheck) {
            this.inputCheck = inputCheck;
            return this;
        }

        /** @throws IllegalArgumentException when a setting is out of range */
        public PipelineConfig build() {
            return new PipelineConfig(input, output, primers, threads, evalue, ampliconOptions, gel, tadpole,
                    readMinFraction, readExtension, inputCheck);
        }
    }

    /** Allowed primer mismatches; also used as BBDuk {@code hdist} when baiting reads. */
    public int mismatches() {
        return ampliconOptions.maxTotalMismatches();
    }
}
