package ca.canada.inspection.util;

import ca.canada.inspection.model.Sample;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Utility methods for FASTA/FASTQ file handling shared by CLI and GUI paths. */
public final class SequenceFileUtils {
    private SequenceFileUtils() {}

    /** The names of assemblies and reads, each also gzipped: the one list, which the others are made from. */
    private static final List<String> FASTA_NAMES = List.of("fasta", "ffn", "fna", "fsa", "fa");
    private static final List<String> FASTQ_NAMES = List.of("fastq", "fq");

    public static final List<String> FASTA_EXTENSIONS = withGzipped(FASTA_NAMES);
    public static final List<String> FASTQ_EXTENSIONS = withGzipped(FASTQ_NAMES);
    /** Every sequence file the input may hold: {@link #FASTA_EXTENSIONS} and {@link #FASTQ_EXTENSIONS}. */
    public static final List<String> ACCEPTED_EXTENSIONS =
            java.util.stream.Stream.concat(FASTA_EXTENSIONS.stream(), FASTQ_EXTENSIONS.stream()).toList();

    private static final Pattern SEQUENCE_EXTENSION = Pattern.compile("(?i)\\.("
            + String.join("|", java.util.stream.Stream.concat(FASTA_NAMES.stream(), FASTQ_NAMES.stream()).toList())
            + ")(\\.gz)?$");

    private static List<String> withGzipped(List<String> names) {
        return java.util.stream.Stream.concat(names.stream().map(name -> "." + name),
                names.stream().map(name -> "." + name + ".gz")).toList();
    }

    private static final Pattern READ_PAIR_SUFFIX = Pattern.compile("(?i)_R([12])(_001)?$");
    // FASTQ reads from ENA/SRA (fasterq-dump) are named <run>_1 / <run>_2.
    private static final Pattern BARE_FASTQ_PAIR_SUFFIX = Pattern.compile("_([12])$");

    public static boolean hasExtension(String filename, List<String> extensions) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    public static boolean hasExtension(Path path, List<String> extensions) {
        return hasExtension(path.getFileName().toString(), extensions);
    }

    public static String sampleName(Path path) {
        return naming(path).sample();
    }

    /**
     * Which mate of a read pair {@code path} is: 1 or 2, or 0 for a file that is not one
     * (any FASTA file, and a FASTQ file without a mate suffix).
     */
    public static int mate(Path path) {
        return naming(path).mate();
    }

    /**
     * Sample name of {@code fileName} with the same rules as {@link #sampleName(Path)},
     * asking {@code siblingExists} instead of the disk whether a mate file is present
     * (e.g. when the names come from a run's QA log and the files may have moved).
     */
    public static String sampleName(String fileName, java.util.function.Predicate<String> siblingExists) {
        return naming(fileName, siblingExists).sample();
    }

    /** The sample a file belongs to and which mate of a read pair it is (0: none). */
    private record Naming(String sample, int mate) {
    }

    private static Naming naming(Path path) {
        Path directory = path.toAbsolutePath().getParent();
        return naming(path.getFileName().toString(),
                sibling -> directory != null && Files.exists(directory.resolve(sibling)));
    }

    private static Naming naming(String fileName, java.util.function.Predicate<String> siblingExists) {
        String base = SEQUENCE_EXTENSION.matcher(fileName).replaceFirst("");
        // Read pairs are FASTQ files only: an assembly named strain_R1.fasta or strain_1.fasta
        // is the sample strain_R1 or strain_1, so that a FASTA sample is always one file.
        if (!hasExtension(fileName, FASTQ_EXTENSIONS)) {
            return new Naming(base, 0);
        }
        Matcher illumina = READ_PAIR_SUFFIX.matcher(base);
        if (illumina.find()) {
            return new Naming(base.substring(0, illumina.start()), Integer.parseInt(illumina.group(1)));
        }
        // The bare _1/_2 convention is ambiguous (sequentially numbered samples
        // use it too), so it only counts as a read pair when the mate file is
        // actually present and no _3 sibling suggests sequential numbering.
        Matcher mate = BARE_FASTQ_PAIR_SUFFIX.matcher(base);
        if (mate.find() && isPartOfReadPair(siblingExists, fileName, base, mate.group(1))) {
            return new Naming(base.substring(0, mate.start()), Integer.parseInt(mate.group(1)));
        }
        return new Naming(base, 0);
    }

