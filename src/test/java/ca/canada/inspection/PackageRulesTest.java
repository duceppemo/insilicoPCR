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
