package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.ExpectedSizes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Loads, validates, expands, and stages primer FASTA data for BLAST/BBMap. */
public final class PrimerService {
    private static final Map<Character, char[]> DEGENERATE_BASES = Map.ofEntries(
            Map.entry('R', new char[]{'A', 'G'}),
            Map.entry('Y', new char[]{'T', 'C'}),
            Map.entry('S', new char[]{'G', 'C'}),
            Map.entry('W', new char[]{'A', 'T'}),
            Map.entry('K', new char[]{'T', 'G'}),
            Map.entry('M', new char[]{'A', 'C'}),
            Map.entry('B', new char[]{'T', 'G', 'C'}),
            Map.entry('D', new char[]{'A', 'T', 'G'}),
            Map.entry('H', new char[]{'A', 'T', 'C'}),
            Map.entry('V', new char[]{'A', 'C', 'G'}),
            Map.entry('N', new char[]{'A', 'T', 'C', 'G'})
    );

    private static final Pattern VALID_PRIMER_SEQUENCE = Pattern.compile("^[ATCGRYSWKMBDHVN]+$");

    /**
     * A primer FASTA file as written by the user.
     *
     * @param sequences     primer sequences by primer ID (the header up to the first blank), in file order
     * @param expectedSizes product sizes the headers declare ({@code size=}, {@code other=})
     */
    public record PrimerFile(Map<String, String> sequences, ExpectedSizes expectedSizes) {
    }

    /**
     * The primers of a run, ready for BLAST.
     *
     * @param primers       validated primers with degenerate bases expanded, by expanded ID
     * @param expectedSizes product sizes the primer file declares
     */
    public record PreparedPrimers(Map<String, String> primers, ExpectedSizes expectedSizes) {
    }

    public PreparedPrimers loadAndPrepare(PipelineConfig config, PipelineDirectories directories) {
        PrimerFile file = read(config.primers());
        Map<String, String> preparedPrimers = expandPrimers(file.sequences());
        writePrimerDatabase(directories.primerDatabase(), preparedPrimers);
        return new PreparedPrimers(preparedPrimers, file.expectedSizes());
    }

    /** Validates and expands degenerate primers; shared with the GUI pipeline. */
    public static Map<String, String> expand(Map<String, String> rawPrimers) {
        return expandPrimers(rawPrimers);
    }

    /** Writes a primer FASTA file; shared with the GUI pipeline. */
    public static void writeFasta(Path output, Map<String, String> primers) {
        writePrimerDatabase(output, primers);
    }

    /**
     * Reads a primer FASTA file; shared with the GUI pipeline. The primer ID is the
     * header up to the first blank, as BLAST names the sequence; what follows is a
     * description, read for product size declarations and otherwise ignored.
     */
    public static PrimerFile read(Path file) {
        Objects.requireNonNull(file, "file");
        Map<String, String> fasta = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String currentId = null;
            StringBuilder sequence = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(">")) {
                    putFastaEntry(fasta, currentId, sequence);
                    String[] header = line.substring(1).trim().split("\\s+", 2);
                    currentId = header[0];
                    descriptions.put(currentId, header.length > 1 ? header[1] : "");
                    sequence.setLength(0);
                } else {
                    sequence.append(line);
                }
            }
            putFastaEntry(fasta, currentId, sequence);
        } catch (IOException e) {
            throw new PipelineException("Could not parse primer FASTA file: " + file, e);
        }
        descriptions.keySet().retainAll(fasta.keySet());
        try {
            return new PrimerFile(fasta, ExpectedSizes.fromHeaders(descriptions));
        } catch (IllegalArgumentException e) {
            throw new PipelineException(e.getMessage() + " (primer file " + file + ")", e);
        }
    }

    private static void putFastaEntry(Map<String, String> fasta, String id, StringBuilder sequence) {
        if (id != null && !sequence.isEmpty()) {
            fasta.put(id, sequence.toString());
        }
    }

    private static Map<String, String> expandPrimers(Map<String, String> primers) {
        Map<String, String> expandedPrimers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : primers.entrySet()) {
            String id = entry.getKey();
            validatePrimerId(id);
            String sequence = normalizePrimerSequence(id, entry.getValue());
            List<String> expanded = expandDegenerateBases(sequence);

            if (expanded.size() == 1 && expanded.getFirst().equals(sequence)) {
                expandedPrimers.put(id, sequence);
            } else {
                for (int i = 0; i < expanded.size(); i++) {
                    expandedPrimers.put(id + "_" + i, expanded.get(i));
                }
            }
        }
        return expandedPrimers;
    }

    /**
     * Fails fast on primer IDs that the report writer cannot assign to an assay.
     * Without this, a bad ID would only surface after the whole BLAST stage, or
     * worse, silently never be reported.
     */
    private static void validatePrimerId(String id) {
        int lastDash = id.lastIndexOf('-');
        boolean valid = lastDash > 0 && lastDash < id.length() - 1
                && (id.charAt(lastDash + 1) == 'F' || id.charAt(lastDash + 1) == 'R' || id.charAt(lastDash + 1) == 'P');
        if (!valid) {
            throw new PipelineException("Primer ID must follow the <assay>-F / <assay>-R / <assay>-P naming convention: " + id);
        }
    }

    private static String normalizePrimerSequence(String id, String sequence) {
        if (sequence == null || sequence.isBlank()) {
            throw new PipelineException("Primer sequence is empty for: " + id);
        }
        String normalized = sequence.trim().toUpperCase();
        if (!VALID_PRIMER_SEQUENCE.matcher(normalized).matches()) {
            throw new PipelineException("Primer sequence contains incompatible characters:\n" + id + "\n" + sequence);
        }
        return normalized;
    }

    private static List<String> expandDegenerateBases(String sequence) {
        ArrayList<String> expanded = new ArrayList<>();
        expandDegenerateBases(sequence.toCharArray(), 0, expanded);
        return expanded;
    }

    private static void expandDegenerateBases(char[] sequence, int start, List<String> output) {
        for (int i = start; i < sequence.length; i++) {
            char[] replacements = DEGENERATE_BASES.get(sequence[i]);
            if (replacements == null) {
                continue;
            }
            char original = sequence[i];
            for (char replacement : replacements) {
                sequence[i] = replacement;
                expandDegenerateBases(sequence, i + 1, output);
            }
            sequence[i] = original;
            return;
        }
        output.add(new String(sequence));
    }

    private static void writePrimerDatabase(Path output, Map<String, String> primers) {
        try {
            Files.createDirectories(output.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : primers.entrySet()) {
                    writer.write(">" + entry.getKey());
                    writer.newLine();
                    writer.write(entry.getValue());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write cleaned primer file: " + output, e);
        }
    }
}
