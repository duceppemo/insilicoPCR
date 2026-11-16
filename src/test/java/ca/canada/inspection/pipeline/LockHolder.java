package ca.canada.inspection.pipeline;

import java.nio.file.Path;

/** Another run, as another process: holds an output folder until its input ends (OutputLockTest). */
public final class LockHolder {
    private LockHolder() {
    }

    public static void main(String[] args) throws Exception {
        try (OutputLock ignored = OutputLock.take(Path.of(args[0]), System.err::println)) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() >= 0) {
                // until the test closes the pipe
            }
        }
    }
}
