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

    /**
     * The lock files this program holds, by real path. A second run of the application into a held folder is refused
     * from here, before it opens the file: on Linux, closing that second channel (after its tryLock failed) let go of
     * the lock the first run held, and the folder was no longer held.
     */
    private static final java.util.Set<Path> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Channels that found the lock held here by another path to the file: kept open, as closing them would free it. */
    private static final java.util.List<FileChannel> KEPT_OPEN = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final FileChannel channel;
    private final FileLock lock;
    private final Path held;

    private OutputLock(FileChannel channel, FileLock lock, Path held) {
        this.channel = channel;
        this.lock = lock;
        this.held = held;
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
        Path key = heldKey(output);
        if (!HELD.add(key)) {
            throw new OutputFolderInUse(output);
        }
        try {
            return take(output, file, key, warning);
        } catch (RuntimeException | Error e) {
            HELD.remove(key);
            throw e;
        }
    }

    /** The folder as the system has it (links resolved), so that two paths to it are one. */
    private static Path heldKey(Path output) {
        try {
            return output.toRealPath().resolve(FILE_NAME);
        } catch (IOException notThere) {
            return output.toAbsolutePath().normalize().resolve(FILE_NAME);
        }
    }

    private static OutputLock take(Path output, Path file, Path key, Consumer<String> warning) {
        FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            warning.accept("Warning: could not lock the output folder (" + e.getMessage() + "): do not run another"
                    + " run into it at the same time");
            return new OutputLock(null, null, key); // held in this program all the same
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            // By another run of this program, through another path to the file: closing this channel would free it.
            KEPT_OPEN.add(channel);
            throw new OutputFolderInUse(output);
        } catch (IOException e) {
            closeQuietly(channel);
            warning.accept("Warning: could not lock the output folder (" + e.getMessage() + "): do not run another"
                    + " run into it at the same time");
            return new OutputLock(null, null, key); // held in this program all the same
        }
        if (lock == null) {
            closeQuietly(channel);
            throw new OutputFolderInUse(output);
        }
        return new OutputLock(channel, lock, key);
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
        if (held != null) {
            HELD.remove(held);
        }
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
