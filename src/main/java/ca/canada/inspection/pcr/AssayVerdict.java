package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.FastqDiagnostics.Finding;

import java.util.OptionalInt;
import java.util.Set;

/**
 * What a sample shows for an assay, all things considered: its products and their sizes, and
 * without any, what the reads or the assembly say. The one place this is decided; the HTML
 * report draws it.
 *
 * @param kind what the sample shows
 * @param note why, when that needs saying; empty otherwise
 */
public record AssayVerdict(Kind kind, String note) {

    /** What a sample shows for an assay. */
    public enum Kind {
        /** A product of the size the assay declares as its target. */
        TARGET,
        /** A product of an assay that declares no size: amplified, size left to the reader. */
        AMPLIFIED,
        /** Only a product the assay is known to give besides its target. */
        OTHER,
        /** Only a product of a size the primer file does not declare. */
        UNEXPECTED,
        /** A complete amplicon in too few of the reads to be reported (FASTQ input). */
        INCONCLUSIVE,
        /** Extended reads join the primers, but read pairs do not confirm the product (FASTQ input). */
        UNCONFIRMED,
        /** No product on any one contig, but the primers point off contig ends (FASTA input). */
        ACROSS_ENDS,
        /** No product, and the reads could not have shown one of the declared size (FASTQ input). */
        UNCHECKED,
        /** No product. */
        NONE
    }

    /**
     * @param products how many products the sample has for the assay
     * @param labels   what their sizes are for the assay ({@link ExpectedSizes#label}), those that
     *                 have one; empty when the assay declares no size
     * @param finding  what the reads say (FASTQ input), or {@code null}
     * @param split    where a product could span contigs (FASTA input), or {@code null}
     */
    public static AssayVerdict of(String assay, int products, Set<ExpectedSizes.Label> labels, ExpectedSizes sizes,
                                  Finding finding, SplitAcrossContigs.Split split) {
        if (products > 0) {
            if (labels.isEmpty()) {
                return new AssayVerdict(Kind.AMPLIFIED, "");
            }
            if (labels.contains(ExpectedSizes.Label.TARGET)) {
                return new AssayVerdict(Kind.TARGET, "");
            }
            return new AssayVerdict(labels.contains(ExpectedSizes.Label.OTHER) ? Kind.OTHER : Kind.UNEXPECTED, "");
        }
        if (split != null) {
            return new AssayVerdict(Kind.ACROSS_ENDS, split.note() + " Check with a more complete assembly.");
        }
        if (finding == null) {
            return new AssayVerdict(Kind.NONE, "");
        }
        return switch (finding.status()) {
            case WEAK_READ_SUPPORT -> new AssayVerdict(Kind.INCONCLUSIVE, "Not reported: " + finding.readDetail());
            case EXTENSION_NOT_SUPPORTED_BY_PAIRS, EXTENSION_THROUGH_UNRESOLVED_REPEAT ->
                    new AssayVerdict(Kind.UNCONFIRMED, "Not reported: " + finding.readDetail());
            // The reads were extended, so the product's length is no longer what hides it.
            case NOT_CLOSED_BY_EXTENSION ->
                    new AssayVerdict(Kind.NONE, "Both primers are in the reads: " + finding.readDetail());
            case NOT_IN_READS -> notInReads(assay, sizes, finding);
            default -> new AssayVerdict(Kind.NONE, "");
        };
    }

    /** The declared product may be longer than a read can hold: its absence from reads then proves nothing. */
    private static AssayVerdict notInReads(String assay, ExpectedSizes sizes, Finding finding) {
        OptionalInt target = sizes.smallestTarget(assay);
        if (target.isPresent() && target.getAsInt() > finding.checkableLength()) {
            return new AssayVerdict(Kind.UNCHECKED, "Both primers are in the reads, but the mini-assembly"
                    + " did not join them, and a product of the declared size (" + sizes.describe(assay)
                    + ") is longer than any read could hold (" + finding.checkableLength()
                    + " bp). Confirm on an assembly of the reads.");
        }
        return new AssayVerdict(Kind.NONE, "");
    }
}
