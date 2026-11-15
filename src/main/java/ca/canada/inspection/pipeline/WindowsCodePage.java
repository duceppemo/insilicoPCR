package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Windows' code page, which the programs a run starts receive their arguments in.
 *
 * <p>BLAST+ and the Java runtime that runs BBTools read their command line in the computer's code page
 * (windows-1252 on a computer set up for English or French): a letter it does not have, such as
 * {@code 数} or {@code Δ}, reaches them as {@code ?}. The application, which takes its paths from the
 * window, ran into it only once makeblastdb had failed, with "exit code 2". Checked before any
 * program runs. Accented letters ({@code é}) are in the code page and are fine.</p>
 */
final class WindowsCodePage {
    private WindowsCodePage() {
    }

    /** The computer's code page: the encoding of the programs' command lines. */
    static Charset system() {
        try {
            return Charset.forName(System.getProperty("native.encoding"));
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }

    /** Checks the paths a run gives its programs, on Windows only. */
    static void check(PipelineConfig config, RuntimeDependencies dependencies, Collection<Sample> samples) {
        if (!AppPaths.isWindows()) {
            return;
        }
        List<Path> paths = new ArrayList<>(List.of(config.output(), config.primers(), dependencies.appRoot()));
        samples.forEach(sample -> paths.addAll(sample.files()));
        check(system(), paths);
    }

    /** @throws PipelineException naming the first of {@code paths} that {@code codePage} cannot hold */
    static void check(Charset codePage, List<Path> paths) {
        CharsetEncoder encoder = codePage.newEncoder();
        for (Path path : paths) {
            String text = path.toAbsolutePath().toString();
            if (encoder.canEncode(text)) {
                continue;
            }
            StringBuilder missing = new StringBuilder();
            text.codePoints().filter(c -> !encoder.canEncode(Character.toString(c)))
                    .distinct().forEach(missing::appendCodePoint);
            throw new PipelineException("The path " + text + " has a letter (" + missing + ") that is not in this"
                    + " computer's code page (" + codePage.name() + "): BLAST+ and BBTools would receive it as ?."
                    + " Rename the folder or file, or turn on \"Use Unicode UTF-8 for worldwide language support\""
                    + " in Windows' language settings (Administrative language settings, Change system locale).");
        }
    }
}
