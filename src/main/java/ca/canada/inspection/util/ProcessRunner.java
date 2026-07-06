package ca.canada.inspection.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small wrapper around ProcessBuilder with consistent output capture and exit-code checks. */
public final class ProcessRunner {
    private ProcessRunner() {}

    public static Result run(File workingDirectory, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        int exitCode = process.waitFor();
        return new Result(Arrays.asList(command), workingDirectory, exitCode, output);
    }

    public static Result runOrThrow(File workingDirectory, String... command) throws IOException, InterruptedException {
        Result result = run(workingDirectory, command);
        if (result.exitCode() != 0) {
            throw new IOException("Command failed with exit code " + result.exitCode() + ": "
                    + String.join(" ", result.command()) + System.lineSeparator()
                    + String.join(System.lineSeparator(), result.output()));
        }
        return result;
    }

    public record Result(List<String> command, File workingDirectory, int exitCode, List<String> output) {}
}
