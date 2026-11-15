package ca.canada.inspection.gel;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A gel drawn as a picture to keep: the {@code --gel} image and the gel window's Save PNG, SVG
 * and PDF. All are drawn from one {@link GelScene}, at a fixed size whatever the window's, without
 * what the window adds while it is used (hover and selection outlines, search fading), and with
 * the legend when the bands are coloured (by gene or by size check).
 */
final class GelPicture {
    static final double BAND_HEIGHT = 2.0;

    private GelPicture() {
    }

    /**
     * The gel of {@code lanes}, one lane per sample in order.
     *
     * @param geneColors the run's colour of each gene, so that a gene keeps it from gel to gel
     * @param mode       black bands without a legend, or coloured by gene or by size check, with a
     *                   legend of the genes or size checks on this gel
     */
    static GelScene scene(LinkedHashMap<String, List<GelBand>> lanes, Map<String, Color> geneColors,
                          GelColorMode mode) {
        Map<String, Color> legend = GelColors.legend(mode, lanes.values().stream().flatMap(List::stream).toList(),
                geneColors);
        GelLayout layout = GelLayout.image(lanes.keySet(), GelLayout.legendWidth(legend.keySet()), legend.size());
        List<String> names = List.copyOf(lanes.keySet());
        List<LaneBand> bands = new ArrayList<>();
        for (int lane = 1; lane <= names.size(); lane++) {
            bands.addAll(LaneBand.of(layout, lane, names.get(lane - 1), lanes.get(names.get(lane - 1)), BAND_HEIGHT,
                    mode));
        }
        return GelScene.of(layout, names, bands, band -> {
            Color color = GelColors.of(mode, band.bands().getFirst(), geneColors);
            return color == null ? BandPaint.BAND : color;
        }, GelScene.LADDER_FONT_SIZE, mode.legendTitle(), legend);
    }

    /**
     * {@code scene} as an image, on a white background for a PDF; must run on the JavaFX
     * application thread.
     */
    static BufferedImage image(GelScene scene, boolean whiteBackground) {
        Canvas canvas = canvas(scene);
        return GelImages.snapshot(canvas, canvas.getWidth(), canvas.getHeight(), whiteBackground);
    }

    static Canvas canvas(GelScene scene) {
        Canvas canvas = new Canvas(scene.width(), scene.height());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        scene.background().forEach(box -> box(gc, box));
        scene.ladder().forEach(band -> band.boxes().forEach(box -> box(gc, box)));
        scene.ladderLabels().forEach(label -> text(gc, label));
        scene.bands().forEach(band -> band.boxes().forEach(box -> box(gc, box)));
        box(gc, scene.border());
        scene.laneLabels().forEach(label -> text(gc, label));
        scene.legendBoxes().forEach(box -> box(gc, box));
        scene.legendLabels().forEach(label -> text(gc, label));
        return canvas;
    }

    private static void box(GraphicsContext gc, GelScene.Box box) {
        if (box.fill() != null) {
            gc.setFill(box.fill());
            gc.fillRect(box.x(), box.y(), box.width(), box.height());
        }
        if (box.stroke() != null) {
            gc.setStroke(box.stroke());
            gc.setLineWidth(box.strokeWidth());
            gc.strokeRect(box.x(), box.y(), box.width(), box.height());
        }
    }

    private static void text(GraphicsContext gc, GelScene.Label label) {
        gc.setFill(Color.BLACK);
        gc.setFont(new Font(GelScene.FONT, label.fontSize()));
        switch (label.anchor()) {
            case START -> gc.fillText(label.text(), label.x(), label.y());
            case END -> {
                gc.setTextAlign(TextAlignment.RIGHT);
                gc.fillText(label.text(), label.x(), label.y());
                gc.setTextAlign(TextAlignment.LEFT);
            }
            case SLANTED -> {
                gc.save();
                gc.translate(label.x(), label.y());
                gc.rotate(45);
                gc.fillText(label.text(), 0, 0);
                gc.restore();
            }
        }
    }
}
