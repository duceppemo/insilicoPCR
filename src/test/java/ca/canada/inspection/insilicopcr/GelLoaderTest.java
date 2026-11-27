package ca.canada.inspection.insilicopcr;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The window reading a report for the gel: off its thread, with the wait cursor until the last report is read, and
 * a failure said in a message. Needs a display (a virtual one in CI).
 */
class GelLoaderTest {

    private final List<String> shown = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() {
        assumeTrue(hasDisplay(), "no display");
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyRunning) {
            // Started by another test of this JVM.
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            abort("JavaFX could not start: " + e);
        }
        Platform.setImplicitExit(false);
    }

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

    @Test
    void theWindowWaitsUntilTheLastReportAskedForIsRead() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        Scene scene = onFx(() -> new Scene(new Group()));
        GelLoader<String> loader = onFx(() -> new GelLoader<>(scene, shown::add, errors::add));

        // View Gel Image clicked twice: the cursor of the first stayed on for good once the second ended last.
        onFx(() -> {
            loader.open(read("first", first), "failed:\n");
            loader.open(read("second", second), "failed:\n");
            return null;
        });
        assertEquals(Cursor.WAIT, onFx(scene::getCursor));
        assertEquals(2, (int) onFx(loader::loading));

        first.countDown();
        await(() -> shown.size() == 1);
        assertEquals(Cursor.WAIT, onFx(scene::getCursor), "the second is still being read");

        second.countDown();
        await(() -> shown.size() == 2);
        assertEquals(List.of("first", "second"), shown);
        assertEquals(Cursor.DEFAULT, onFx(scene::getCursor));
        assertEquals(0, (int) onFx(loader::loading));
        assertEquals(List.of(), errors);
    }

    @Test
    void aReportThatCannotBeReadOrShownIsAMessage() throws Exception {
        Scene scene = onFx(() -> new Scene(new Group()));
        GelLoader<String> loader = onFx(() -> new GelLoader<>(scene, text -> {
            throw new IllegalStateException("cannot draw " + text);
        }, errors::add));

        onFx(() -> {
            loader.open(() -> {
                throw new IllegalArgumentException("Consolidated report not found: gone.tsv");
            }, "Unable to open report TSV:\n");
            return null;
        });
        await(() -> errors.size() == 1);
        onFx(() -> {
            loader.open(() -> "it", "Unable to create gel image:\n");
            return null;
        });
        await(() -> errors.size() == 2);

        assertEquals(List.of("Unable to open report TSV:\nConsolidated report not found: gone.tsv",
                "Unable to create gel image:\ncannot draw it"), errors);
        assertEquals(Cursor.DEFAULT, onFx(scene::getCursor));
    }

    /** A read giving {@code text} once {@code release} is counted down, off the FX thread. */
    private static Callable<String> read(String text, CountDownLatch release) {
        return () -> {
            assertTrue(!Platform.isFxApplicationThread(), "read on the window's thread");
            assertTrue(release.await(10, TimeUnit.SECONDS), "never released");
            return text;
        };
    }

    /** {@code work} on the FX thread, waited for. */
    private static <T> T onFx(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.get());
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread did not answer");
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** Waits up to 10 s for {@code condition}, checked on the FX thread (where the loader calls back). */
    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onFx(condition)) {
            assertTrue(System.nanoTime() < end, "timed out");
            Thread.sleep(20);
        }
    }
}
