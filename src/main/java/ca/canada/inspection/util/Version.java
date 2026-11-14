package ca.canada.inspection.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The version of insilicoPCR, as {@code pom.xml} gives it: the one place it is written. */
public final class Version {

    /**
     * Such as {@code 0.15.1}; {@value #UNKNOWN} from a build that did not go through Maven
     * (an IDE's own compiler), which leaves the file unfilled: the version is shown and
     * logged, never needed to run.
     */
    public static final String VERSION = read();

    static final String UNKNOWN = "unknown (not built by Maven)";

    private Version() {
    }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
            return orUnknown(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    /** The version in the file, or {@link #UNKNOWN} when it is missing or was not filled in. */
    static String orUnknown(InputStream in) {
        try {
            return parse(in);
        } catch (IOException | IllegalStateException e) {
            return UNKNOWN;
        }
    }

    /** The version in a {@code version.properties} as the build writes it. */
    static String parse(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalStateException("version.properties is missing from the build");
        }
        Properties properties = new Properties();
        properties.load(in);
        String version = properties.getProperty("version", "");
        if (version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("version.properties was not filled in by the build: " + version);
        }
        return version;
    }
}
