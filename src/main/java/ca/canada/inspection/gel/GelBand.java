package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ExpectedSizes;

/**
 * Metadata for one predicted PCR amplicon displayed as a synthetic gel band.
 *
 * @param sizeCheck what its size is for its assay, as the report's {@value ExpectedSizes#COLUMN}
 *                  column says; {@code null} when the assay declares no size, or the report has
 *                  no such column
 */
public record GelBand(
        String sampleName,
        String geneName,
        int ampliconSize,
        ExpectedSizes.Label sizeCheck
) {
    public GelBand {
        sampleName = clean(sampleName, "Unknown sample");
        geneName = clean(geneName, "Unknown gene");
        if (ampliconSize < 0) {
            throw new IllegalArgumentException("ampliconSize must be >= 0");
        }
    }

    /** An amplicon whose size is not checked against a declared one. */
    public GelBand(String sampleName, String geneName, int ampliconSize) {
        this(sampleName, geneName, ampliconSize, null);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
