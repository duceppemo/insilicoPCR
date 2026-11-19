package ca.canada.inspection.pipeline;

import java.util.List;


import java.nio.file.Files;
import java.nio.file.Path;

public record RuntimeDependencies(AppPaths.RuntimeLayout layout) {

    public static RuntimeDependencies discover() {
        try {
            return new RuntimeDependencies(AppPaths.discover());
        } catch (RuntimeException e) {
            throw new PipelineException("Unable to discover bundled runtime dependencies", e);
        }
    }

    public Path appRoot() {
        return layout.appRoot();
    }

    public Path bbmapDirectory() {
        return layout.bbmapDirectory();
    }

    public Path blastBinDirectory() {
        return layout.blastBinDirectory();
    }

    public String javaCommand() {
        return layout.javaCommand();
    }

    public Path blastn() {
        return layout.blastnExecutable();
    }

    public Path makeblastdb() {
        return layout.makeblastdbExecutable();
    }

    /**
     * Where BBTools' classes are, for {@code -cp}: {@code bbtools.jar} (the portable releases), or the
     * {@code current} folder of an installed BBTools (conda's bbmap package).
     */
    public Path bbtoolsClassPath() {
        Path jar = bbmapDirectory().resolve(AppPaths.BBTOOLS_JAR);
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Path classes = bbmapDirectory().resolve(AppPaths.BBTOOLS_CLASSES);
        if (Files.isDirectory(classes)) {
            return classes;
        }
        throw new PipelineException("Missing BBMap runtime: " + jar.toAbsolutePath());
    }

    /** The versions found by {@link #versions()}, by the BLAST and BBTools folders they were asked of. */
    private static final java.util.Map<String, String> VERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The versions of the programs the run uses, for its QAlog.txt and its report ("BLAST+ 2.17.0, BBTools 40.04,
     * Java 25.0.4"): a result is audited with them. Asked of the programs once; "unknown" for one that does
     * not say.
     */
    public String versions() {
        String blast;
        String bbtools;
        try {
            blast = blastn().toString();
            bbtools = bbtoolsClassPath().toString();
        } catch (RuntimeException missing) {
            return "unknown (" + missing.getMessage() + ")";
        }
        return VERSIONS.computeIfAbsent(blast + "|" + bbtools, ignored -> "BLAST+ "
                + firstMatch(List.of(blast, "-version"), "blastn: ([0-9.]+)", 20)
                + ", BBTools " + firstMatch(List.of(javaCommand(), "-cp", bbtools, "jgi.BBDuk", "--version"),
                "BBTools version ([0-9.]+)", 20)
                + ", Java " + Runtime.version() + " (" + System.getProperty("java.vendor", "?") + ")");
    }

    /** The first group of {@code pattern} in what {@code command} prints within {@code seconds}, or "unknown". */
    static String firstMatch(List<String> command, String pattern, int seconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output;
            try (java.io.InputStream in = process.getInputStream()) {
                java.util.concurrent.Future<byte[]> read = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return in.readAllBytes();
                    } catch (java.io.IOException e) {
                        return new byte[0];
                    }
                });
                if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return "unknown";
                }
                output = new String(read.get(5, java.util.concurrent.TimeUnit.SECONDS),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            java.util.regex.Matcher found = java.util.regex.Pattern.compile(pattern).matcher(output);
            return found.find() ? found.group(1) : "unknown";
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }
}
