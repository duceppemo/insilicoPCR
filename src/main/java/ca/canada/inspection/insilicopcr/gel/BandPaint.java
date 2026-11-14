package ca.canada.inspection.insilicopcr.gel;

import javafx.scene.paint.Color;

/** Colours of the gel background and of the bands, which mimic a photographed gel. */
final class BandPaint {
    static final Color GEL = Color.rgb(238, 238, 238);
    static final Color LIGHT_TEXTURE = Color.rgb(246, 246, 246, 0.45);
    static final Color DARK_TEXTURE = Color.rgb(222, 222, 222, 0.25);
    static final Color HIT_BOX = Color.rgb(255, 255, 255, 0.01);
    static final Color HOVER = Color.rgb(30, 144, 255);
    static final Color SELECTED = Color.rgb(255, 140, 0);
    static final Color SEARCH_MATCH = Color.rgb(60, 180, 75);
    static final Color BAND = Color.rgb(8, 8, 8);

    private BandPaint() {
    }

    /** Lanes alternate between two light greys; lane 0 is the ladder. */
    static Color laneFill(int lane) {
        return lane % 2 == 0 ? Color.rgb(244, 244, 244) : Color.rgb(232, 232, 232);
    }

    /** Soft halo around a band, so thin bands stay visible. */
    static Color halo(double intensity) {
        return Color.rgb(25, 25, 25, intensity * 0.20);
    }

    /** Light line along the top of a band. */
    static Color highlight(double intensity) {
        return Color.rgb(255, 255, 255, Math.min(0.18, intensity * 0.14));
    }

    /** Dark line along the bottom of a band. */
    static Color shadow(double intensity) {
        return Color.rgb(0, 0, 0, Math.min(0.32, intensity * 0.24));
    }

    /** {@code color} with the band's intensity as its opacity. */
    static Color withOpacity(Color color, double intensity) {
        return Color.color(color.getRed(), color.getGreen(), color.getBlue(), intensity);
    }

    /** Halo of a band in the PNG image, a darker shade of the gene colour. */
    static Color imageHalo(Color color, double intensity) {
        return color.deriveColor(0, 1.0, 0.35, intensity * 0.22);
    }

    /** Body of a band in the PNG image; higher intensity is more opaque. */
    static Color imageBody(Color color, double intensity) {
        return color.deriveColor(0, 1.0, 0.75, Math.min(1.0, 0.55 + intensity * 0.45));
    }

    /** Dark line along the bottom of a band in the PNG image. */
    static Color imageShadow(Color color, double intensity) {
        return color.deriveColor(0, 1.0, 0.25, Math.min(0.32, intensity * 0.24));
    }

    /**
     * How a band of the interactive viewer looks in its current state.
     *
     * @param opacity     opacity of the whole band (bands that do not match a search fade)
     * @param laneFill    fill of the lane highlight behind the band
     * @param bandFill    fill of the band
     * @param stroke      outline of the band, or {@code null} for none
     * @param strokeWidth width of the outline; unused without an outline
     * @param halo        fill of the halo around the band
     */
    record State(double opacity, Color laneFill, Color bandFill, Color stroke, double strokeWidth, Color halo) {

        /** Selection wins over hover, which wins over a search match. */
        static State of(boolean hovered, boolean selected, boolean searching, boolean searchMatch,
                        boolean colorByGene, Color geneColor, double intensity) {
            Color laneFill = hovered || selected ? Color.rgb(30, 144, 255, selected ? 0.18 : 0.10) : Color.TRANSPARENT;
            Color bandFill = withOpacity(colorByGene ? geneColor : BAND, intensity);
            double opacity = searchMatch ? 1.0 : 0.18;
            if (selected) {
                return new State(opacity, laneFill, bandFill, SELECTED, 2.0, Color.rgb(255, 140, 0, 0.28));
            }
            if (hovered) {
                return new State(opacity, laneFill, bandFill, HOVER, 1.25, Color.rgb(30, 144, 255, 0.25));
            }
            if (searching && searchMatch) {
                return new State(opacity, laneFill, bandFill, SEARCH_MATCH, 1.25, Color.rgb(60, 180, 75, 0.20));
            }
            return new State(opacity, laneFill, bandFill, null, 0, BandPaint.halo(intensity));
        }
    }
}
