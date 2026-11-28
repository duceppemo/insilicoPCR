package ca.canada.inspection.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The program under test, run as a user runs it: a command line in a process of its own, judged by the files it
 * writes. The benchmark knows nothing of its classes, so that the same cases run against this build, a release
 * unpacked in a folder, and a version whose inside is written anew.
 *
 * @param label   what the scorecard calls it
 * @param command the command before the options
 */
record App(String label, List<String> command) {

    /** A run that takes longer is stopped, and counts as failed. */
    private static final long TIMEOUT_MINUTES = 240;

    /**
     * A finished run.
     *
     * @param output the folder the reports are in
     */
    record Run(int exitCode, double seconds, Path output, Path log) {
    }

    /** The classes this benchmark was compiled with: the build being tested. */
    static App currentBuild() {
        // Surefire runs the tests from a jar that only names the class path; it keeps the real one here.
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new App("this build", List.of(java().toString(), "-cp", classPath,
                "ca.canada.inspection.dispatchpcr.Dispatcher"));
    }

    /**
     * A release: its folder (the launcher in it is run), its launcher, or its jar (run with this Java).
     */
    static App installed(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            Path launcher = absolute.resolve(windows ? "run-insilicoPCR.bat" : "run-insilicoPCR.sh");
            if (!Files.isRegularFile(launcher)) {
                throw new IllegalArgumentException("No launcher " + launcher.getFileName() + " in " + absolute);
            }
            return new App(absolute.getFileName().toString(), List.of(launcher.toString()));
        }
        if (absolute.getFileName().toString().endsWith(".jar")) {
            return new App(absolute.getFileName().toString(), List.of(java().toString(), "-jar", absolute.toString()));
        }
        return new App(absolute.getFileName().toString(), List.of(absolute.toString()));
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /** What {@code --version} prints, or the label when it cannot be run. */
    String version() {
        try {
            List<String> line = new ArrayList<>(command);
            line.add("--version");
            Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
            String printed = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor();
            return printed.strip().isEmpty() ? label : printed.strip().lines().reduce((a, b) -> b).orElse(label);
        } catch (IOException e) {
            return label;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return label;
        }
    }

    /**
     * Runs the program on {@code input} with {@code primers} into {@code output}, which must not exist; what it
     * prints goes to {@code log}.
     */
    Run run(Path input, Path primers, Path output, List<String> options, int threads, Path log) throws IOException {
        List<String> line = new ArrayList<>(command);
        line.addAll(List.of("-i", input.toString(), "-p", primers.toString(), "-o", output.toString(), "-t",
                String.valueOf(threads)));
        line.addAll(options);
        long start = System.nanoTime();
        Process process = new ProcessBuilder(line).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exitCode;
        try {
            if (process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                exitCode = process.exitValue();
            } else {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while the program ran", e);
        }
        return new Run(exitCode, (System.nanoTime() - start) / 1e9, output, log);
    }
}
