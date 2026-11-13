package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import ca.canada.inspection.util.SequenceFileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reads the input-file list back from a run's QAlog.txt (used to reopen a previous run). */
public final class QaLogInputs {
    static final String INPUTS_MARKER = "Input File(s) :";
    /** Lines written after the input list since 0.10.0; logs from 0.10.0 have no blank line before them. */
    private static final String FASTQ_SECTION_PREFIX = "FASTQ mode";

    /** Starts the line that names a sample left out for a faulty input file (since 0.13.0). */
    static final String NOT_ANALYSED_PREFIX = "Sample not analysed: ";

    private QaLogInputs() {
    }

    /**
     * @return the samples the run left out because a file of theirs was faulty. Their files
     *         are in the input list all the same, but nothing was run on them: they are not
     *         samples without a product.
     */
    public static Set<String> samplesNotAnalysed(Path qaLog) {
        Set<String> samples = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(qaLog, StandardCharsets.UTF_8)) {
                if (line.startsWith(NOT_ANALYSED_PREFIX)) {
                    samples.add(line.substring(NOT_ANALYSED_PREFIX.length()).strip());
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to read QAlog: " + qaLog, e);
        }
        return samples;
    }

    /**
     * @return the input paths listed after {@code Input File(s) :}, up to the first blank
     *         line or FASTQ-mode line, in file order
     */
    public static List<String> read(Path qaLog) {
        List<String> inputs = new ArrayList<>();
        boolean readingInputs = false;
        try {
            for (String line : Files.readAllLines(qaLog, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!readingInputs) {
                    readingInputs = trimmed.equals(INPUTS_MARKER);
                    continue;
                }
                if (trimmed.isEmpty() || trimmed.startsWith(FASTQ_SECTION_PREFIX)) {
                    break;
                }
                inputs.add(trimmed);
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to read QAlog input list: " + qaLog, e);
        }
        return inputs;
    }

    /**
     * Sample names of a run, from its logged inputs, as the pipeline named them: only
     * sequence files count (a folder input is logged entry by entry, so other files
     * and subfolders appear in the list but were never run), and bare {@code _1}/{@code _2}
     * mates are paired using the logged list itself, so the names stay right after the
     * input files have been moved or deleted.
     */
    public static Set<String> sampleNames(List<String> inputs) {
        Map<String, Set<String>> namesByFolder = new HashMap<>();
        List<Path> sequenceFiles = new ArrayList<>();
        for (String input : inputs) {
            Path path;
            try {
                path = Path.of(input);
            } catch (InvalidPathException e) {
                continue; // not a path this system can name; it cannot be a sample either
            }
            if (path.getFileName() == null || Files.isDirectory(path)
                    || !SequenceFileUtils.hasExtension(path.getFileName().toString(), SequenceFileUtils.ACCEPTED_EXTENSIONS)) {
                continue;
            }
            sequenceFiles.add(path);
            namesByFolder.computeIfAbsent(folderKey(path), ignored -> new HashSet<>())
                    .add(path.getFileName().toString());
        }
        Set<String> samples = new TreeSet<>();
        for (Path file : sequenceFiles) {
            Set<String> siblings = namesByFolder.get(folderKey(file));
            samples.add(SequenceFileUtils.sampleName(file.getFileName().toString(), siblings::contains));
        }
        return samples;
    }

    private static String folderKey(Path file) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        return parent == null ? "" : parent.toString();
    }
}
