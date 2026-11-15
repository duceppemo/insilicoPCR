package ca.canada.inspection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The packages below the window draw nothing: JavaFX is only in {@code gel} and {@code insilicopcr}. */
class PackageRulesTest {

    @Test
    void onlyTheGelAndTheWindowUseJavaFx() throws IOException {
        Path sources = Path.of("src", "main", "java", "ca", "canada", "inspection");
        List<String> offenders;
        try (Stream<Path> files = Stream.of("model", "util", "pcr", "report", "pipeline", "dispatchpcr")
                .map(sources::resolve).flatMap(PackageRulesTest::javaFiles)) {
            offenders = files.filter(PackageRulesTest::importsJavaFx).map(Path::toString).sorted().toList();
        }
        assertEquals(List.of(), offenders);
    }

    /**
     * The layers of Architecture.md, lowest first: a package imports only from itself and the
     * layers below it. The two front ends, the dispatcher and the window, sit on top; the
     * dispatcher starts the window.
     */
    private static final List<List<String>> LAYERS = List.of(
            List.of("model"), List.of("util"), List.of("pcr"), List.of("report"), List.of("gel"),
            List.of("pipeline"), List.of("insilicopcr"), List.of("dispatchpcr"));

    @Test
    void eachPackageImportsOnlyFromTheLayersBelowIt() throws IOException {
        Path sources = Path.of("src", "main", "java", "ca", "canada", "inspection");
        List<String> offenders = new java.util.ArrayList<>();
        for (int layer = 0; layer < LAYERS.size(); layer++) {
            java.util.Set<String> allowed = new java.util.HashSet<>();
            for (int below = 0; below <= layer; below++) {
                allowed.addAll(LAYERS.get(below));
            }
            for (String pkg : LAYERS.get(layer)) {
                try (Stream<Path> files = javaFiles(sources.resolve(pkg))) {
                    for (Path file : files.toList()) {
                        for (String line : Files.readAllLines(file)) {
                            java.util.regex.Matcher imported = java.util.regex.Pattern
                                    .compile("^import (?:static )?ca\\.canada\\.inspection\\.(\\w+)\\.").matcher(line);
                            if (imported.find() && !allowed.contains(imported.group(1))) {
                                offenders.add(sources.relativize(file) + ": " + line);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    private static Stream<Path> javaFiles(Path directory) {
        try {
            return Files.walk(directory).filter(file -> file.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean importsJavaFx(Path file) {
        try {
            return Files.readAllLines(file).stream().anyMatch(line -> line.startsWith("import javafx."));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
