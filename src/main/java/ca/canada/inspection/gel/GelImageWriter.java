package ca.canada.inspection.gel;

import javafx.application.Platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Renders the synthetic gel PNG from the CLI (--gel). JavaFX needs a graphical
 * environment even for off-screen rendering, so expected failures (headless
 * servers, missing JavaFX natives) are reported as a warning instead of
 * failing the run.
 */
public final class GelImageWriter {

    /** JavaFX cannot be restarted after Platform.exit, so exporting is once per JVM. */
    private static final AtomicBoolean TOOLKIT_SHUT_DOWN = new AtomicBoolean(false);

    private static final String NO_GRAPHICS =
            "Warning: could not render the synthetic gel image (a graphical environment is required): ";

    private GelImageWriter() {
    }

    /**
     * @param info    told where the picture was written
     * @param warning told why it could not be
     */
    public static void write(Path consolidatedReport, Path outputFile, Collection<String> sampleNames,
                             Consumer<String> info, Consumer<String> warning) {
        if (TOOLKIT_SHUT_DOWN.get()) {
            warning.accept("Warning: the JavaFX toolkit was already shut down in this process;"
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
            } catch (RuntimeException | NoClassDefFoundError | UnsatisfiedLinkError e) {
                warning.accept(NO_GRAPHICS + rootMessage(e));
                return;
            }
            List<Path> written = SyntheticGelImage.exportPng(consolidatedReport, outputFile, sampleNames);
            info.accept(written.size() == 1 ? "Synthetic gel image written to " + written.getFirst()
                    : "Synthetic gel images written, " + InteractiveGelViewer.SAMPLES_PER_GEL + " samples a gel: "
                    + written.getFirst() + " to " + written.getLast().getFileName());
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            warning.accept(NO_GRAPHICS + rootMessage(e));
        } catch (RuntimeException e) {
            // Not the graphical environment: a full disk, a folder that cannot be written...
            warning.accept("Warning: could not write the synthetic gel image: " + rootMessage(e));
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
