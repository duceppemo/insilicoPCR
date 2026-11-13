package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pipeline.PipelineException;
import ca.canada.inspection.util.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * How the command line reports a failed run.
 *
 * <p>A failure the user can act on (a primer file that cannot be read, two different
 * sizes declared for one assay, an unreadable input) is reported as its message. So is
 * an external tool (BLAST+, BBTools) that failed: the tool has printed its own message,
 * and the command line and exit code say more than a stack trace of this program. A
 * failure that points at the program itself keeps its stack trace, which is what a bug
 * report needs.</p>
 */
final class CliErrors {

    private CliErrors() {
    }

    /**
     * Whether {@code error} and everything that caused it are failures raised on purpose
     * with a message for the user: pipeline errors, failed external commands, rejected
     * values and file errors.
     */
    static boolean isForTheUser(Throwable error) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            boolean expected = link instanceof PipelineException
                    || link instanceof ProcessRunner.ProcessException
                    || link instanceof IllegalArgumentException
                    || link instanceof IOException
                    || link instanceof UncheckedIOException;
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
    static List<String> lines(Throwable error) {
        List<String> lines = new ArrayList<>();
        for (Throwable link = error; link != null; link = link.getCause()) {
            String message = link.getMessage() == null ? link.getClass().getSimpleName() : link.getMessage().strip();
            if (lines.stream().anyMatch(line -> line.contains(message))) {
                continue;
            }
            lines.add((lines.isEmpty() ? "Error: " : "  Cause: ") + message);
        }
        return lines;
    }
}
