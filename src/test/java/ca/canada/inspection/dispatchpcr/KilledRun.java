package ca.canada.inspection.dispatchpcr;

import java.util.List;

/** A program run as the command line runs it, waiting to be killed (DispatcherTest). */
public final class KilledRun {
    /** A program that waits a minute: sleep, or on Windows, ping (timeout needs a console to read). */
    static final List<String> SLEEP = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win") ? List.of("ping", "-n", "61", "127.0.0.1") : List.of("sleep", "60");

    private KilledRun() {
    }

    public static void main(String[] args) {
        System.out.println("started");
        Dispatcher.consoleMonitor().runProgram(null, SLEEP, "sleep");
    }
}
