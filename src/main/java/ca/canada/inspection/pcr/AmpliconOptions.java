package ca.canada.inspection.pcr;

import ca.canada.inspection.util.Numbers;

/**
 * Parameters controlling which BLAST primer hits are accepted and which
 * forward/reverse hit pairs are reported as amplicons.
 *
 * @param threePrimeWindow      number of bases at the primer 3' end checked separately
 * @param maxThreePrimePenalty  maximum weighted 3' penalty tolerated inside the window
 *                              (see {@link ca.canada.inspection.model.BlastResult#threePrimePenalty(int)})
 * @param minAmpliconLength     minimum reported amplicon length in bp (0 = no limit)
 * @param maxAmpliconLength     maximum reported amplicon length in bp (0 = no limit)
 * @param tmGate                the Tm rule a primer or probe site must pass ({@code --anneal-temp}, since 2.0)
 */
public record AmpliconOptions(
        int threePrimeWindow,
        int maxThreePrimePenalty,
        int minAmpliconLength,
        int maxAmpliconLength,
        TmGate tmGate
) {
    public static final int DEFAULT_THREE_PRIME_WINDOW = 5;
    public static final int DEFAULT_THREE_PRIME_PENALTY = 0;
    /**
     * The 5' bases of a primer whose differences from the template are reported as unpaired 5' bases, not as
     * mismatches (the {@code 5PrimeUnpaired} columns): BLAST trims terminal mismatches, so a single SNP near the 5'
     * end shows up as several unaligned bases. They do not decide whether a site is accepted, its Tm does; the FASTQ
     * bait leaves them out of its k-mers. The 1.x option {@code --5p-truncation}, at its default.
     */
    public static final int FIVE_PRIME_FREE = 5;
    public static final int DEFAULT_MIN_AMPLICON_LENGTH = 0;
    public static final int DEFAULT_MAX_AMPLICON_LENGTH = 5_000;

    public AmpliconOptions {
        // Named as the command line names them: the window's dialogs check their values first.
        Numbers.requireNonNegative(threePrimeWindow, "--3p-window");
        Numbers.requireNonNegative(maxThreePrimePenalty, "--3p-penalty");
        Numbers.requireNonNegative(minAmpliconLength, "--min-length");
        Numbers.requireNonNegative(maxAmpliconLength, "-l (--max-length)");
        if (!lengthsInOrder(minAmpliconLength, maxAmpliconLength)) {
            throw new IllegalArgumentException("--min-length (" + minAmpliconLength + ") is larger than -l"
                    + " (--max-length, " + maxAmpliconLength + "): no product could be reported");
        }
        if (tmGate == null) {
            throw new IllegalArgumentException("The Tm rule (--anneal-temp, --tm-margin) must be given");
        }
    }

    /** These options with the Tm gate {@code gate}. */
    public AmpliconOptions withTmGate(TmGate gate) {
        return new AmpliconOptions(threePrimeWindow, maxThreePrimePenalty, minAmpliconLength, maxAmpliconLength, gate);
    }

    /** Whether a product length range is one: the minimum at most the maximum, 0 being no maximum. */
    public static boolean lengthsInOrder(int minLength, int maxLength) {
        return maxLength <= 0 || minLength <= maxLength;
    }

    /** The defaults of the command line and the application. */
    public static AmpliconOptions defaults() {
        return builder().build();
    }

    /** Options starting from the defaults, each set by name: ints by position are easily swapped. */
    public static Builder builder() {
        return new Builder();
    }

    /** The one place the defaults are applied, for the command line and the application alike. */
    public static final class Builder {
        private int threePrimeWindow = DEFAULT_THREE_PRIME_WINDOW;
        private int threePrimePenalty = DEFAULT_THREE_PRIME_PENALTY;
        private int minLength = DEFAULT_MIN_AMPLICON_LENGTH;
        private int maxLength = DEFAULT_MAX_AMPLICON_LENGTH;
        private TmGate tmGate = TmGate.DEFAULT;

        private Builder() {
        }

        public Builder threePrimeWindow(int threePrimeWindow) {
            this.threePrimeWindow = threePrimeWindow;
            return this;
        }

        public Builder threePrimePenalty(int threePrimePenalty) {
            this.threePrimePenalty = threePrimePenalty;
            return this;
        }

        public Builder minLength(int minLength) {
            this.minLength = minLength;
            return this;
        }

        /** 0: no limit. */
        public Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        /** Default: {@link TmGate#DEFAULT}. */
        public Builder tmGate(TmGate tmGate) {
            this.tmGate = tmGate;
            return this;
        }

        /** @throws IllegalArgumentException for a negative value, or a minimum above the maximum */
        public AmpliconOptions build() {
            return new AmpliconOptions(threePrimeWindow, threePrimePenalty, minLength, maxLength, tmGate);
        }
    }
}
