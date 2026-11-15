package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ExpectedSizes;
import javafx.scene.paint.Color;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared gene color palette for the interactive gel viewer and the PNG export. */
public final class GelColors {

    /** Colorblind-aware categorical palette (matplotlib "tab10"). */
    public static final Color[] PALETTE = {
            Color.rgb(31, 119, 180), Color.rgb(214, 39, 40), Color.rgb(44, 160, 44),
            Color.rgb(255, 127, 14), Color.rgb(148, 103, 189), Color.rgb(23, 190, 207),
            Color.rgb(140, 86, 75), Color.rgb(227, 119, 194), Color.rgb(188, 189, 34),
            Color.rgb(127, 127, 127)
    };

    private GelColors() {
    }

    /**
     * The colour of the {@code index}-th gene: the palette's, then further hues spread by the golden
     * angle, so that genes past the tenth do not share a colour (and a legend entry) with another.
     */
    static Color color(int index) {
        if (index < PALETTE.length) {
            return PALETTE[index];
        }
        return Color.hsb((index * 137.508) % 360, 0.65, 0.80);
    }

    /**
     * The colours of the size check, as the HTML report shows it: target green, another known
     * product amber, unexpected red, and blue for an assay that declares no size.
     */
    private static final Map<ExpectedSizes.Label, Color> SIZE_CHECK = new EnumMap<>(Map.of(
            ExpectedSizes.Label.TARGET, Color.web("#008300"),
            ExpectedSizes.Label.OTHER, Color.web("#fab219"),
            ExpectedSizes.Label.UNEXPECTED, Color.web("#d03b3b")));
    private static final Color NO_SIZE_DECLARED = Color.web("#2a78d6");
    private static final String NO_SIZE_DECLARED_NAME = "No size declared";

    /** The name of a size check in the legend and the band text. */
    static String sizeCheckName(ExpectedSizes.Label label) {
        if (label == null) {
            return NO_SIZE_DECLARED_NAME;
        }
        return switch (label) {
            case TARGET -> "Target size";
            case OTHER -> "Other known product";
            case UNEXPECTED -> "Unexpected size";
        };
    }

    /** The colour of a band of this size check. */
    static Color sizeCheck(ExpectedSizes.Label label) {
        return label == null ? NO_SIZE_DECLARED : SIZE_CHECK.get(label);
    }

    /** The size checks of {@code bands} by name, with their colours: the legend, in a fixed order. */
    static Map<String, Color> sizeCheckLegend(Collection<GelBand> bands) {
        Map<String, Color> legend = new LinkedHashMap<>();
        for (ExpectedSizes.Label label : ExpectedSizes.Label.values()) {
            if (bands.stream().anyMatch(band -> band.sizeCheck() == label)) {
                legend.put(sizeCheckName(label), sizeCheck(label));
            }
        }
        if (bands.stream().anyMatch(band -> band.sizeCheck() == null)) {
            legend.put(NO_SIZE_DECLARED_NAME, NO_SIZE_DECLARED);
        }
        return legend;
    }

    /**
     * The colour of a band drawn in {@code mode}, or {@code null} for black.
     *
     * @param band       one of the band's amplicons: they all share what colours it
     * @param geneColors the run's colour of each gene
     */
    static Color of(GelColorMode mode, GelBand band, Map<String, Color> geneColors) {
        return switch (mode) {
            case BLACK -> null;
            case GENE -> geneColors.getOrDefault(band.geneName(), Color.BLACK);
            case SIZE_CHECK -> sizeCheck(band.sizeCheck());
        };
    }

    /** The legend of {@code bands} drawn in {@code mode}; empty for black bands. */
    static Map<String, Color> legend(GelColorMode mode, Collection<GelBand> bands, Map<String, Color> geneColors) {
        Map<String, Color> legend = new LinkedHashMap<>();
        switch (mode) {
            case BLACK -> {
            }
            case GENE -> geneColors.forEach((gene, color) -> {
                if (bands.stream().anyMatch(band -> band.geneName().equals(gene))) {
                    legend.put(gene, color);
                }
            });
            case SIZE_CHECK -> legend.putAll(sizeCheckLegend(bands));
        }
        return legend;
    }

    /** Assigns a stable palette color to each gene, in first-appearance order across lanes. */
    public static Map<String, Color> assignGeneColors(Map<String, List<GelBand>> lanes) {
        Map<String, Color> colors = new LinkedHashMap<>();
        for (List<GelBand> bands : lanes.values()) {
            for (GelBand band : bands) {
                colors.computeIfAbsent(band.geneName(), gene -> color(colors.size()));
            }
        }
        return colors;
    }
}
