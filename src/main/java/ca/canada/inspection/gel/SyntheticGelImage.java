package ca.canada.inspection.gel;

import javafx.application.Platform;
import javafx.scene.paint.Color;

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

/** The synthetic gel of a consolidated report drawn straight to PNG files, without a window (--gel). */
public final class SyntheticGelImage {

    private SyntheticGelImage() {
    }

    /** Where {@code --gel} writes the gel of {@code consolidatedReport}: see {@link GelImages#defaultOutput}. */
    public static Path defaultPng(Path consolidatedReport) {
        return GelImages.defaultOutput(consolidatedReport);
    }

    /**
     * Renders the synthetic gel of a consolidated report to PNG, without opening a window: one
     * file, {@code outputFile}, for up to {@link InteractiveGelViewer#SAMPLES_PER_GEL} samples,
     * as the gel window pages them, and one file a gel beyond ({@code ..._page_001.png} and on).
     * Bands are coloured by gene, with a legend. The JavaFX toolkit must already be running and
     * this method must NOT be called on the FX application thread.
     *
     * @return the files written, in order
     */
    public static List<Path> exportPng(Path consolidatedReport, Path outputFile, Collection<String> sampleNames) {
        return exportPng(GelReportReader.read(consolidatedReport, sampleNames), outputFile,
                "No sample to draw in consolidated report: " + consolidatedReport);
    }

    /** As {@link #exportPng(Path, Path, Collection)}, for the lanes of a run ({@link GelReportReader#lanes}). */
    static List<Path> exportPng(LinkedHashMap<String, List<GelBand>> lanes, Path outputFile, String noSample) {
        // A run without a product is drawn with empty lanes, as the gel window draws it.
        if (lanes.isEmpty()) {
            throw new IllegalStateException(noSample);
        }
        // One colour per gene for the whole run, as in the gel window.
        Map<String, Color> geneColors = GelColors.assignGeneColors(lanes);
        GelPages pages = new GelPages(List.copyOf(lanes.keySet()), InteractiveGelViewer.SAMPLES_PER_GEL);
        List<Path> written = new ArrayList<>();
        for (int page = 0; page < pages.totalPages(); page++) {
            LinkedHashMap<String, List<GelBand>> pageLanes = pages.lanes(page, lanes);
            Path file = GelImages.pageOutput(outputFile, page, pages.totalPages());
            // Drawn one gel at a time: the picture of a large run would not fit in one image.
            onFxThread(() -> GelImages.writePng(GelPicture.image(GelPicture.scene(pageLanes, geneColors, GelColorMode.GENE), false),
                    file), file);
            written.add(file);
        }
        return written;
    }

    @FunctionalInterface
    private interface Drawing {
        void run() throws IOException;
    }

    private static void onFxThread(Drawing drawing, Path file) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                drawing.run();
            } catch (IOException e) {
                failure.set(new UncheckedIOException("Unable to save synthetic gel image: " + file, e));
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
    }
}
