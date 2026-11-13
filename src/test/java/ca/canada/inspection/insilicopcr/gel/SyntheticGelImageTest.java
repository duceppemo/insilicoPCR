package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SyntheticGelImageTest {

    private static boolean startedToolkit;

    @TempDir
    Path dir;

    private Path report(String... rows) throws IOException {
        StringBuilder sb = new StringBuilder("Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return Files.writeString(dir.resolve("report.tsv"), sb);
    }

    @Test
    void reportWithoutAmpliconsIsRejectedBeforeDrawing() throws IOException {
        Path report = report();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SyntheticGelImage.exportPng(report, dir.resolve("gel.png"), List.of("negCtrl")));
        assertTrue(e.getMessage().startsWith("No amplicons were found"));
        assertFalse(Files.exists(dir.resolve("gel.png")));
    }

    @Test
    void missingReportIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SyntheticGelImage.exportPng(dir.resolve("none.tsv"), dir.resolve("gel.png"), List.of()));
    }

    /** JavaFX needs a display even to draw off screen; headless machines skip the rendering test. */
    private static boolean hasDisplay() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        return !System.getenv().getOrDefault("DISPLAY", "").isBlank()
                || !System.getenv().getOrDefault("WAYLAND_DISPLAY", "").isBlank();
    }

    private static synchronized void startToolkit() {
        try {
            Platform.startup(() -> {
            });
            startedToolkit = true;
        } catch (IllegalStateException alreadyRunning) {
            // Started by another test of this JVM.
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            abort("JavaFX could not start: " + e);
        }
    }

    @AfterAll
    static void stopToolkit() {
        if (startedToolkit) {
            Platform.exit();
        }
    }

    @Test
    void drawsTheGelWithEveryLaneAndTheLegend() throws IOException {
        assumeTrue(hasDisplay(), "no display");
        startToolkit();

        Path report = report(
                "isolate_01\ttoxA\t4001-4312\t312\tc1",
                "isolate_01\tstxQ\t7313-7430\t118\tc1",
                "isolate_02\tstxQ\t7313-7430\t118\tc1");
        Path png = dir.resolve("out").resolve("synthetic_gel_report.png");
        assertEquals(png, SyntheticGelImage.exportPng(report, png, List.of("isolate_01", "isolate_02", "negCtrl")));

        HashMap<String, Sample> named = new HashMap<>();
        named.put("isolate_01", null);
        named.put("isolate_02", null);
        named.put("negCtrl", null);
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(report, named);
        Map<String, Color> colors = GelColors.assignGeneColors(lanes);
        GelLayout layout = GelLayout.image(lanes.keySet(), GelLayout.legendWidth(colors.keySet()));

        BufferedImage image = ImageIO.read(png.toFile());
        assertEquals((int) Math.ceil(layout.width()), image.getWidth());
        assertEquals((int) Math.ceil(layout.height()), image.getHeight());
        assertEquals(0xffffffff, image.getRGB(0, 0));

        int border = image.getRGB((int) layout.gelLeft(), (int) (layout.gelTop() + layout.gelHeight() / 2));
        assertTrue((border & 0xffffff) < 0x202020, "gel border should be black: " + Integer.toHexString(border));

        LaneBand toxA = LaneBand.image(layout, 1, "isolate_01", lanes.get("isolate_01"), 2.0).getFirst();
        assertEquals("toxA", toxA.gene());
        int band = image.getRGB((int) layout.laneCenterX(1), (int) Math.round(toxA.centerY()));
        int red = (band >> 16) & 0xff;
        int blue = band & 0xff;
        assertTrue(blue > red + 40, "toxA band should be drawn in its blue gene colour: " + Integer.toHexString(band));
    }
}
