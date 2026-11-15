package ca.canada.inspection.dispatchpcr;

import java.util.List;

/** A program run as the command line runs it, waiting to be killed (DispatcherTest). */
public final class KilledRun {
    private KilledRun() {
    }

    public static void main(String[] args) {
        System.out.println("started");
        Dispatcher.consoleMonitor().runProgram(null, List.of("sleep", "60"), "sleep");
    }
}
