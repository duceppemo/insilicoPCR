package ca.canada.inspection.pipeline;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Centralized runtime path discovery for portable insilicoPCR releases.
 *
 * <p>The supported release layout is intentionally deterministic:</p>
 *
 * <pre>
 * insilicoPCR.jar
 * lib/
 * runtime/
 *   common/
 *     bbmap/
 *       bbtools.jar
 *   linux|windows/
 *     blast/bin/
 *     jdk/bin/java[.exe]
 * </pre>
 *
 * <p>Development runs from the Maven project root use the same checked-in
 * runtime/common and runtime/&lt;platform&gt; bioinformatics tools. The bundled JDK
 * is only required in packaged releases; while developing, the current JVM is
 * accepted when runtime/&lt;platform&gt;/jdk is absent.</p>
 *
 * <p>Without a {@code runtime} folder (the ZIP for package managers, a conda installation), insilicoPCR
 * runs the programs installed beside it instead: BLAST+ and BBTools found on {@code PATH}, and BBTools in
 * the Java runtime that runs insilicoPCR. See {@link #installed(Path, String, Path)}.</p>
 */
public final class AppPaths {
    static final String BBTOOLS_JAR = "bbtools.jar";
    /** BBTools' classes in a folder, as its own scripts and conda's bbmap package have them. */
    static final String BBTOOLS_CLASSES = "current";

    private AppPaths() {}

    public static RuntimeLayout discover() {
        Path appRoot = applicationRoot();
        Path runtimeRoot = appRoot.resolve("runtime").toAbsolutePath().normalize();
        if (!Files.isDirectory(runtimeRoot)) {
            return installed(appRoot, System.getenv("PATH"), Path.of(System.getProperty("java.home")));
        }
        String platform = platformName();
        Path platformRoot = runtimeRoot.resolve(platform).toAbsolutePath().normalize();

        Path bbmap = requireBbmap(runtimeRoot.resolve("common").resolve("bbmap"));
        Path blastBin = requireBlastBin(platformRoot.resolve("blast").resolve("bin"));
        Path javaExecutable = findJavaExecutable(platformRoot, appRoot)
                .orElseThrow(() -> missing("bundled Java runtime", platformRoot.resolve("jdk")));
        return new RuntimeLayout(appRoot, bbmap, blastBin, javaExecutable);
    }

    public static Path applicationRoot() {
        try {
            Path location = Paths.get(AppPaths.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();

            Path start = Files.isRegularFile(location) ? location.getParent() : location;
            return findApplicationRoot(start).orElse(start);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to determine application location", e);
        }
    }

    private static Optional<Path> findApplicationRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (isCompleteRuntimeRoot(current)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }

        current = start;
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    || Files.exists(current.resolve("insilicoPCR.jar"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }

        return Optional.empty();
    }

    private static boolean isCompleteRuntimeRoot(Path directory) {
        Path runtime = directory.resolve("runtime");
        Path platformRuntime = runtime.resolve(platformName());
        return Files.isRegularFile(runtime.resolve("common").resolve("bbmap").resolve(BBTOOLS_JAR))
                && Files.isDirectory(platformRuntime.resolve("blast").resolve("bin"));
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    public static String platformName() {
        return isWindows() ? "windows" : "linux";
    }

    public static String executableName(String baseName) {
        return isWindows() ? baseName + ".exe" : baseName;
    }

    public static Path executable(Path directory, String baseName) {
        return directory.resolve(executableName(baseName));
    }

    private static Path requireBbmap(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw missing("BBMap directory", normalized);
        }

        Path jar = normalized.resolve(BBTOOLS_JAR);
        if (Files.isRegularFile(jar)) {
            return normalized;
        }

        throw missing("BBMap runtime jar", jar);
    }

    private static Path requireBlastBin(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        Path blastn = normalized.resolve(executableName("blastn"));
        Path makeblastdb = normalized.resolve(executableName("makeblastdb"));

        if (Files.isRegularFile(blastn) && Files.isRegularFile(makeblastdb)) {
            return normalized;
        }

        throw missing("NCBI BLAST executables", normalized);
    }

    private static Optional<Path> findJavaExecutable(Path platformRoot, Path appRoot) {
        Path bundled = platformRoot.resolve("jdk").resolve("bin").resolve(executableName("java"));
        if (Files.isRegularFile(bundled)) {
            return Optional.of(bundled.toAbsolutePath().normalize());
        }

        // Development mode: no JDK is committed under runtime/. Use the current JVM.
        if (Files.exists(appRoot.resolve("pom.xml"))) {
            Path currentJvm = Paths.get(System.getProperty("java.home"))
                    .resolve("bin")
                    .resolve(executableName("java"));
            if (Files.isRegularFile(currentJvm)) {
                return Optional.of(currentJvm.toAbsolutePath().normalize());
            }
        }

        return Optional.empty();
    }

    /**
     * The programs of an installation without a {@code runtime} folder: {@code blastn} and {@code makeblastdb}
     * from the first folder of {@code path} that has both; BBTools from the folder {@code bbduk.sh} is in,
     * links followed (conda links it into its {@code bin}), with its classes in {@code bbtools.jar} or in
     * {@code current/}; and the Java runtime in {@code javaHome}, the one running insilicoPCR.
     *
     * @param path the {@code PATH} variable's value, {@code null} when not set
     * @throws PipelineException naming the program not found
     */
    static RuntimeLayout installed(Path appRoot, String path, Path javaHome) {
        List<Path> folders = path == null ? List.of() : Arrays.stream(path.split(File.pathSeparator))
                .filter(folder -> !folder.isBlank())
                .map(folder -> {
                    try {
                        return Path.of(folder);
                    } catch (InvalidPathException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        Path blastBin = folders.stream()
                .filter(folder -> Files.isRegularFile(executable(folder, "blastn"))
                        && Files.isRegularFile(executable(folder, "makeblastdb")))
                .findFirst()
                .orElseThrow(() -> notOnPath("NCBI BLAST+ (blastn and makeblastdb)", "blast"))
                .toAbsolutePath().normalize();
        Path bbmap = folders.stream()
                .map(folder -> folder.resolve("bbduk.sh"))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(AppPaths::realFolderOf)
                .orElseThrow(() -> notOnPath("BBTools (bbduk.sh)", "bbmap"));
        if (!Files.isRegularFile(bbmap.resolve(BBTOOLS_JAR)) && !Files.isDirectory(bbmap.resolve(BBTOOLS_CLASSES))) {
            throw new PipelineException("Unable to find the classes of BBTools beside " + bbmap.resolve("bbduk.sh")
                    + ": neither " + BBTOOLS_JAR + " nor a " + BBTOOLS_CLASSES + " folder is there.");
        }
        Path java = javaHome.resolve("bin").resolve(executableName("java")).toAbsolutePath().normalize();
        return new RuntimeLayout(appRoot, bbmap, blastBin, java);
    }

    /** The folder {@code file} really is in, links followed. */
    private static Path realFolderOf(Path file) {
        try {
            return file.toRealPath().getParent();
        } catch (java.io.IOException e) {
            throw new PipelineException("Unable to follow " + file + " to the folder of BBTools: " + e.getMessage());
        }
    }

    private static PipelineException notOnPath(String programs, String condaPackage) {
        return new PipelineException("Unable to find " + programs + ". insilicoPCR has no runtime folder beside it,"
                + " so it runs the programs found on PATH: install them (conda install -c conda-forge -c bioconda "
                + condaPackage + "), or use a portable release, which bundles them.");
    }

    /** A tool missing from the installation: something the user can fix, not a bug. */
    private static PipelineException missing(String dependency, Path expectedPath) {
        return new PipelineException(
                "Unable to find " + dependency + ". Expected deterministic portable layout at: "
                        + expectedPath.toAbsolutePath().normalize()
        );
    }

    public record RuntimeLayout(Path appRoot, Path bbmapDirectory, Path blastBinDirectory, Path javaExecutable) {
        public String javaCommand() {
            return javaExecutable.toString();
        }

        public Path blastnExecutable() {
            return AppPaths.executable(blastBinDirectory, "blastn");
        }

        public Path makeblastdbExecutable() {
            return AppPaths.executable(blastBinDirectory, "makeblastdb");
        }
    }
}
