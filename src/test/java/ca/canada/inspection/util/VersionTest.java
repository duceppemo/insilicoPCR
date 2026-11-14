package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void theVersionIsThePoms() throws IOException {
        // The project's own <version>, the first one after </parent> or the coordinates.
        Matcher pom = Pattern.compile("<artifactId>insilicoPCR</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(pom.find(), "no version in pom.xml");

        assertEquals(pom.group(1), Version.VERSION);
    }

    @Test
    void aFileTheBuildDidNotFillInIsAnError() {
        // As in the source tree, before Maven replaces the placeholder.
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Version.parse(
                new ByteArrayInputStream("version=${project.version}\n".getBytes())));

        assertEquals("version.properties was not filled in by the build: ${project.version}", error.getMessage());
        assertThrows(IllegalStateException.class, () -> Version.parse(new ByteArrayInputStream(new byte[0])));
        assertThrows(IllegalStateException.class, () -> Version.parse(null));
    }

    @Test
    void withoutAFilledInFileTheVersionIsUnknownAndNothingFails() {
        // An IDE's own build leaves the placeholder: the window must still open.
        assertEquals(Version.UNKNOWN, Version.orUnknown(null));
        assertEquals(Version.UNKNOWN, Version.orUnknown(new ByteArrayInputStream("version=${project.version}".getBytes())));
        assertEquals("1.2.3", Version.orUnknown(new ByteArrayInputStream("version=1.2.3".getBytes())));
    }

    @Test
    void theVersionIsReadFromTheFile() throws IOException {
        assertEquals("1.2.3", Version.parse(new ByteArrayInputStream("# comment\nversion=1.2.3\n".getBytes())));
    }
}
