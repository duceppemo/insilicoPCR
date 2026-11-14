package ca.canada.inspection.gel;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** The synthetic gel of a consolidated report drawn straight to a PNG file, without a window (--gel). */
public final class SyntheticGelImage {
    private static final double BAND_HEIGHT = 2.0;

    private SyntheticGelImage() {
    }

    /** Where {@code --gel} writes the gel of {@code consolidatedReport}: see {@link GelImages#defaultOutput}. */
    public static Path defaultPng(Path consolidatedReport) {
        return GelImages.defaultOutput(consolidatedReport);
    }

    /**
     * Renders the synthetic gel for a consolidated report straight to a PNG file,
     * without opening a window. The JavaFX toolkit must already be running and
     * this method must NOT be called on the FX application thread.
     */
    public static Path exportPng(Path consolidatedReport, Path outputFile, Collection<String> sampleNames) {
        LinkedHashMap<String, List<GelBand>> sampleBands = GelReportReader.read(consolidatedReport, sampleNames);
        if (sampleBands.values().stream().allMatch(List::isEmpty)) {
            throw new IllegalStateException("No amplicons were found in consolidated report: " + consolidatedReport);
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Canvas canvas = draw(sampleBands);
                GelImages.writePng(GelImages.snapshot(canvas, canvas.getWidth(), canvas.getHeight(), false), outputFile);
            } catch (IOException e) {
                failure.set(new UncheckedIOException("Unable to save synthetic gel image: " + outputFile, e));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rendering synthetic gel", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Unable to render synthetic gel image", failure.get());
        }
        return outputFile;
    }

    private static Canvas draw(LinkedHashMap<String, List<GelBand>> sampleBands) {
        Map<String, Color> geneColors = GelColors.assignGeneColors(sampleBands);
        GelLayout layout = GelLayout.image(sampleBands.keySet(), GelLayout.legendWidth(geneColors.keySet()));
        // Amplicons of one size from different genes are drawn apart, so no gene colour is hidden.
        List<LaneBand> bands = new ArrayList<>();
        int lane = 1;
        for (Map.Entry<String, List<GelBand>> sampleEntry : sampleBands.entrySet()) {
            bands.addAll(LaneBand.image(layout, lane, sampleEntry.getKey(), sampleEntry.getValue(), BAND_HEIGHT));
            lane++;
        }
        GelScene scene = GelScene.of(layout, List.copyOf(sampleBands.keySet()), bands,
                band -> geneColors.getOrDefault(band.gene(), Color.BLACK), GelScene.LADDER_FONT_SIZE);

        Canvas canvas = new Canvas(layout.width(), layout.height());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        scene.background().forEach(box -> box(gc, box));
        scene.ladder().forEach(band -> band.boxes().forEach(box -> box(gc, box)));
        scene.ladderLabels().forEach(label -> text(gc, label));
        scene.bands().forEach(band -> band.boxes().forEach(box -> box(gc, box)));
        box(gc, scene.border());
        scene.laneLabels().forEach(label -> text(gc, label));
        drawLegend(gc, geneColors, layout.legendX(), layout.gelTop());
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
        if (label.anchor() == GelScene.Anchor.END) {
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(label.text(), label.x(), label.y());
            gc.setTextAlign(TextAlignment.LEFT);
        } else {
            gc.save();
            gc.translate(label.x(), label.y());
            gc.rotate(45);
            gc.fillText(label.text(), 0, 0);
            gc.restore();
        }
    }

    private static void drawLegend(GraphicsContext gc, Map<String, Color> geneColors, double x, double y) {
        if (geneColors.isEmpty()) {
            return;
        }
        double rowHeight = 20;
        double width = GelLayout.legendWidth(geneColors.keySet()) - 8;
        double height = 32 + geneColors.size() * rowHeight;

        gc.setFill(Color.rgb(255, 255, 255, 0.95));
        gc.fillRect(x, y, width, height);
        gc.setStroke(Color.rgb(160, 160, 160));
        gc.setLineWidth(1.0);
        gc.strokeRect(x, y, width, height);

        gc.setFill(Color.BLACK);
        gc.setFont(new Font("Verdana", 11));
        gc.fillText("Gene legend", x + 10, y + 19);

        int row = 0;
        gc.setFont(new Font("Verdana", 10.5));
        for (Map.Entry<String, Color> entry : geneColors.entrySet()) {
            double rowY = y + 32 + row * rowHeight;
            gc.setFill(entry.getValue());
            gc.fillRect(x + 10, rowY, 14, 10);
            gc.setStroke(Color.rgb(90, 90, 90));
            gc.strokeRect(x + 10, rowY, 14, 10);
            gc.setFill(Color.BLACK);
            gc.fillText(entry.getKey(), x + 32, rowY + 9);
            row++;
        }
    }
}
