package ca.canada.inspection.pipeline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Starts a BBTools program, in the child Java runtime, from a {@code bbtools.jar} whose path has a
 * letter outside ASCII: an installation in {@code C:\Users\Rémi} or {@code D:\Données}.
 *
 * <p>BBTools finds its {@code resources} folder from the URL its classes were loaded from, decoding
 * the percent codes of that URL one byte to a character. A jar given with {@code -cp} has each such
 * letter percent-encoded as its UTF-8 bytes, so BBTools looked for its resources in
 * {@code Donn??es} and stopped: "Cannot find primes.txt.gz", then a NullPointerException, and no
 * FASTQ sample could be run. This loads the jar from a URL that leaves the path as it is.</p>
 *
 * <p>usage: {@code java -cp <insilicoPCR> ca.canada.inspection.pipeline.BbtoolsLauncher <bbtools.jar> <main class> [arguments]}</p>
 */
public final class BbtoolsLauncher {

    private BbtoolsLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 2) {
            System.err.println("usage: BbtoolsLauncher <bbtools.jar> <main class> [arguments]");
            System.exit(2);
        }
        ClassLoader bbtools = new URLClassLoader(new URL[]{asIs(Path.of(args[0]))},
                ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(bbtools);
        Method main = Class.forName(args[1], true, bbtools).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) Arrays.copyOfRange(args, 2, args.length));
        } catch (InvocationTargetException e) {
            // As if the program had been started itself: its exception, its exit code (1).
            throw e.getCause();
        }
    }

    /** Whether BBTools would find its resources from {@code jar} given with {@code -cp}. */
    static boolean needed(Path jar) {
        return !StandardCharsets.US_ASCII.newEncoder().canEncode(jar.toAbsolutePath().toString());
    }

    /**
     * A {@code file:} URL of {@code jar} with its path as it is: no letter, and no space, percent-encoded.
     * A space left as {@code %20} would do no harm alone, but BBTools copies a path that has any percent
     * code byte by byte, which garbles the letters outside ASCII.
     */
    @SuppressWarnings("deprecation") // URL(String, String, String) is the constructor that encodes nothing
    static URL asIs(Path jar) throws MalformedURLException {
        String path = jar.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new URL("file", "", path.startsWith("/") ? path : "/" + path);
    }
}
