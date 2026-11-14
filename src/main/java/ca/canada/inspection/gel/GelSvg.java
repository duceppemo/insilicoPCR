package ca.canada.inspection.gel;

import javafx.scene.paint.Color;

import java.util.Locale;

/** Writes a synthetic gel ({@link GelScene}) as an SVG document. */
final class GelSvg {
    private GelSvg() {
    }

    /** The gel as SVG: a band's hover text is kept as its title. */
    static String of(GelScene scene) {
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(format(scene.width())).append("\" height=\"")
                .append(format(scene.height())).append("\" viewBox=\"0 0 ").append(format(scene.width())).append(' ')
                .append(format(scene.height())).append("\">\n");
        scene.background().forEach(box -> box(svg, box, null));
        scene.ladder().forEach(band -> band(svg, band));
        scene.ladderLabels().forEach(label -> text(svg, label));
        scene.bands().forEach(band -> band(svg, band));
        box(svg, scene.border(), null);
        scene.laneLabels().forEach(label -> text(svg, label));
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void band(StringBuilder svg, GelScene.Band band) {
        svg.append("  <g>");
        if (band.title() != null && !band.title().isBlank()) {
            svg.append("<title>").append(escapeXml(band.title())).append("</title>");
        }
        svg.append('\n');
        band.boxes().forEach(box -> box(svg, box, "    "));
        svg.append("  </g>\n");
    }

    private static void box(StringBuilder svg, GelScene.Box box, String indent) {
        svg.append(indent == null ? "  " : indent).append("<rect x=\"").append(format(box.x())).append("\" y=\"")
                .append(format(box.y())).append("\" width=\"").append(format(box.width())).append("\" height=\"")
                .append(format(box.height())).append("\" fill=\"").append(box.fill() == null ? "none" : toHex(box.fill()))
                .append('"');
        if (box.fill() != null && box.fill().getOpacity() < 1.0) {
            svg.append(" fill-opacity=\"").append(format(box.fill().getOpacity())).append('"');
        }
        if (box.stroke() != null) {
            svg.append(" stroke=\"").append(toHex(box.stroke())).append("\" stroke-width=\"")
                    .append(format(box.strokeWidth())).append('"');
        }
        svg.append("/>\n");
    }

    private static void text(StringBuilder svg, GelScene.Label label) {
        svg.append("  <text x=\"").append(format(label.x())).append("\" y=\"").append(format(label.y()))
                .append("\" font-family=\"").append(GelScene.FONT).append("\" font-size=\"").append(format(label.fontSize()))
                .append("\" fill=\"#000000\"");
        if (label.anchor() == GelScene.Anchor.END) {
            svg.append(" text-anchor=\"end\"");
        } else {
            svg.append(" transform=\"").append(rotate45(label.x(), label.y())).append('"');
        }
        svg.append('>').append(escapeXml(label.text())).append("</text>\n");
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
