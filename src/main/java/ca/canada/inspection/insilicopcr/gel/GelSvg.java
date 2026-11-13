package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.gel.LaneBand.BandRect;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the synthetic gel of the gel viewers as an SVG document. */
final class GelSvg {
    private GelSvg() {
    }

    /**
     * A sample band of the interactive viewer as shown on screen.
     *
     * @param rect      the band's rectangle
     * @param color     band colour: black, or the gene colour when colouring by gene
     * @param intensity band opacity
     * @param title     hover text, kept as the rectangle's SVG title
     */
    record Band(BandRect rect, Color color, double intensity, String title) {
    }

    /** The interactive viewer's gel: lanes, ladder, labels, then the sample bands. */
    static String window(GelLayout layout, List<String> sampleNames, List<Band> bands) {
        StringBuilder svg = new StringBuilder();
        header(svg, layout);
        svg.append("  <rect x=\"").append(format(layout.gelLeft())).append("\" y=\"").append(format(layout.gelTop())).append("\" width=\"")
                .append(format(layout.gelWidth())).append("\" height=\"").append(format(layout.gelHeight())).append("\" fill=\"#eeeeee\" stroke=\"black\" stroke-width=\"3\"/>\n");
        lanes(svg, layout, sampleNames.size() + 1);
        ladder(svg, layout, "10", toHex(Color.BLACK));
        border(svg, layout);
        text(svg, "Ladder", layout.laneCenterX(0), layout.labelY(), "10", toHex(Color.BLACK), rotate45(layout.laneCenterX(0), layout.labelY()));
        for (int i = 0; i < sampleNames.size(); i++) {
            double cx = layout.laneCenterX(i + 1);
            text(svg, sampleNames.get(i), cx, layout.labelY(), "10", toHex(Color.BLACK), rotate45(cx, layout.labelY()));
        }
        for (Band band : bands) {
            BandRect rect = band.rect();
            band(svg, rect.x(), rect.y(), rect.width(), Math.max(1.2, rect.height()), band.color(),
                    Math.max(0.35, band.intensity()), band.title());
        }
        svg.append("</svg>\n");
        return svg.toString();
    }

    /** One page of the paginated viewer: lanes, ladder, each sample's bands and label, the border. */
    static String page(GelLayout layout, List<String> sampleNames, Map<String, List<GelBand>> lanes,
                       Map<String, Color> geneColors, boolean colorByGene) {
        StringBuilder svg = new StringBuilder();
        header(svg, layout);
        lanes(svg, layout, sampleNames.size() + 1);
        String fontSize = format(GelLayout.PAGE_LABEL_FONT_SIZE);
        ladder(svg, layout, format(10.0), "black");
        int lane = 1;
        for (String sampleName : sampleNames) {
            for (LaneBand band : LaneBand.page(layout, lane, lanes.getOrDefault(sampleName, List.of()))) {
                Color color = colorByGene ? geneColors.getOrDefault(band.gene(), Color.BLACK) : Color.BLACK;
                band(svg, band.laneX() + layout.laneWidth() * 0.17, band.centerY() - 1.0, layout.laneWidth() * 0.66,
                        band.rect().height(), color, band.intensity(), band.tooltip());
            }
            double cx = layout.laneCenterX(lane);
            text(svg, sampleName, cx, layout.labelY(), fontSize, "black", rotate45(cx, layout.labelY()));
            lane++;
        }
        double ladderCx = layout.laneCenterX(0);
        text(svg, "Ladder", ladderCx, layout.labelY(), fontSize, "black", rotate45(ladderCx, layout.labelY()));
        border(svg, layout);
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void header(StringBuilder svg, GelLayout layout) {
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(format(layout.width())).append("\" height=\"")
                .append(format(layout.height())).append("\" viewBox=\"0 0 ").append(format(layout.width())).append(' ')
                .append(format(layout.height())).append("\">\n");
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
    }

    private static void lanes(StringBuilder svg, GelLayout layout, int laneCount) {
        for (int lane = 0; lane < laneCount; lane++) {
            double x = layout.laneX(lane);
            svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(layout.gelTop())).append("\" width=\"")
                    .append(format(layout.laneWidth())).append("\" height=\"").append(format(layout.gelHeight())).append("\" fill=\"")
                    .append(toHex(BandPaint.laneFill(lane))).append("\"/>\n");
            svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(layout.gelTop())).append("\" width=\"3\" height=\"")
                    .append(format(layout.gelHeight())).append("\" fill=\"white\"/>\n");
        }
    }

    private static void ladder(StringBuilder svg, GelLayout layout, String fontSize, String textFill) {
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            double y = layout.bandY(ladderBand.size());
            band(svg, layout.gelLeft() + (layout.laneWidth() * 0.17), y - 1.0, layout.laneWidth() * 0.66, 2.0,
                    Color.BLACK, 0.45, "Ladder " + ladderBand.label());
            text(svg, ladderBand.label(), layout.gelLeft() - 8 - GelScale.approximateTextWidth(ladderBand.label()), y + 4,
                    fontSize, textFill, null);
        }
    }

    private static void border(StringBuilder svg, GelLayout layout) {
        svg.append("  <rect x=\"").append(format(layout.gelLeft())).append("\" y=\"").append(format(layout.gelTop())).append("\" width=\"")
                .append(format(layout.gelWidth())).append("\" height=\"").append(format(layout.gelHeight()))
                .append("\" fill=\"none\" stroke=\"black\" stroke-width=\"3\"/>\n");
    }

    private static void band(StringBuilder svg, double x, double y, double width, double height, Color color,
                             double opacity, String title) {
        svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" width=\"")
                .append(format(width)).append("\" height=\"").append(format(height)).append("\" fill=\"")
                .append(toHex(color)).append("\" opacity=\"").append(format(opacity)).append("\">");
        if (title != null && !title.isBlank()) {
            svg.append("<title>").append(escapeXml(title)).append("</title>");
        }
        svg.append("</rect>\n");
    }

    private static void text(StringBuilder svg, String text, double x, double y, String fontSize, String fill,
                             String transform) {
        svg.append("  <text x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" font-family=\"Verdana\" font-size=\"")
                .append(fontSize).append("\" fill=\"").append(fill).append("\"");
        if (transform != null) {
            svg.append(" transform=\"").append(transform).append("\"");
        }
        svg.append(">").append(escapeXml(text)).append("</text>\n");
    }

    private static String rotate45(double x, double y) {
        return "rotate(45 " + format(x) + " " + format(y) + ")";
    }

    /** A coordinate with three decimals and a dot, whatever the locale. */
    static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** "#rrggbb" of a colour, ignoring its opacity. */
    static String toHex(Color color) {
        int r = (int) Math.round(color.getRed() * 255.0);
        int g = (int) Math.round(color.getGreen() * 255.0);
        int b = (int) Math.round(color.getBlue() * 255.0);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** Text with the five XML special characters escaped; empty for {@code null}. */
    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
