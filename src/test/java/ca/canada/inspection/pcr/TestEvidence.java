package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadSequences;

import java.util.Map;

/**
 * Read-level evidence for tests, <b>without the locus check</b> ({@link ReadSequences#NONE}: every
 * read carrying a primer counts as coming from the product's locus) unless sequences are given.
 * A run always checks the locus; these are for tests of the other rules.
 */
public final class TestEvidence {

    private TestEvidence() {
    }

    /** Every read its own fragment, the default support fraction. */
    public static ReadEvidence evaluate(Sample reads, Map<String, Integer> readLengths, PrimerPanel primers,
                                        AmpliconOptions options) {
        return evaluate(reads, readLengths, Map.of(), primers, options, ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION);
    }

    /** Every read its own fragment. */
    public static ReadEvidence evaluate(Sample reads, Map<String, Integer> readLengths, PrimerPanel primers,
                                        AmpliconOptions options, double minSupportFraction) {
        return evaluate(reads, readLengths, Map.of(), primers, options, minSupportFraction);
    }

    public static ReadEvidence evaluate(Sample reads, Map<String, Integer> readLengths, Map<String, String> fragments,
                                        PrimerPanel primers, AmpliconOptions options, double minSupportFraction) {
        return ReadLevelCaller.evaluate(reads.getName(), reads.hits(), readLengths, fragments, primers, options, minSupportFraction,
                ReadSequences.NONE);
    }
}
