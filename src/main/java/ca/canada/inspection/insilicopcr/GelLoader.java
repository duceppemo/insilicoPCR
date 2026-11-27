package ca.canada.inspection.insilicopcr;

import javafx.concurrent.Task;
import javafx.scene.Cursor;
import javafx.scene.Scene;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Reads a report for a gel on a thread of its own, then opens it: a large run's report took the window's thread
 * for as long as it was read, and the window did not answer meanwhile. The window shows the wait cursor until the
 * last report asked for is read (View Gel Image clicked twice reads two). Used on the FX thread.
 *
 * @param <T> what a report is read into
 */
final class GelLoader<T> {
    private final Scene scene;
    private final Consumer<T> show;
    private final Consumer<String> error;
    /** Reports being read. */
    private int loading;

    /**
     * @param scene the window's scene, whose cursor says a report is being read
     * @param show  opens what a report was read into, on the FX thread
     * @param error shows a message, on the FX thread
     */
    GelLoader(Scene scene, Consumer<T> show, Consumer<String> error) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.show = Objects.requireNonNull(show, "show");
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * Reads with {@code load} on a thread of its own, then shows what it read; a failure, to read or to show, is
     * {@code failure} followed by its message.
     */
    void open(Callable<T> load, String failure) {
        Task<T> reading = new Task<>() {
            @Override
            protected T call() throws Exception {
                return load.call();
            }
        };
        if (loading++ == 0) {
            scene.setCursor(Cursor.WAIT);
        }
        reading.setOnSucceeded(event -> {
            done();
            try {
                show.accept(reading.getValue());
            } catch (RuntimeException e) {
                error.accept(failure + e.getMessage());
            }
        });
        reading.setOnFailed(event -> {
            done();
            Throwable e = reading.getException();
            error.accept(failure + (e == null ? "" : e.getMessage()));
        });
        Thread.ofPlatform().daemon().name("gel-report").start(reading);
    }

    /** Reports being read; 0 once each has been shown or has failed. */
    int loading() {
        return loading;
    }

    private void done() {
        if (--loading == 0) {
            scene.setCursor(Cursor.DEFAULT);
        }
    }
}
