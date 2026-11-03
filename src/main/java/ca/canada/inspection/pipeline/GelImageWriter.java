package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Methods;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders the synthetic gel PNG from the CLI (--gel). JavaFX needs a graphical
 * environment even for off-screen rendering, so expected failures (headless
 * servers, missing JavaFX natives) are reported as a warning instead of
 * failing the run.
 */
final class GelImageWriter {

    /** JavaFX cannot be restarted after Platform.exit, so exporting is once per JVM. */
    private static final AtomicBoolean TOOLKIT_SHUT_DOWN = new AtomicBoolean(false);

    private GelImageWriter() {
    }

    static void write(Path consolidatedReport, Path outputFile, Collection<String> sampleNames) {
        if (TOOLKIT_SHUT_DOWN.get()) {
            System.err.println("Warning: the JavaFX toolkit was already shut down in this process;"
                    + " the synthetic gel can only be rendered once per run.");
            return;
        }
        boolean startedHere = false;
        try {
            try {
                Platform.startup(() -> {
                });
                startedHere = true;
            } catch (IllegalStateException alreadyRunning) {
                // Toolkit already initialized (e.g. running inside the GUI); reuse it.
            }
            Path written = Methods.exportSyntheticGelPng(consolidatedReport, outputFile, sampleNames);
            System.out.println("Synthetic gel image written to " + written);
        } catch (RuntimeException | NoClassDefFoundError | UnsatisfiedLinkError e) {
            System.err.println("Warning: could not render the synthetic gel image (a graphical environment is required): "
                    + rootMessage(e));
        } finally {
            if (startedHere) {
                Platform.exit();
                TOOLKIT_SHUT_DOWN.set(true);
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
