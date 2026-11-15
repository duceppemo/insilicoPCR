package ca.canada.inspection.gel;

import java.util.function.Function;

/** How the bands of a gel are coloured, and so which bands of one size are drawn apart. */
enum GelColorMode {
    /** Black bands, as on a stained gel: amplicons of one size are one band. */
    BLACK("Black", null, band -> ""),
    /** Each band in its assay's colour, with a gene legend. */
    GENE("By gene", "Gene legend", GelBand::geneName),
    /**
     * Each band by what its size is for its assay (the report's {@code SizeCheck}): target,
     * other known product, unexpected, or no size declared; with a legend of these.
     */
    SIZE_CHECK("By size check", "Size check", band -> GelColors.sizeCheckName(band.sizeCheck()));

    private final String label;
    private final String legendTitle;
    private final Function<GelBand, String> group;

    GelColorMode(String label, String legendTitle, Function<GelBand, String> group) {
        this.label = label;
        this.legendTitle = legendTitle;
        this.group = group;
    }

    /** The legend's title; {@code null} for no legend. */
    String legendTitle() {
        return legendTitle;
    }

    /**
     * What sets a band's colour: amplicons of one size that differ in it are drawn as bands
     * apart, so that no colour hides another.
     */
    String group(GelBand band) {
        return group.apply(band);
    }

    @Override
    public String toString() {
        return label;
    }
}