    private static boolean isPartOfReadPair(java.util.function.Predicate<String> siblingExists, String fileName,
                                            String base, String digit) {
        String extension = fileName.substring(base.length());
        String stem = base.substring(0, base.length() - 2);
        String mateName = stem + "_" + ("1".equals(digit) ? "2" : "1") + extension;
        return siblingExists.test(mateName) && !siblingExists.test(stem + "_3" + extension);
    }

    public static Sample.FileType fileType(Path file) {
        if (hasExtension(file, FASTA_EXTENSIONS)) {
            return Sample.FileType.FASTA;
        }
        if (hasExtension(file, FASTQ_EXTENSIONS)) {
            return Sample.FileType.FASTQ;
        }
        return Sample.FileType.UNKNOWN;
    }

    public static boolean looksLikeSequenceFile(Path file) {
        if (!Files.isRegularFile(file) || !hasExtension(file, ACCEPTED_EXTENSIONS)) {
            return false;
        }
        try (BufferedReader reader = openMaybeGzip(file)) {
            // Blank lines before the first record are skipped, as the input check skips them.
            String first = reader.readLine();
            while (first != null && first.isBlank()) {
                first = reader.readLine();
            }
            return first != null && (first.charAt(0) == '>' || first.charAt(0) == '@');
        } catch (IOException e) {
            return false;
        }
    }

    /** The samples that {@code files} make, by name, whatever the files hold. */
    static Map<String, Sample> sampleMapOf(List<Path> files) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        files.forEach(file -> addSampleFile(samples, file));
        return samples;
    }

    private static void addSampleFile(Map<String, Sample> samples, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        String name = sampleName(normalized);
        Sample sample = samples.computeIfAbsent(name, key -> {
            Sample created = new Sample(key);
            created.setFileType(fileType(normalized));
            return created;
        });
        sample.addFile(normalized);
    }

    public static BufferedReader openMaybeGzip(Path file) throws IOException {
        // Decoded leniently either way: a byte that is not UTF-8 (an accented letter in a
        // header, say) becomes a replacement character instead of ending the run.
        // A .gz that is not gzip fails as it is opened: the file is then closed here, or it stayed open
        // until the garbage collector came by (on Windows, locked: it could not be renamed meanwhile).
        InputStream raw = Files.newInputStream(file);
        BufferedReader reader;
        try {
            InputStream in = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")
                    ? new GZIPInputStream(raw) : raw;
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
        return withoutByteOrderMark(reader);
    }

    /** The UTF-8 byte order mark, which Notepad and other Windows editors write at the start of a file. */
    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * {@code in} past the UTF-8 byte order mark its bytes start with, if any: for what reads a
     * sequence file as bytes ({@link FastqCheck}, the copy given to blastn), as
     * {@link #openMaybeGzip} skips it for what reads it as text.
     */
    public static InputStream withoutByteOrderMark(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 1 << 16);
        buffered.mark(BYTE_ORDER_MARK.length);
        if (!Arrays.equals(buffered.readNBytes(BYTE_ORDER_MARK.length), BYTE_ORDER_MARK)) {
            buffered.reset();
        }
        return buffered;
    }

    /** Whether {@code file}'s bytes start with the UTF-8 byte order mark (a gzipped file's are not read). */
    public static boolean startsWithByteOrderMark(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(BYTE_ORDER_MARK.length), BYTE_ORDER_MARK);
        }
    }

    /**
     * {@code reader} past the byte order mark its text starts with, if any. Notepad and other Windows
     * editors save UTF-8 with one; read as a character, it stood before the first {@code >}, and a primer
     * file saved so was refused for "a sequence before its first header line".
     */
    private static BufferedReader withoutByteOrderMark(BufferedReader reader) throws IOException {
        try {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            return reader;
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
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

    /** The description of a FASTA header line: what follows its ID, trimmed; empty without one. */
    public static String fastaDescription(String headerLine) {
        String header = headerLine.substring(1).strip();
        int space = firstWhitespace(header);
        return space < 0 ? "" : header.substring(space + 1).strip();
    }

    /**
     * Sequence lengths of a FASTA file keyed by ID ({@link #fastaId}, as BLAST reports it), read
     * without building lines ({@link FastaScanner}).
     */
    public static Map<String, Integer> sequenceLengths(Path fasta) {
        Map<String, Integer> lengths = new HashMap<>();
        int[] length = new int[1];
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            String header = scanner.nextHeader(null);
            while (header != null) {
                length[0] = 0;
                String next = scanner.nextHeader(base -> length[0]++);
                lengths.put(fastaId(header), length[0]);
                header = next;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read sequence lengths from: " + fasta, e);
        }
        return lengths;
    }
}
