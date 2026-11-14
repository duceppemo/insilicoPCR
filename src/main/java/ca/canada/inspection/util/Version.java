package ca.canada.inspection.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The version of insilicoPCR, as {@code pom.xml} gives it: the one place it is written. */
public final class Version {

    /** Such as {@code 0.15.1}. */
    public static final String VERSION = read();

    private Version() {
    }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
