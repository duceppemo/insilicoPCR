package ca.canada.inspection.dispatchpcr;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
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
 */
public final class AppPaths {
    private static final String BBTOOLS_JAR = "bbtools.jar";

    private AppPaths() {}

    public static RuntimeLayout discover() {
        Path appRoot = applicationRoot();
        Path runtimeRoot = appRoot.resolve("runtime").toAbsolutePath().normalize();
        String platform = platformName();
        Path platformRoot = runtimeRoot.resolve(platform).toAbsolutePath().normalize();

        Path bbmap = requireBbmap(runtimeRoot.resolve("common").resolve("bbmap"));
        Path blastBin = requireBlastBin(platformRoot.resolve("blast").resolve("bin"));
        Path javaExecutable = findJavaExecutable(platformRoot, appRoot)
                .orElseThrow(() -> missing("bundled Java runtime", platformRoot.resolve("jdk")));
        Path javafxLib = findJavaFxLib(platformRoot).orElse(null);

        return new RuntimeLayout(
                appRoot,
                runtimeRoot,
                platformRoot,
                bbmap,
                blastBin,
                javaExecutable,
                javafxLib
        );
    }

    public static Path applicationRoot() {
        try {
            Path location = Paths.get(AppPaths.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(location)) {
                return location.getParent();
            }

            Path current = location;
            while (current != null) {
                if (Files.exists(current.resolve("pom.xml"))
                        || Files.exists(current.resolve("insilicoPCR.jar"))) {
                    return current;
                }
                current = current.getParent();
            }

            return location;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to determine application location", e);
        }
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

    private static Optional<Path> findJavaFxLib(Path platformRoot) {
        Path javafx = platformRoot.resolve("javafx-sdk").resolve("lib");
        if (Files.isDirectory(javafx)) {
            return Optional.of(javafx.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private static IllegalStateException missing(String dependency, Path expectedPath) {
        return new IllegalStateException(
                "Unable to find " + dependency + ". Expected deterministic portable layout at: "
                        + expectedPath.toAbsolutePath().normalize()
        );
    }

    public record RuntimeLayout(
            Path appRoot,
            Path runtimeRoot,
            Path platformRoot,
            Path bbmapDirectory,
            Path blastBinDirectory,
            Path javaExecutable,
            Path javafxLibDirectory
    ) {
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
