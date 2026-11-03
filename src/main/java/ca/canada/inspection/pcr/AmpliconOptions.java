package ca.canada.inspection.pcr;

/**
 * Parameters controlling which BLAST primer hits are accepted and which
 * forward/reverse hit pairs are reported as amplicons.
 *
 * @param maxTotalMismatches    maximum mismatches + indels + unaligned 3' bases over the whole primer
 * @param threePrimeWindow      number of bases at the primer 3' end checked separately
 * @param maxThreePrimePenalty  maximum weighted 3' penalty tolerated inside the window
 *                              (see {@link ca.canada.inspection.insilicopcr.BlastResult#threePrimePenalty(int)})
 * @param maxFivePrimeTruncation maximum unaligned bases tolerated at the primer 5' end
 * @param minAmpliconLength     minimum reported amplicon length in bp (0 = no limit)
 * @param maxAmpliconLength     maximum reported amplicon length in bp (0 = no limit)
 */
public record AmpliconOptions(
        int maxTotalMismatches,
        int threePrimeWindow,
        int maxThreePrimePenalty,
        int maxFivePrimeTruncation,
        int minAmpliconLength,
        int maxAmpliconLength
) {
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
        requireNonNegative(maxTotalMismatches, "maxTotalMismatches");
        requireNonNegative(threePrimeWindow, "threePrimeWindow");
        requireNonNegative(maxThreePrimePenalty, "maxThreePrimePenalty");
        requireNonNegative(maxFivePrimeTruncation, "maxFivePrimeTruncation");
        requireNonNegative(minAmpliconLength, "minAmpliconLength");
        requireNonNegative(maxAmpliconLength, "maxAmpliconLength");
        if (maxAmpliconLength > 0 && minAmpliconLength > maxAmpliconLength) {
            throw new IllegalArgumentException(
                    "minAmpliconLength (" + minAmpliconLength + ") is larger than maxAmpliconLength (" + maxAmpliconLength + ")");
        }
    }

    /** Default options with the given number of allowed total mismatches. */
    public static AmpliconOptions withMismatches(int maxTotalMismatches) {
        return new AmpliconOptions(
                maxTotalMismatches,
                DEFAULT_THREE_PRIME_WINDOW,
                DEFAULT_THREE_PRIME_PENALTY,
                DEFAULT_FIVE_PRIME_TRUNCATION,
                DEFAULT_MIN_AMPLICON_LENGTH,
                DEFAULT_MAX_AMPLICON_LENGTH
        );
    }

    private static void requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be >= 0, got: " + value);
        }
    }
}
