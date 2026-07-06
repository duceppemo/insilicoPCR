package ca.canada.inspection.util;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    public static boolean hasExtension(String filename, String[] extensions) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).anyMatch(lower::endsWith);
    }

    public static boolean hasExtension(Path path, String[] extensions) {
        return hasExtension(path.getFileName().toString(), extensions);
    }

    public static String sampleName(Path path) {
        String name = path.getFileName().toString();
        name = SEQUENCE_EXTENSION.matcher(name).replaceFirst("");
        name = READ_PAIR_SUFFIX.matcher(name).replaceFirst("");
        return name;
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
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file))));
        }
        return Files.newBufferedReader(file);
    }

    public static BufferedReader openMaybeGzip(java.io.File file) throws IOException {
        return openMaybeGzip(file.toPath());
    }
}
