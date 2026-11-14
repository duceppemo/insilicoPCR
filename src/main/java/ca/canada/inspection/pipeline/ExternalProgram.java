package ca.canada.inspection.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Runs the external programs of a run (BLAST+, BBTools): their output goes to the
 * console or to {@link RunMonitor#programOutput()}, each process is registered with
 * {@link RunMonitor#processes()}, and a cancelled run is reported as cancelled rather
 * than as a failed command.
 */
final class ExternalProgram {

    private ExternalProgram() {
    }

    /**
     * @param workingDirectory directory to run in; {@code null} keeps the current one
     * @param program          name of the program, for messages
     */
    static void run(RunMonitor monitor, Path workingDirectory, List<String> command, String program) {
        stopIfCancelled(monitor, program);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        // Error output is read, so that a program saying it ran out of memory is recognised;
        // on the console it is passed on to standard error as it comes.
        Consumer<String> output = monitor.programOutput();
        boolean console = output == null;
        if (console) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            output = System.err::println;
        } else {
            builder.redirectErrorStream(true);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PipelineException("Could not run " + program + ": " + String.join(" ", command), e);
        }
        if (monitor.processes() != null) {
            monitor.processes().add(process);
        }
        boolean ended = false;
        boolean saidOutOfMemory = false;
        try {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    console ? process.getErrorStream() : process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    output.accept(line);
                    saidOutOfMemory |= line.strip().equals(BbtoolsCommands.OUT_OF_MEMORY_LINE);
                }
            }
            int exit = process.waitFor();
            ended = true;
            stopIfCancelled(monitor, program);
            if (exit != 0) {
                throw new ProgramFailedException(program, exit, saidOutOfMemory,
                        program + " failed with exit code " + exit + ": " + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read the output of " + program + ": " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Interrupted while running " + program + ": " + String.join(" ", command), e);
        } finally {
            // A program left behind by an interrupted or failed wait would go on writing its files.
            if (!ended) {
                process.destroyForcibly();
            }
            if (monitor.processes() != null) {
                monitor.processes().remove(process);
            }
        }
    }

    private static void stopIfCancelled(RunMonitor monitor, String program) {
        if (monitor.isCancelled()) {
            throw new CancellationException("The run was cancelled (" + program + ").");
        }
    }
}
