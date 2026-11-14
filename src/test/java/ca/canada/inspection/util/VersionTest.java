package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
