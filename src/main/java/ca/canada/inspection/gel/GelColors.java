package ca.canada.inspection.gel;

import javafx.scene.paint.Color;

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

    /** Band opacity as a function of how many amplicons share the band. */
    public static double bandIntensity(int bandCount) {
        return Math.min(1.0, 0.45 + Math.max(0, bandCount - 1) * 0.12);
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
