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
        InputCheck.Depth inputCheck,
        int bbtoolsMemoryGiB
) {
    /** {@link #bbtoolsMemoryGiB} for the budget {@link ChildJavaMemory} works out from the computer. */
    public static final int AUTOMATIC_BBTOOLS_MEMORY = 0;

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
            throw new IllegalArgumentException("-t (threads) must be at least 1, not " + threads);
        }
        if (bbtoolsMemoryGiB < 0) {
            throw new IllegalArgumentException("--memory must be a number of GB, or 0 for automatic, got: "
                    + bbtoolsMemoryGiB);
        }
        if (!validEvalue(evalue)) {
            // Infinity (or 1e999, which reads as it) would only fail at blastn, hours into a run.
            throw new IllegalArgumentException("evalue must be a number greater than 0, e.g. " + DEFAULT_EVALUE_TEXT
                    + ", got: " + evalue);
        }
        if (sameFolder(output, input)) {
            // The run writes primer_tmp.fasta there before it lists the input: it would be a sample.
            throw new IllegalArgumentException("The output folder must not be the input folder: " + output);
        }
    }

    /**
     * Whether {@code text}, read as {@code value}, is a number above 0 too small for a double: 1e-400 reads as 0,
     * and was told it was not above 0. The command line and the window say so in the same words.
     */
    public static boolean tooSmallForANumber(String text, double value) {
        // Read exactly: -1e-400 is below 0, and 0d or 0x0p0 are 0 (all were told "too small"), whatever Java reads.
        try {
            return value == 0 && new java.math.BigDecimal(text.strip()).signum() > 0;
        } catch (NumberFormatException notDecimal) {
            return false;
        }
    }

    /** What is said of an e-value {@link #tooSmallForANumber}, after the option's or field's name. */
    public static String tooSmallMessage(String text) {
        return text.strip() + " is too small for a number of the program (it reads as 0): give 1e-300 or more";
    }

    /** These settings with {@code options} for the primer sites and products. */
    public PipelineConfig withAmpliconOptions(AmpliconOptions options) {
        return new PipelineConfig(input, output, primers, threads, evalue, options, gel, tadpole, readMinFraction,
                readExtension, inputCheck, bbtoolsMemoryGiB);
    }

    /** Whether blastn takes {@code evalue}: a finite number above 0. */
    public static boolean validEvalue(double evalue) {
        return evalue > 0.0 && Double.isFinite(evalue);
    }

    /** As written, or one folder reached by two paths (a symbolic link). */
    private static boolean sameFolder(Path output, Path input) {
        if (output.equals(input)) {
            return true;
        }
        try {
            return java.nio.file.Files.exists(output) && java.nio.file.Files.exists(input)
                    && java.nio.file.Files.isSameFile(output, input);
        } catch (java.io.IOException e) {
            return false;
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
        // The Tm rule, as on the command line and in the window (since 2.0): mismatch counts are for tests only.
        private AmpliconOptions ampliconOptions = AmpliconOptions.builder().tmGate(ca.canada.inspection.pcr.TmGate.DEFAULT)
                .build();
        private boolean gel;
        private TadpoleOptions tadpole = TadpoleOptions.DEFAULTS;
        private double readMinFraction = ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION;
        private ReadExtensionCaller.Mode readExtension = ReadExtensionCaller.Mode.DEFAULT;
        private InputCheck.Depth inputCheck = DEFAULT_INPUT_CHECK;
        private int bbtoolsMemoryGiB = AUTOMATIC_BBTOOLS_MEMORY;

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

        /**
         * Heap, in GB, that the BBTools programs running at once share (FASTQ mode); default
         * {@link #AUTOMATIC_BBTOOLS_MEMORY}: worked out from the computer.
         */
        public Builder bbtoolsMemory(int gib) {
            this.bbtoolsMemoryGiB = gib;
            return this;
        }

        /** @throws IllegalArgumentException when a setting is out of range */
        public PipelineConfig build() {
            return new PipelineConfig(input, output, primers, threads, evalue, ampliconOptions, gel, tadpole,
                    readMinFraction, readExtension, inputCheck, bbtoolsMemoryGiB);
        }
    }

    /**
     * The edits BBDuk's first bait matches primer k-mers within (at most 2), and, at 3, the substitutions its
     * substitution bait matches 19-base k-mers within: {@link #TM_BAIT_MISMATCHES} with the Tm rule, which counts no
     * mismatch; the allowed primer mismatches without it (tests of the counts of 1.x).
     */
    public int mismatches() {
        return ampliconOptions.tmGated() ? TM_BAIT_MISMATCHES : ampliconOptions.maxTotalMismatches();
    }

    /**
     * The mismatches the FASTQ bait allows with the Tm rule: one in each k-mer of 14 or more bases. A site whose Tm
     * passes has few mismatches, and its 3' end pairs (the 3' rule); the bait takes reads in, the Tm decides.
     */
    public static final int TM_BAIT_MISMATCHES = 1;

    /**
     * The substitutions the FASTQ substitution bait allows in a k-mer ({@link BbtoolsCommands#substitutionBait}), 0 for
     * none: {@link #TM_BAIT_SUBSTITUTIONS} with the Tm rule; without it, {@link BbtoolsCommands#MAX_BAIT_SUBSTITUTIONS}
     * (3) when the allowed primer mismatches exceed the first bait's 2 edits (tests of the counts of 1.x).
     */
    public int baitSubstitutions() {
        if (ampliconOptions.tmGated()) {
            return TM_BAIT_SUBSTITUTIONS;
        }
        int mismatches = ampliconOptions.maxTotalMismatches();
        // Above the 2 edits, the 3 substitutions: no count between them.
        return mismatches > BbtoolsCommands.MAX_BAIT_INDELS ? BbtoolsCommands.MAX_BAIT_SUBSTITUTIONS : 0;
    }

    /**
     * The substitutions the FASTQ substitution bait allows with the Tm rule, in 18-base k-mers (since 2.0), for the
     * oligos up to {@link #TM_BAIT_SUBSTITUTION_MAX_LENGTH} bases. The first bait's single edit misses sites the Tm
     * rule accepts: 2 mismatches in the first 5 bases of an 18-base primer leave neither of its 17-mers within 1 edit.
     */
    public static final int TM_BAIT_SUBSTITUTIONS = 2;

    /**
     * The longest oligo the substitution bait baits with: {@link #TM_BAIT_SUBSTITUTION_MAX_LENGTH} with the Tm rule,
     * any without it.
     */
    public int baitSubstitutionMaxLength() {
        return ampliconOptions.tmGated() ? TM_BAIT_SUBSTITUTION_MAX_LENGTH : Integer.MAX_VALUE;
    }

    /**
     * With the Tm rule, the substitution bait takes the oligos of 18 to 21 bases only: the fewer a primer's k-mers,
     * the more often 1 edit misses its sites, and the 18-mers of longer oligos took in most of the reads. On a
     * potato cyst nematode sample it took the baited pairs from 185,312 to 231,347, where every oligo took them to
     * 588,550 and the run from 310 to 588 s. Of random sites with 2 substitutions that pass the Tm rule, the bait
     * then misses 1.3%, with 3, 13% (every oligo: 0.4% and 7%; 1 edit alone: 7% and 19%).
     */
    public static final int TM_BAIT_SUBSTITUTION_MAX_LENGTH = 21;
}
