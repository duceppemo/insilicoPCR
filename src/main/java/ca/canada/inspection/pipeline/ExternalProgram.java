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
        // The output is read to recognise a program saying it ran out of memory. On the console, both its outputs go
        // to standard error: standard output holds this program's own lines only (makeblastdb's went there, among
        // them, into what a script reads).
        Consumer<String> output = monitor.programOutput();
        boolean console = output == null;
        if (console) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        }
        builder.redirectErrorStream(true);
        Process process;
        // The job object made before the program starts, so that it is put in it at once (WindowsJob).
        WindowsJob.prepare();
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
                return console ? copyToConsole(process.getInputStream(), AppPaths.isWindows() ? OUTPUT_ENCODING : null,
                        System.err) : passLines(process.getInputStream(), output);
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
            if (killedBySignal(exit)) {
                // Ctrl-C, or a workflow manager signalling the process group: the program may die a moment
                // before this program's own shutdown (which marks the run stopped) begins. A failure said
                // then ("blastn failed with exit code 130") was the stop itself.
                Thread.sleep(SIGNAL_GRACE.toMillis());
            }
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

    /** How long a program killed by SIGINT or SIGTERM waits for this program's own stop to be seen. */
    private static final java.time.Duration SIGNAL_GRACE = java.time.Duration.ofMillis(300);

    /** Whether {@code exit} is a program's end by SIGINT (130) or SIGTERM (143), outside Windows. */
    static boolean killedBySignal(int exit) {
        return !AppPaths.isWindows() && (exit == 128 + 2 || exit == 128 + 15);
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

    /**
     * Copies to {@code console} (standard error) as it comes; whether it says the program ran out of memory.
     *
     * @param encoding what the program prints in, read and written again in the console's own: on Windows, the
     *                 programs print in the computer's code page (1252), and the console (cmd.exe) shows another
     *                 (850), so an accent came out wrong ("RÚsultats"). {@code null}: copied byte for byte.
     */
    static boolean copyToConsole(InputStream stream, java.nio.charset.Charset encoding, java.io.PrintStream console)
            throws IOException {
        String sought = BbtoolsCommands.OUT_OF_MEMORY_LINE;
        StringBuilder recent = new StringBuilder();
        boolean saidOutOfMemory = false;
        byte[] buffer = new byte[8192];
        char[] chars = new char[8192];
        try (stream; java.io.Reader reader = encoding == null ? null : new InputStreamReader(stream, encoding)) {
            int read;
            while ((read = reader == null ? stream.read(buffer) : reader.read(chars)) != -1) {
                // The message is ASCII: one char per byte, whatever the rest is in.
                String text = reader == null ? new String(buffer, 0, read, StandardCharsets.ISO_8859_1)
                        : new String(chars, 0, read);
                if (reader == null) {
                    console.write(buffer, 0, read);
                } else {
                    console.print(text);
                }
                console.flush();
                if (!saidOutOfMemory) {
                    recent.append(text);
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
