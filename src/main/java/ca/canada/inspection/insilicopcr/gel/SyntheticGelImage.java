package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.insilicopcr.gel.LaneBand.BandRect;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
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

    /**
     * Renders the synthetic gel for a consolidated report straight to a PNG file,
     * without opening a window. The JavaFX toolkit must already be running and
     * this method must NOT be called on the FX application thread.
     */
    public static Path exportPng(Path consolidatedReport, Path outputFile, Collection<String> sampleNames) {
        HashMap<String, Sample> laneSamples = new HashMap<>();
        for (String name : sampleNames) {
            laneSamples.put(name, null);
        }
        LinkedHashMap<String, List<GelBand>> sampleBands = GelReportReader.read(consolidatedReport, laneSamples);
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
                failure.set(new IllegalStateException("Unable to save synthetic gel image: " + outputFile, e));
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
        int laneCount = sampleBands.size() + 1;

        Canvas canvas = new Canvas(layout.width(), layout.height());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, layout.width(), layout.height());

        drawGelBackground(gc, layout, laneCount);
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            drawBand(gc, BandRect.inLane(layout.gelLeft(), layout.bandY(ladderBand.size()), layout.laneWidth(), BAND_HEIGHT),
                    0.45, Color.BLACK);
        }
        drawLadderLabels(gc, layout);
        int lane = 1;
        for (Map.Entry<String, List<GelBand>> sampleEntry : sampleBands.entrySet()) {
            for (LaneBand band : LaneBand.image(layout, lane, sampleEntry.getKey(), sampleEntry.getValue(), BAND_HEIGHT)) {
                drawBand(gc, band.rect(), band.intensity(), geneColors.getOrDefault(band.gene(), Color.BLACK));
            }
            lane++;
        }
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(3.0);
        gc.strokeRect(layout.gelLeft(), layout.gelTop(), layout.gelWidth(), layout.gelHeight());
        drawLaneLabels(gc, layout, sampleBands.keySet());
        drawLegend(gc, geneColors, layout.legendX(), layout.gelTop());
        return canvas;
    }

    private static void drawGelBackground(GraphicsContext gc, GelLayout layout, int laneCount) {
        double x = layout.gelLeft();
        double y = layout.gelTop();
        double width = layout.gelWidth();
        double height = layout.gelHeight();
        gc.setFill(BandPaint.GEL);
        gc.fillRect(x, y, width, height);

        for (int i = 0; i < laneCount; i++) {
            double laneX = layout.laneX(i);
            gc.setFill(BandPaint.laneFill(i));
            gc.fillRect(laneX, y, layout.laneWidth(), height);

            gc.setStroke(Color.rgb(255, 255, 255));
            gc.setLineWidth(3.0);
            gc.strokeLine(laneX, y, laneX, y + height);
        }
        gc.strokeLine(x + width, y, x + width, y + height);

        // Very subtle horizontal gel texture, similar to a photographed electrophoresis gel.
        gc.setLineWidth(1.0);
        for (int row = 24; row < height; row += 42) {
            gc.setStroke(BandPaint.LIGHT_TEXTURE);
            gc.strokeLine(x, y + row, x + width, y + row);
            gc.setStroke(BandPaint.DARK_TEXTURE);
            gc.strokeLine(x, y + row + 2, x + width, y + row + 2);
        }
    }

    private static void drawLaneLabels(GraphicsContext gc, GelLayout layout, Collection<String> sampleNames) {
        gc.setFill(Color.BLACK);
        gc.setStroke(Color.BLACK);
        gc.setFont(new Font("Verdana", 10));

        drawSlantedLabel(gc, "Ladder", layout.laneCenterX(0), layout.labelY());
        int lane = 1;
        for (String sampleName : sampleNames) {
            drawSlantedLabel(gc, sampleName, layout.laneCenterX(lane), layout.labelY());
            lane++;
        }
    }

    private static void drawSlantedLabel(GraphicsContext gc, String label, double centerX, double y) {
        gc.save();
        gc.translate(centerX, y);
        gc.rotate(45);
        gc.fillText(label, 0, 0);
        gc.restore();
    }

    private static void drawLadderLabels(GraphicsContext gc, GelLayout layout) {
        gc.setFill(Color.BLACK);
        gc.setFont(new Font("Verdana", 10));
        gc.setTextAlign(TextAlignment.RIGHT);
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            gc.fillText(ladderBand.label(), layout.gelLeft() - 8, layout.bandY(ladderBand.size()) + 4);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private static void drawBand(GraphicsContext gc, BandRect rect, double intensity, Color color) {
        double bandX = rect.x();
        double bandY = rect.y();
        double bandWidth = rect.width();
        double bandHeight = rect.height();

        // Soft halo around the band so thin bands stay visible without looking like solid rectangles.
        gc.setFill(BandPaint.imageHalo(color, intensity));
        gc.fillRect(bandX - 1.5, bandY - 2.0, bandWidth + 3.0, bandHeight + 4.0);

        // Main band in the gene color. Higher intensity means more opaque and slightly thicker.
        gc.setFill(BandPaint.imageBody(color, intensity));
        gc.fillRect(bandX, bandY, bandWidth, bandHeight);

        // Subtle highlight and lower shadow make the band look less computer-generated.
        gc.setFill(BandPaint.highlight(intensity));
        gc.fillRect(bandX, bandY + 0.5, bandWidth, Math.max(0.75, bandHeight * 0.22));

        gc.setFill(BandPaint.imageShadow(color, intensity));
        gc.fillRect(bandX, bandY + bandHeight - 0.75, bandWidth, 0.75);
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
