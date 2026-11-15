package ca.canada.inspection.pipeline;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * How a failed run is reported, on the command line and in the application.
 *
 * <p>A failure the user can act on (a primer file that cannot be read, two different
 * sizes declared for one assay, an unreadable input) is reported as its message. So is
 * an external tool (BLAST+, BBTools) that failed: the tool has printed its own message,
 * and the command line and exit code say more than a stack trace of this program. A
 * failure that points at the program itself keeps its stack trace, which is what a bug
 * report needs.</p>
 *
 * <p>Which is which is said by the exception type, one per layer: the run's steps raise a
 * {@link PipelineException} for a failure the user can act on, and the packages below them
 * ({@code pcr}, {@code report}, {@code util}) an {@link UncheckedIOException} for a file
 * that cannot be read or written. Anything else, {@link IllegalArgumentException}
 * included, is a bug: a value the user gave is checked before the run starts, and the
 * steps that read the user's files turn what they reject into a {@code PipelineException}.</p>
 */
public final class RunFailure {

    private RunFailure() {
    }

    /**
     * Whether {@code error} and everything that caused it are failures raised on purpose
     * with a message for the user: pipeline errors, failed external commands and file errors.
     */
    public static boolean isForTheUser(Throwable error) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            // A file name the locale cannot encode (LANG=C) is the user's to fix, as a missing file is.
            boolean expected = link instanceof PipelineException
                    || link instanceof IOException
                    || link instanceof UncheckedIOException
                    || link instanceof java.nio.file.InvalidPathException;
            if (!expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lines to print for a failure that {@link #isForTheUser}: its message, then the
     * message of each cause that says something the lines before it do not.
     */
    public static List<String> lines(Throwable error) {
        List<String> lines = new ArrayList<>();
        for (Throwable link = error; link != null; link = link.getCause()) {
            String message = message(link);
            if (lines.stream().anyMatch(line -> line.contains(message))) {
                continue;
            }
            lines.add((lines.isEmpty() ? "Error: " : "  Cause: ") + message);
        }
        return lines;
    }

    /**
     * What {@code error} says. A file the system refused (no permission, a read-only disk) says
     * only its path in its message; the reason is given apart, or by the exception's kind.
     */
    private static String message(Throwable error) {
        if (error instanceof java.nio.file.FileSystemException refused && refused.getFile() != null) {
            String reason = refused.getReason() != null ? refused.getReason()
                    : switch (refused) {
                        case java.nio.file.AccessDeniedException denied -> "permission denied";
                        case java.nio.file.NoSuchFileException missing -> "no such file or folder";
                        case java.nio.file.FileAlreadyExistsException exists -> "already exists";
                        default -> refused.getClass().getSimpleName();
                    };
            return refused.getFile() + ": " + reason
                    + (refused.getOtherFile() == null ? "" : " (" + refused.getOtherFile() + ")");
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage().strip();
    }
}
