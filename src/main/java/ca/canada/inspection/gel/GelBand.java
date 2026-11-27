package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.ReportRow;

/**
 * Metadata for one predicted PCR amplicon displayed as a synthetic gel band.
 *
 * @param sizeCheck what its size is for its assay, as the report's {@value ExpectedSizes#COLUMN}
 *                  column says; {@code null} when the assay declares no size, or the report has
 *                  no such column
 * @param row       the report's row of the amplicon: how its oligos bind and what it was called on;
 *                  {@code null} for a band made without one
 */
public record GelBand(
        String sampleName,
        String geneName,
        int ampliconSize,
        ExpectedSizes.Label sizeCheck,
        ReportRow.Pairing pairing,
        ReportRow row
) {
    public GelBand {
        sampleName = clean(sampleName, "Unknown sample");
        geneName = clean(geneName, "Unknown gene");
        if (ampliconSize < 0) {
            throw new IllegalArgumentException("ampliconSize must be >= 0");
        }
        pairing = pairing == null ? ReportRow.Pairing.DESIGNED : pairing;
    }

    /** The band of a report's row, its size checked as {@code sizeCheck} says. */
    public static GelBand of(ReportRow row, ExpectedSizes.Label sizeCheck) {
        return new GelBand(row.sample(), row.assay(), row.size(), sizeCheck, row.pairing(), row);
    }

    /** An amplicon of its assay's forward and reverse primer, without its report row. */
    public GelBand(String sampleName, String geneName, int ampliconSize, ExpectedSizes.Label sizeCheck) {
        this(sampleName, geneName, ampliconSize, sizeCheck, ReportRow.Pairing.DESIGNED, null);
    }

    /**
     * What made it, as the report tags it: "one primer" (one primer alone, at an inverted repeat), "two assays"
     * (primers of two assays sharing a reaction), or empty for an assay's own pair.
     */
    String pairingTag() {
        return switch (pairing) {
            case DESIGNED -> "";
            case ONE_PRIMER -> "one primer";
            case TWO_ASSAYS -> "two assays";
        };
    }

    /** An amplicon whose size is not checked against a declared one. */
    public GelBand(String sampleName, String geneName, int ampliconSize) {
        this(sampleName, geneName, ampliconSize, null);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
