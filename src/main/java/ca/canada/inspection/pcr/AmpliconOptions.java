package ca.canada.inspection.pcr;

/**
 * Parameters controlling which BLAST primer hits are accepted and which
 * forward/reverse hit pairs are reported as amplicons.
 *
 * @param maxTotalMismatches    maximum mismatches + indels over the whole primer, counting the 3' bases BLAST
 *                              trimmed off its alignment that do not pair with the template
 * @param maxProbeMismatches    maximum mismatches + indels over a hydrolysis probe (probes are longer
 *                              and bind hotter than primers, so they tolerate more)
 * @param threePrimeWindow      number of bases at the primer 3' end checked separately
 * @param maxThreePrimePenalty  maximum weighted 3' penalty tolerated inside the window
 *                              (see {@link ca.canada.inspection.model.BlastResult#threePrimePenalty(int)})
 * @param maxFivePrimeTruncation maximum unaligned bases tolerated at the primer 5' end
 * @param minAmpliconLength     minimum reported amplicon length in bp (0 = no limit)
 * @param maxAmpliconLength     maximum reported amplicon length in bp (0 = no limit)
 */
public record AmpliconOptions(
        int maxTotalMismatches,
        int maxProbeMismatches,
        int threePrimeWindow,
        int maxThreePrimePenalty,
        int maxFivePrimeTruncation,
        int minAmpliconLength,
        int maxAmpliconLength
) {
    public static final int DEFAULT_MISMATCHES = 0;
    public static final int DEFAULT_THREE_PRIME_WINDOW = 5;
    public static final int DEFAULT_THREE_PRIME_PENALTY = 0;
    /**
     * BLAST trims terminal mismatches, so a single SNP near the primer 5' end
     * shows up as several unaligned 5' bases. Real PCR tolerates 5'-end
     * mismatches well (the 3' end does the priming), so the default allowance
     * is generous.
     */
    public static final int DEFAULT_FIVE_PRIME_TRUNCATION = 5;
    public static final int DEFAULT_MIN_AMPLICON_LENGTH = 0;
    public static final int DEFAULT_MAX_AMPLICON_LENGTH = 5_000;

    public AmpliconOptions {
        // Named as the command line names them: the window's dialogs check their values first.
        requireNonNegative(maxTotalMismatches, "-m (mismatches)");
        requireNonNegative(maxProbeMismatches, "--probe-mismatches");
        requireNonNegative(threePrimeWindow, "--3p-window");
        requireNonNegative(maxThreePrimePenalty, "--3p-penalty");
        requireNonNegative(maxFivePrimeTruncation, "--5p-truncation");
        requireNonNegative(minAmpliconLength, "--min-length");
        requireNonNegative(maxAmpliconLength, "-l (--max-length)");
        if (!lengthsInOrder(minAmpliconLength, maxAmpliconLength)) {
            throw new IllegalArgumentException("--min-length (" + minAmpliconLength + ") is larger than -l"
                    + " (--max-length, " + maxAmpliconLength + "): no product could be reported");
        }
    }

    /** Whether a product length range is one: the minimum at most the maximum, 0 being no maximum. */
    public static boolean lengthsInOrder(int minLength, int maxLength) {
        return maxLength <= 0 || minLength <= maxLength;
    }

    /** Default options with the given number of allowed total mismatches. */
    public static AmpliconOptions withMismatches(int maxTotalMismatches) {
        return builder().mismatches(maxTotalMismatches).build();
    }

    /** Options starting from the defaults, each set by name: seven ints by position are easily swapped. */
    public static Builder builder() {
        return new Builder();
    }

    /** The one place the defaults are applied, for the command line and the application alike. */
    public static final class Builder {
        private int mismatches = DEFAULT_MISMATCHES;
        private Integer probeMismatches;
        private int threePrimeWindow = DEFAULT_THREE_PRIME_WINDOW;
        private int threePrimePenalty = DEFAULT_THREE_PRIME_PENALTY;
        private int fivePrimeTruncation = DEFAULT_FIVE_PRIME_TRUNCATION;
        private int minLength = DEFAULT_MIN_AMPLICON_LENGTH;
        private int maxLength = DEFAULT_MAX_AMPLICON_LENGTH;

        private Builder() {
        }

        public Builder mismatches(int mismatches) {
            this.mismatches = mismatches;
            return this;
        }

        /** Default: the primers' mismatches. */
        public Builder probeMismatches(int probeMismatches) {
            this.probeMismatches = probeMismatches;
            return this;
        }

        public Builder threePrimeWindow(int threePrimeWindow) {
            this.threePrimeWindow = threePrimeWindow;
            return this;
        }

        public Builder threePrimePenalty(int threePrimePenalty) {
            this.threePrimePenalty = threePrimePenalty;
            return this;
        }

        public Builder fivePrimeTruncation(int fivePrimeTruncation) {
            this.fivePrimeTruncation = fivePrimeTruncation;
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

        /** @throws IllegalArgumentException for a negative value, or a minimum above the maximum */
        public AmpliconOptions build() {
            return new AmpliconOptions(mismatches, probeMismatches == null ? mismatches : probeMismatches,
                    threePrimeWindow, threePrimePenalty, fivePrimeTruncation, minLength, maxLength);
        }
    }

    private static void requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be >= 0, got: " + value);
        }
    }
}
