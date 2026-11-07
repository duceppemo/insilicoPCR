package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalProcessTrackerTest {

    /** Child JVM that would run for a minute unless killed. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000);
        }
    }

    private static Process startSleeper() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName())
                .redirectErrorStream(true)
                .start();
    }

    @Test
    void destroyAllKillsRegisteredProcesses() throws Exception {
        var tracker = new ExternalProcessTracker();
        Process running = startSleeper();
        tracker.add(running);

        tracker.destroyAll();

        assertTrue(running.waitFor(10, TimeUnit.SECONDS), "registered process was not killed");
        assertTrue(tracker.isShutDown());
    }

    @Test
    void processRegisteredAfterShutdownIsKilledAtOnce() throws Exception {
        // A tool that starts while the window is closing must not outlive the application.
        var tracker = new ExternalProcessTracker();
        tracker.destroyAll();
        Process late = startSleeper();

        tracker.add(late);

        assertTrue(late.waitFor(10, TimeUnit.SECONDS), "late process was left running");
        assertFalse(late.isAlive());
    }
}
