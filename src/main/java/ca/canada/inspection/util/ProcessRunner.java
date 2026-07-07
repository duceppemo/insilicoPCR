package ca.canada.inspection.util;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Centralized external-process execution with consistent timing, error handling,
 * and interruption behavior.
 */
public final class ProcessRunner {

    private ProcessRunner() {
    }

    public static Result run(ExternalCommand command) {
        Objects.requireNonNull(command, "command");
        return run(command.workingDirectory(), command.arguments().toArray(String[]::new));
    }

    public static Result run(Path workingDirectory, String... command) {
        Objects.requireNonNull(command, "command");
        if (command.length == 0) {
            throw new IllegalArgumentException("Command must not be empty");
        }

        Instant start = Instant.now();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }

            try (Process process = builder.start()) {
                int exitCode = process.waitFor();
                Duration elapsed = Duration.between(start, Instant.now());
                Result result = new Result(exitCode, elapsed, List.of(command));
                if (exitCode != 0) {
                    throw new ProcessException("External command failed: " + result.commandLine()
                            + " (exit " + exitCode + ", " + elapsed.toSeconds() + "s)", result);
                }
                return result;
            }
        } catch (IOException e) {
            throw new ProcessException("Could not start external command: " + String.join(" ", command), e,
                    new Result(-1, Duration.between(start, Instant.now()), List.of(command)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessException("Interrupted while running external command: " + String.join(" ", command), e,
                    new Result(-1, Duration.between(start, Instant.now()), List.of(command)));
        }
    }

    public record ExternalCommand(Path workingDirectory, List<String> arguments) {
        public ExternalCommand {
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException("Command must not be empty");
            }
        }

        public static ExternalCommand of(Path workingDirectory, String... arguments) {
            return new ExternalCommand(workingDirectory, Arrays.asList(arguments));
        }
    }

    public record Result(int exitCode, Duration elapsed, List<String> command) {
        public Result {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
        }

        public String commandLine() {
            return String.join(" ", command);
        }
    }

    public static final class ProcessException extends RuntimeException {
        private final Result result;

        public ProcessException(String message, Result result) {
            super(message);
            this.result = Objects.requireNonNull(result, "result");
        }

        public ProcessException(String message, Throwable cause, Result result) {
            super(message, cause);
            this.result = Objects.requireNonNull(result, "result");
        }

        public Result result() {
            return result;
        }
    }
}
