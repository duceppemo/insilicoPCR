package ca.canada.inspection.dispatchpcr;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Centralized runtime path discovery for portable insilicoPCR releases.
 *
 * Supported layouts:
 *
 * Historical v0.5:
 *
 *   insilicoPCR.jar
 *   linux/bin/bbmap
 *   linux/bin/ncbi-blast-.../bin
 *   linux/jdk-21.0.3/bin/java
 *   linux/javafx-sdk-21.0.3/lib
 *
 * Modernized shared-runtime layout:
 *
 *   runtime/common/bbmap
 *   runtime/linux/jdk-...
 *   runtime/linux/javafx-sdk-...
 *   runtime/linux/blast/bin
 *   runtime/windows/jdk-...
 *   runtime/windows/javafx-sdk-...
 *   runtime/windows/blast/bin
 */
public final class AppPaths {
    private AppPaths() {}

    public static RuntimeLayout discover() {
        Path appRoot = applicationRoot();
        Path runtimeRoot = runtimeRoot(appRoot);
        Path platformRoot = platformRoot(appRoot, runtimeRoot);

        Path bbmap = findBbmap(appRoot, runtimeRoot, platformRoot)
                .orElseThrow(() -> missing("BBMap", appRoot));

        Path blastBin = findBlastBin(appRoot, runtimeRoot, platformRoot)
                .orElseThrow(() -> missing("NCBI BLAST bin directory", appRoot));

        Path javaExecutable = findJavaExecutable(appRoot, runtimeRoot, platformRoot)
                .orElseThrow(() -> missing("bundled Java runtime", appRoot));

        Optional<Path> javafxLib = findJavaFxLib(appRoot, runtimeRoot, platformRoot);

        return new RuntimeLayout(
                appRoot,
                runtimeRoot,
                platformRoot,
                bbmap,
                blastBin,
                javaExecutable,
                javafxLib.orElse(null)
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
                        || Files.exists(current.resolve("insilicoPCR.jar"))
                        || Files.isDirectory(current.resolve("runtime"))
                        || Files.isDirectory(current.resolve("linux"))
                        || Files.isDirectory(current.resolve("windows"))) {
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

    private static Path runtimeRoot(Path appRoot) {
        Path runtime = appRoot.resolve("runtime");
        if (Files.isDirectory(runtime)) {
            return runtime.toAbsolutePath().normalize();
        }

        return appRoot.toAbsolutePath().normalize();
    }

    private static Path platformRoot(Path appRoot, Path runtimeRoot) {
        String platform = platformName();

        Path[] candidates = new Path[] {
                runtimeRoot.resolve(platform),
                appRoot.resolve(platform),
                appRoot.resolve("dependencies").resolve(platform),
                appRoot.resolve("src").resolve("other_resources").resolve(platform),
                appRoot.resolve("src").resolve("main").resolve("resources").resolve(platform)
        };

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        return appRoot.toAbsolutePath().normalize();
    }

    private static Optional<Path> findBbmap(Path appRoot, Path runtimeRoot, Path platformRoot) {
        Path[] direct = new Path[] {
                runtimeRoot.resolve("common").resolve("bbmap"),
                appRoot.resolve("runtime").resolve("common").resolve("bbmap"),

                // Historical v0.5 layout
                platformRoot.resolve("bin").resolve("bbmap"),
                appRoot.resolve(platformName()).resolve("bin").resolve("bbmap"),

                // Other fallback layouts
                platformRoot.resolve("bbmap"),
                appRoot.resolve("bbmap"),
                appRoot.resolve("dependencies").resolve("bbmap"),
                appRoot.resolve("src").resolve("other_resources").resolve("common").resolve("bbmap")
        };

        for (Path candidate : direct) {
            if (isBbmapDirectory(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }

        return findDirectory(appRoot, AppPaths::isBbmapDirectory);
    }

    private static boolean isBbmapDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }

        return Files.exists(directory.resolve("current"))
                || Files.exists(directory.resolve("bbmap.sh"))
                || Files.exists(directory.resolve("bbduk.sh"))
                || Files.exists(directory.resolve("tadpole.sh"));
    }

    private static Optional<Path> findBlastBin(Path appRoot, Path runtimeRoot, Path platformRoot) {
        String makeblastdb = executableName("makeblastdb");

        Path[] direct = new Path[] {
                platformRoot.resolve("blast").resolve("bin"),
                platformRoot.resolve("ncbi-blast").resolve("bin"),

                // Historical v0.5 layout
                platformRoot.resolve("bin").resolve("ncbi-blast").resolve("bin"),
                appRoot.resolve(platformName()).resolve("bin").resolve("ncbi-blast").resolve("bin"),

                // Versioned NCBI BLAST folders
                platformRoot.resolve("bin"),
                appRoot.resolve("blast").resolve("bin"),
                appRoot.resolve("dependencies").resolve("blast").resolve("bin"),
                runtimeRoot.resolve(platformName()).resolve("blast").resolve("bin")
        };

        for (Path candidate : direct) {
            if (Files.isRegularFile(candidate.resolve(makeblastdb))) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }

        return findFile(appRoot, makeblastdb)
                .map(Path::getParent)
                .map(Path::toAbsolutePath)
                .map(Path::normalize);
    }

    private static Optional<Path> findJavaExecutable(Path appRoot, Path runtimeRoot, Path platformRoot) {
        String java = executableName("java");

        Path[] direct = new Path[] {
                platformRoot.resolve("jdk-21.0.3").resolve("bin").resolve(java),
                platformRoot.resolve("jdk").resolve("bin").resolve(java),
                platformRoot.resolve("jre").resolve("bin").resolve(java),

                runtimeRoot.resolve(platformName()).resolve("jdk").resolve("bin").resolve(java),
                runtimeRoot.resolve(platformName()).resolve("jre").resolve("bin").resolve(java),

                appRoot.resolve("runtime").resolve("bin").resolve(java),
                appRoot.resolve("jdk").resolve("bin").resolve(java),
                appRoot.resolve("jre").resolve("bin").resolve(java)
        };

        for (Path candidate : direct) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }

        return findFile(appRoot, java, p -> {
            String path = p.toString().toLowerCase(Locale.ROOT);
            return path.contains("jdk") || path.contains("jre") || path.contains("runtime");
        });
    }

    private static Optional<Path> findJavaFxLib(Path appRoot, Path runtimeRoot, Path platformRoot) {
        Path[] direct = new Path[] {
                platformRoot.resolve("javafx-sdk-21.0.3").resolve("lib"),
                platformRoot.resolve("javafx-sdk").resolve("lib"),

                runtimeRoot.resolve(platformName()).resolve("javafx-sdk-21.0.3").resolve("lib"),
                runtimeRoot.resolve(platformName()).resolve("javafx-sdk").resolve("lib"),

                appRoot.resolve("javafx-sdk").resolve("lib"),
                appRoot.resolve("runtime").resolve("javafx-sdk").resolve("lib")
        };

        for (Path candidate : direct) {
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }

        return findDirectory(appRoot, p -> {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.startsWith("javafx-sdk");
        }).map(p -> p.resolve("lib"))
                .filter(Files::isDirectory)
                .map(Path::toAbsolutePath)
                .map(Path::normalize);
    }

    private static Optional<Path> findFile(Path root, String fileName) {
        return findFile(root, fileName, p -> true);
    }

    private static Optional<Path> findFile(Path root, String fileName, Predicate<Path> predicate) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(predicate)
                    .min(Comparator.comparingInt(Path::getNameCount));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findDirectory(Path root, Predicate<Path> predicate) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(predicate)
                    .min(Comparator.comparingInt(Path::getNameCount));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static IllegalStateException missing(String dependency, Path appRoot) {
        return new IllegalStateException(
                "Unable to find " + dependency + " under " + appRoot
                        + ". Expected either historical layout with linux/ or windows/, "
                        + "or modern layout with runtime/common and runtime/" + platformName() + "."
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
        public File bbmapDirectoryFile() {
            return bbmapDirectory.toFile();
        }

        public File blastBinDirectoryFile() {
            return blastBinDirectory.toFile();
        }

        public File javaBinDirectoryFile() {
            return javaExecutable.getParent().toFile();
        }

        public String javaCommand() {
            return javaExecutable.toString();
        }

        public Path blastnExecutable() {
            return AppPaths.executable(blastBinDirectory, "blastn");
        }

        public Path makeblastdbExecutable() {
            return AppPaths.executable(blastBinDirectory, "makeblastdb");
        }

        public Path bbdukScript() {
            return bbmapDirectory.resolve(AppPaths.isWindows() ? "bbduk.bat" : "bbduk.sh");
        }

        public Path bbmapScript() {
            return bbmapDirectory.resolve(AppPaths.isWindows() ? "bbmap.bat" : "bbmap.sh");
        }

        public Path tadpoleScript() {
            return bbmapDirectory.resolve(AppPaths.isWindows() ? "tadpole.bat" : "tadpole.sh");
        }

        public boolean hasJavaFx() {
            return javafxLibDirectory != null && Files.isDirectory(javafxLibDirectory);
        }
    }
}
