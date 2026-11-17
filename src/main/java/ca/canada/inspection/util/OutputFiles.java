package ca.canada.inspection.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The run's way with the files it writes: a report or log is written beside its place, under
 * {@link #partOf its .part name}, then {@link #moveOver moved over it}, so that a run stopped part way
 * leaves no file cut short where one is looked for; and a work file it no longer needs is
 * {@link #deleteQuietly deleted}, a failure to do so mattering less than what the run reports.
 */
public final class OutputFiles {

    /** What is added to a file's name while it is written. */
    public static final String PART = ".part";

    private OutputFiles() {
    }

    /** Where {@code target} is written before it is moved over its place. */
    public static Path partOf(Path target) {
        return target.resolveSibling(target.getFileName() + PART);
    }

    /** Moves {@code part} over {@code target}: at once where the file system can, else as a plain move. */
    public static void moveOver(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes {@code file} if it is there; a failure leaves it behind (a work file, or a part of one). */
    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is only clutter: what the run reports matters more.
        }
    }
}
