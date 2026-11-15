package ca.canada.inspection.pipeline;

import java.util.List;

/**
 * Tadpole settings for the FASTQ-mode mini-assembly.
 *
 * <p>The goal is a contig spanning a short amplicon, not a parsimonious genome
 * assembly, so the defaults are less conservative than Tadpole's own
 * ({@code mincountseed=3}, {@code mincontig=500}): a contig over a ~100 bp
 * target is often shorter than 500 bp and was silently dropped (#7).</p>
 *
 * @param k            k-mer length
 * @param minCountSeed minimum k-mer count to seed a contig ({@code mincountseed})
 * @param minCoverage  minimum average contig coverage written ({@code mincov})
 * @param minContig    minimum contig length written, in bp ({@code mincontig})
 */
public record TadpoleOptions(int k, int minCountSeed, int minCoverage, int minContig) {
    public static final int DEFAULT_K = 31;
    public static final int DEFAULT_MIN_COUNT_SEED = 2;
    public static final int DEFAULT_MIN_COVERAGE = 1;
    public static final int DEFAULT_MIN_CONTIG = 60;

    public static final TadpoleOptions DEFAULTS =
            new TadpoleOptions(DEFAULT_K, DEFAULT_MIN_COUNT_SEED, DEFAULT_MIN_COVERAGE, DEFAULT_MIN_CONTIG);

    /** The smallest value each setting takes: the one place these limits are, the window's too. */
    public static final int MIN_K = 1;
    public static final int MIN_COUNT_SEED = 1;
    public static final int MIN_COVERAGE = 0;
    public static final int MIN_CONTIG = 1;

    public TadpoleOptions {
        requireAtLeast(k, MIN_K, "k");
        requireAtLeast(minCountSeed, MIN_COUNT_SEED, "minCountSeed");
        requireAtLeast(minCoverage, MIN_COVERAGE, "minCoverage");
        requireAtLeast(minContig, MIN_CONTIG, "minContig");
    }

    /** Tadpole command-line arguments for these settings. */
    public List<String> arguments() {
        return List.of("k=" + k, "mincountseed=" + minCountSeed, "mincov=" + minCoverage, "mincontig=" + minContig);
    }

    private static void requireAtLeast(int value, int minimum, String label) {
        if (value < minimum) {
            throw new IllegalArgumentException(label + " must be >= " + minimum + ", got: " + value);
        }
    }
}
