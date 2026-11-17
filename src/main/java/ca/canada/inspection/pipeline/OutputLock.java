package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * The output folder held by one run: two runs into it at once (a workflow manager running a job
 * per sample into one folder) overwrote each other's primer database and reports, and could
 * search each other's primers. The lock is the operating system's, so it goes with the run,
 * however the run ends; the lock file is left in the folder (hidden on Linux and macOS by its
 * leading dot; listed on Windows).
 */
final class OutputLock implements AutoCloseable {
    static final String FILE_NAME = ".insilicoPCR.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private OutputLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Holds {@code output} for this run.
     *
     * @param warning told when the folder's file system cannot lock (some network file systems):
     *                the run goes on without the lock
     * @throws OutputFolderInUse when another run holds the folder
     */
    static OutputLock take(Path output, Consumer<String> warning) {
        Path file = output.resolve(FILE_NAME);
        FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            warning.accept("Warning: could not lock the output folder (" + e.getMessage() + "): do not run another"
                    + " run into it at the same time");
            return new OutputLock(null, null);
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            lock = null; // by another run of this program (the application)
        } catch (IOException e) {
            closeQuietly(channel);
            warning.accept("Warning: could not lock the output folder (" + e.getMessage() + "): do not run another"
                    + " run into it at the same time");
            return new OutputLock(null, null);
        }
        if (lock == null) {
            closeQuietly(channel);
            throw new OutputFolderInUse(output);
        }
        return new OutputLock(channel, lock);
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Released with the channel anyway.
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing held any more.
        }
    }
}
