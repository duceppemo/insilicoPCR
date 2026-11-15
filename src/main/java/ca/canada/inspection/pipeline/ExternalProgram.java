package ca.canada.inspection.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
        // The output is read to recognise a program saying it ran out of memory: on the console
        // only the error output, copied to standard error unchanged, byte for byte.
        Consumer<String> output = monitor.programOutput();
        boolean console = output == null;
        if (console) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else {
            builder.redirectErrorStream(true);
        }
        Process process;
        // The job object made before the program starts, so that it is put in it at once (WindowsJob).
        WindowsJob.active();
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PipelineException("Could not run " + program + ": " + String.join(" ", command)
                    + tooLongForWindows(workingDirectory), e);
        }
        // Registered first: a run stopped now (the window closed, a kill) must find it to end it.
        if (monitor.processes() != null) {
            monitor.processes().add(process);
        }
        // On Windows, ended with this program even when it is killed (Task Manager, a workflow manager).
        WindowsJob.adopt(process);
        // Read on another thread, so that this one waits in waitFor(), which an interrupt stops
        // (and the program is then killed below); a blocking read would not stop.
        FutureTask<Boolean> reading = new FutureTask<>(() -> {
            try {
                return console ? copyToConsole(process.getErrorStream())
                        : passLines(process.getInputStream(), output);
            } catch (IOException | RuntimeException e) {
                process.destroyForcibly(); // or it may block on a full pipe, and waitFor() with it
                throw e;
            }
        });
        Thread.ofVirtual().name(program + " output").start(reading);
        boolean ended = false;
        try {
            int exit = process.waitFor();
            ended = true;
            boolean saidOutOfMemory = reading.get();
            stopIfCancelled(monitor, program);
            if (exit != 0) {
                // Where it ran too: a command with a relative path is run again by hand from there.
                throw new ProgramFailedException(program, exit, saidOutOfMemory,
                        program + " failed with exit code " + exit + ": " + String.join(" ", command)
                                + (workingDirectory == null ? "" : " (in " + workingDirectory + ")"));
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            throw new PipelineException("Could not read the output of " + program + ": " + String.join(" ", command),
                    e.getCause());
        } catch (InterruptedException e) {
            // The run thread is interrupted when the application cancels the run: a cancellation,
            // not a failed program, which a step may note and go on after.
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("The run was cancelled (" + program + ").");
            cancelled.initCause(e);
            throw cancelled;
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

    /**
     * The encoding of what the programs print: on Windows, the computer's code page, in which BLAST+ and a
     * Java runtime writing to a pipe both print, so that a path with an accent showed in the application's
     * log as "R�sultats". Elsewhere UTF-8, as before.
     */
    static final java.nio.charset.Charset OUTPUT_ENCODING = AppPaths.isWindows() ? WindowsCodePage.system()
            : StandardCharsets.UTF_8;

    /** Gives each line to {@code output}; whether one says the program ran out of memory. */
    private static boolean passLines(InputStream stream, Consumer<String> output) throws IOException {
        boolean saidOutOfMemory = false;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream, OUTPUT_ENCODING))) {
            String line;
            while ((line = lines.readLine()) != null) {
                output.accept(line);
                // contains: with standard output merged in, another thread's text may share its line
                saidOutOfMemory |= line.contains(BbtoolsCommands.OUT_OF_MEMORY_LINE);
            }
        }
        return saidOutOfMemory;
    }

    /** Copies to standard error as it comes; whether it says the program ran out of memory. */
    private static boolean copyToConsole(InputStream stream) throws IOException {
        String sought = BbtoolsCommands.OUT_OF_MEMORY_LINE;
        StringBuilder recent = new StringBuilder();
        boolean saidOutOfMemory = false;
        byte[] buffer = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                System.err.write(buffer, 0, read);
                System.err.flush();
                if (!saidOutOfMemory) {
                    // The message is ASCII: one char per byte, whatever the rest is in.
                    recent.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                    saidOutOfMemory = recent.indexOf(sought) >= 0;
                    recent.delete(0, Math.max(0, recent.length() - sought.length()));
                }
            }
        }
        return saidOutOfMemory;
    }

    /**
     * Why a program could not start in {@code workingDirectory}, when its path is too long for Windows
     * ("CreateProcess error=267, The directory name is invalid"), or nothing.
     */
    static String tooLongForWindows(Path workingDirectory) {
        if (workingDirectory == null || !WindowsPathLimit.tooLong(workingDirectory)) {
            return "";
        }
        return " (the path of the folder it runs in is " + workingDirectory.toAbsolutePath().toString().length()
                + " characters long: Windows starts no program in a folder whose path has "
                + WindowsPathLimit.MAX_PATH + " characters or more; choose an output folder with a shorter path)";
    }

    private static void stopIfCancelled(RunMonitor monitor, String program) {
        if (monitor.isCancelled()) {
            throw new CancellationException("The run was cancelled (" + program + ").");
        }
    }
}
