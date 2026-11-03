package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Methods;
import javafx.application.Platform;

import java.nio.file.Path;

/**
 * Renders the synthetic gel PNG from the CLI (--gel). JavaFX needs a graphical
 * environment even for off-screen rendering, so failures (e.g. headless
 * servers) are reported as a warning instead of failing the run.
 */
final class GelImageWriter {

    private GelImageWriter() {
    }

    static void write(Path consolidatedReport, Path outputFile, java.util.Collection<String> sampleNames) {
        boolean startedHere = false;
        try {
            try {
                Platform.startup(() -> {
                });
                startedHere = true;
            } catch (IllegalStateException alreadyRunning) {
                // Toolkit already initialized; reuse it.
            }
            Path written = Methods.exportSyntheticGelPng(consolidatedReport, outputFile, sampleNames);
            System.out.println("Synthetic gel image written to " + written);
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: could not render the synthetic gel image (a graphical environment is required): "
                    + rootMessage(e));
        } finally {
            if (startedHere) {
                Platform.exit();
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
