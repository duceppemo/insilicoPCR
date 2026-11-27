package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.ReportRow;
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
     * Writes the gel of a run's rows (what {@code --gel} draws: the run has them, and its report is not read back).
     *
     * @param sizes   the product sizes the primer file declares, for the size check of each band
     * @param info    told where the picture was written
     * @param warning told why it could not be
     * @return the pictures written (one per gel), none when they could not be
     */
    public static List<Path> write(List<ReportRow> rows, ExpectedSizes sizes, Path outputFile,
                                   Collection<String> sampleNames, Consumer<String> info, Consumer<String> warning) {
        return write(() -> SyntheticGelImage.exportPng(GelReportReader.lanes(rows, sizes, sampleNames), outputFile,
                "No sample to draw"), info, warning);
    }

    private static List<Path> write(java.util.function.Supplier<List<Path>> export, Consumer<String> info,
                                    Consumer<String> warning) {
        if (TOOLKIT_SHUT_DOWN.get()) {
            warning.accept("Warning: the JavaFX toolkit was already shut down in this process;"
                    + " the synthetic gel can only be rendered once per run.");
            return List.of();
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
                return List.of();
            }
            List<Path> written = export.get();
            info.accept(written.size() == 1 ? "Synthetic gel image written to " + written.getFirst()
                    : "Synthetic gel images written, " + InteractiveGelViewer.SAMPLES_PER_GEL + " samples a gel: "
                    + written.getFirst() + " to " + written.getLast().getFileName());
            return written;
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
        return List.of();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
