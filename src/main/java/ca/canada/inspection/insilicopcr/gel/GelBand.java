package ca.canada.inspection.insilicopcr.gel;

/** Metadata for one predicted PCR amplicon displayed as a synthetic gel band. */
public record GelBand(
        String sampleName,
        String geneName,
        int ampliconSize
) {
    public GelBand {
        sampleName = clean(sampleName, "Unknown sample");
        geneName = clean(geneName, "Unknown gene");
        if (ampliconSize < 0) {
            throw new IllegalArgumentException("ampliconSize must be >= 0");
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
