package ca.canada.inspection.util;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Utility methods for FASTA/FASTQ file handling shared by CLI and GUI paths. */
public final class SequenceFileUtils {
    private SequenceFileUtils() {}

    public static final String[] FASTA_EXTENSIONS = {".fasta", ".ffn", ".fna", ".fsa", ".fa",
            ".fasta.gz", ".ffn.gz", ".fna.gz", ".fsa.gz", ".fa.gz"};
    public static final String[] FASTQ_EXTENSIONS = {".fastq", ".fq", ".fastq.gz", ".fq.gz"};
    public static final String[] ACCEPTED_EXTENSIONS = {".fasta", ".ffn", ".fna", ".fsa", ".fa", ".fastq", ".fq",
            ".fasta.gz", ".ffn.gz", ".fna.gz", ".fsa.gz", ".fa.gz", ".fastq.gz", ".fq.gz"};

    private static final Pattern SEQUENCE_EXTENSION = Pattern.compile("(?i)\\.(fasta|ffn|fna|fsa|fa|fastq|fq)(\\.gz)?$");
    private static final Pattern READ_PAIR_SUFFIX = Pattern.compile("(?i)_R[12](_001)?$");
    // FASTQ reads from ENA/SRA (fasterq-dump) are named <run>_1 / <run>_2.
    private static final Pattern BARE_FASTQ_PAIR_SUFFIX = Pattern.compile("_([12])$");

    public static boolean hasExtension(String filename, String[] extensions) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).anyMatch(lower::endsWith);
    }

    public static boolean hasExtension(Path path, String[] extensions) {
        return hasExtension(path.getFileName().toString(), extensions);
    }

    public static String sampleName(Path path) {
        Path directory = path.toAbsolutePath().getParent();
        return sampleName(path.getFileName().toString(),
                sibling -> directory != null && Files.exists(directory.resolve(sibling)));
    }

    /**
     * Sample name of {@code fileName} with the same rules as {@link #sampleName(Path)},
     * asking {@code siblingExists} instead of the disk whether a mate file is present
     * (e.g. when the names come from a run's QA log and the files may have moved).
     */
    public static String sampleName(String fileName, java.util.function.Predicate<String> siblingExists) {
        String base = SEQUENCE_EXTENSION.matcher(fileName).replaceFirst("");
        String withoutIlluminaSuffix = READ_PAIR_SUFFIX.matcher(base).replaceFirst("");
        if (!withoutIlluminaSuffix.equals(base)) {
            return withoutIlluminaSuffix;
        }
        // The bare _1/_2 convention is ambiguous (sequentially numbered samples
        // use it too), so it only counts as a read pair when the mate file is
        // actually present and no _3 sibling suggests sequential numbering.
        // It is also never applied to FASTA files: strain_1.fasta and
        // strain_2.fasta are distinct samples.
        if (hasExtension(fileName, FASTQ_EXTENSIONS)) {
            Matcher mate = BARE_FASTQ_PAIR_SUFFIX.matcher(base);
            if (mate.find() && isPartOfReadPair(siblingExists, fileName, base, mate.group(1))) {
                return base.substring(0, mate.start());
            }
        }
        return base;
    }

    private static boolean isPartOfReadPair(java.util.function.Predicate<String> siblingExists, String fileName,
                                            String base, String digit) {
        String extension = fileName.substring(base.length());
        String stem = base.substring(0, base.length() - 2);
        String mateName = stem + "_" + ("1".equals(digit) ? "2" : "1") + extension;
        return siblingExists.test(mateName) && !siblingExists.test(stem + "_3" + extension);
    }

    public static String fileType(Path file) {
        if (hasExtension(file, FASTA_EXTENSIONS)) {
            return "fasta";
        }
        if (hasExtension(file, FASTQ_EXTENSIONS)) {
            return "fastq";
        }
        return "unknown";
    }

    public static String fileType(java.io.File file) {
        return fileType(file.toPath());
    }

    public static boolean looksLikeSequenceFile(Path file) {
        if (!Files.isRegularFile(file) || !hasExtension(file, ACCEPTED_EXTENSIONS)) {
            return false;
        }
        try (BufferedReader reader = openMaybeGzip(file)) {
            String first = reader.readLine();
            return first != null && !first.isBlank() && (first.charAt(0) == '>' || first.charAt(0) == '@');
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean looksLikeSequenceFile(java.io.File file) {
        return looksLikeSequenceFile(file.toPath());
    }

    public static Map<String, Sample> createSampleMap(Path inputFile) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        if (Files.isDirectory(inputFile)) {
            try (Stream<Path> entries = Files.list(inputFile)) {
                entries.filter(SequenceFileUtils::looksLikeSequenceFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(entry -> addSampleFile(samples, entry));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to list input directory: " + inputFile, e);
            }
        } else if (looksLikeSequenceFile(inputFile)) {
            addSampleFile(samples, inputFile);
        }
        return samples;
    }

    /** The samples that {@code files} make, by name, whatever the files hold. */
    static Map<String, Sample> sampleMapOf(List<Path> files) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        files.forEach(file -> addSampleFile(samples, file));
        return samples;
    }

    public static Map<String, Sample> createSampleMap(java.io.File inputFile) {
        return createSampleMap(Objects.requireNonNull(inputFile, "inputFile").toPath());
    }

    private static void addSampleFile(Map<String, Sample> samples, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        String name = sampleName(normalized);
        Sample sample = samples.computeIfAbsent(name, key -> {
            Sample created = new Sample();
            created.setName(key);
            created.setFileType(fileType(normalized));
            return created;
        });
        sample.addFile(normalized);
    }

    public static BufferedReader openMaybeGzip(Path file) throws IOException {
        // Decoded leniently either way: a byte that is not UTF-8 (an accented letter in a
        // header, say) becomes a replacement character instead of ending the run.
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                    StandardCharsets.UTF_8));
        }
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    public static BufferedReader openMaybeGzip(java.io.File file) throws IOException {
        return openMaybeGzip(file.toPath());
    }

    /**
     * The ID of a FASTA header line: the text after {@code >} up to the first
     * whitespace, which is what BLAST reports as {@code qseqid}.
     */
    public static String fastaId(String headerLine) {
        String header = headerLine.substring(1).strip();
        int space = firstWhitespace(header);
        return space < 0 ? header : header.substring(0, space);
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
