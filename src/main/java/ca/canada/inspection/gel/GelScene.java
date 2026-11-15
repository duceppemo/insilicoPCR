package ca.canada.inspection.gel;

import ca.canada.inspection.gel.LaneBand.BandRect;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What a synthetic gel picture is made of, whatever draws it: the gel and its lanes, the
 * ladder and its labels, the sample bands, the border, the lane labels and the gene legend, in
 * drawing order.
 * The viewers (JavaFX nodes, {@link GelNodes}), the {@code --gel} image (a canvas,
 * {@link SyntheticGelImage}) and the SVG files ({@link GelSvg}) all draw one of these, so a
 * gel looks the same in each.
 *
 * @param width        width of the whole picture
 * @param height       height of the whole picture
 * @param background   the gel, its lanes, the lines between them and the gel's texture
 * @param ladder       the ladder's bands
 * @param ladderLabels the ladder's sizes, left of the gel
 * @param bands        the samples' bands
 * @param border       the gel's outline
 * @param laneLabels   the slanted names under the lanes, the ladder's first
 * @param legendBoxes  the legend's frame and its colour swatches; empty without a legend
 * @param legendLabels the legend's title and gene names; empty without a legend
 */
record GelScene(double width, double height, List<Box> background, List<Band> ladder, List<Label> ladderLabels,
                List<Band> bands, Box border, List<Label> laneLabels, List<Box> legendBoxes, List<Label> legendLabels) {

    static final String FONT = "Verdana";
    static final double LADDER_FONT_SIZE = 10.0;
    private static final double BAND_HEIGHT = 2.0;
    private static final double LADDER_INTENSITY = 0.45;

    GelScene {
        background = List.copyOf(background);
        ladder = List.copyOf(ladder);
        ladderLabels = List.copyOf(ladderLabels);
        bands = List.copyOf(bands);
        laneLabels = List.copyOf(laneLabels);
        legendBoxes = List.copyOf(legendBoxes);
        legendLabels = List.copyOf(legendLabels);
    }

    /**
     * A filled or outlined rectangle.
     *
     * @param fill   fill colour, or {@code null} for none
     * @param stroke outline colour, or {@code null} for none
     */
    record Box(double x, double y, double width, double height, Color fill, Color stroke, double strokeWidth) {
        static Box filled(double x, double y, double width, double height, Color fill) {
            return new Box(x, y, width, height, fill, null, 0);
        }
    }

    /** Where a label's {@code x} is. */
    enum Anchor {
        /** Its left end, as for the legend's text. */
        START,
        /** Its right end, as for the ladder's sizes. */
        END,
        /** Its start, the text turned 45 degrees about that point, as for the lane names. */
        SLANTED
    }

    /** Black text in {@link #FONT}, with its baseline at {@code y}. */
    record Label(String text, double x, double y, double fontSize, Anchor anchor) {
    }

    /**
     * A band: a soft halo, the band itself, a light line along its top and a dark one along
     * its bottom, which make it look like a band of a photographed gel.
     *
     * @param lane      the sample band it draws, or {@code null} for a ladder band
     * @param rect      the band itself
     * @param color     black, or its gene's colour
     * @param intensity how strong it looks, 0 to 1
     * @param title     what hovering it says (the SVG title), or {@code null}
     */
    record Band(LaneBand lane, BandRect rect, Color color, double intensity, String title) {
        Box halo() {
            return Box.filled(rect.x() - 1.5, rect.y() - 2.0, rect.width() + 3.0, rect.height() + 4.0,
                    BandPaint.halo(color, intensity));
        }

        Box body() {
            return Box.filled(rect.x(), rect.y(), rect.width(), rect.height(), BandPaint.body(color, intensity));
        }

        Box highlight() {
            return Box.filled(rect.x(), rect.y() + 0.5, rect.width(), Math.max(0.75, rect.height() * 0.22),
                    BandPaint.highlight(intensity));
        }

        Box shadow() {
            return Box.filled(rect.x(), rect.y() + rect.height() - 0.75, rect.width(), 0.75,
                    BandPaint.shadow(color, intensity));
        }

        /** The four rectangles, in drawing order. */
        List<Box> boxes() {
            return List.of(halo(), body(), highlight(), shadow());
        }
    }

    /**
     * The gel of {@code layout}: the ladder in lane 0, then a lane for each of {@code laneNames}
     * holding its {@code bands}.
     *
     * @param color         the colour of each band: black, or its gene's
     * @param laneFontSize  size of the lane names
     * @param legend        the genes and their colours, listed right of the gel at
     *                      {@link GelLayout#legendX()}; empty for no legend
     */
    static GelScene of(GelLayout layout, List<String> laneNames, List<LaneBand> bands,
                       Function<LaneBand, Color> color, double laneFontSize, Map<String, Color> legend) {
        double top = layout.gelTop();
        double height = layout.gelHeight();
        List<Box> background = new ArrayList<>();
        background.add(Box.filled(0, 0, layout.width(), layout.height(), Color.WHITE));
        background.add(Box.filled(layout.gelLeft(), top, layout.gelWidth(), height, BandPaint.GEL));
        for (int lane = 0; lane <= laneNames.size(); lane++) {
            background.add(Box.filled(layout.laneX(lane), top, layout.laneWidth(), height, BandPaint.laneFill(lane)));
            background.add(Box.filled(layout.laneX(lane), top, 3.0, height, Color.WHITE));
        }
        // A faint horizontal texture, as on a photographed gel.
        for (int row = 24; row < height; row += 42) {
            background.add(Box.filled(layout.gelLeft(), top + row, layout.gelWidth(), 1.0, BandPaint.LIGHT_TEXTURE));
            background.add(Box.filled(layout.gelLeft(), top + row + 2, layout.gelWidth(), 1.0, BandPaint.DARK_TEXTURE));
        }

        List<Band> ladder = new ArrayList<>();
        List<Label> ladderLabels = new ArrayList<>();
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            double y = layout.bandY(ladderBand.size());
            ladder.add(new Band(null, BandRect.inLane(layout.gelLeft(), y, layout.laneWidth(), BAND_HEIGHT),
                    Color.BLACK, LADDER_INTENSITY, "Ladder " + ladderBand.label()));
            ladderLabels.add(new Label(ladderBand.label(), layout.gelLeft() - 8, y + 4, LADDER_FONT_SIZE, Anchor.END));
        }

        List<Band> sampleBands = bands.stream()
                .map(band -> new Band(band, band.rect(), color.apply(band), band.intensity(), band.tooltip()))
                .toList();

        List<Label> laneLabels = new ArrayList<>();
        laneLabels.add(new Label("Ladder", layout.laneCenterX(0), layout.labelY(), laneFontSize, Anchor.SLANTED));
        for (int i = 0; i < laneNames.size(); i++) {
            laneLabels.add(new Label(laneNames.get(i), layout.laneCenterX(i + 1), layout.labelY(), laneFontSize,
                    Anchor.SLANTED));
        }

        Box border = new Box(layout.gelLeft(), top, layout.gelWidth(), height, null, Color.BLACK, 3.0);

        List<Box> legendBoxes = new ArrayList<>();
        List<Label> legendLabels = new ArrayList<>();
        if (!legend.isEmpty()) {
            double x = layout.legendX();
            legendBoxes.add(new Box(x, top, GelLayout.legendWidth(legend.keySet()) - 8,
                    GelLayout.legendHeight(legend.size()), Color.rgb(255, 255, 255, 0.95), Color.rgb(160, 160, 160), 1.0));
            legendLabels.add(new Label("Gene legend", x + 10, top + 19, 11, Anchor.START));
            int row = 0;
            for (Map.Entry<String, Color> entry : legend.entrySet()) {
                double rowY = top + 32 + row * 20.0;
                legendBoxes.add(new Box(x + 10, rowY, 14, 10, entry.getValue(), Color.rgb(90, 90, 90), 1.0));
                legendLabels.add(new Label(entry.getKey(), x + 32, rowY + 9, 10.5, Anchor.START));
                row++;
            }
        }
        return new GelScene(layout.width(), layout.height(), background, ladder, ladderLabels, sampleBands, border,
                laneLabels, legendBoxes, legendLabels);
    }
}
