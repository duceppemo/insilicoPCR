package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.SequenceFileUtils;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.PrimerId;
import ca.canada.inspection.pcr.PrimerPanel;

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

    private PrimerService() {
    }

    /**
     * Reads the run's primer file, expands degenerate bases, and writes the primers for BLAST.
     *
     * @return the validated primers, degenerate ones expanded, with the sizes the file declares
     */
    public static PrimerPanel loadAndPrepare(PipelineConfig config, PipelineDirectories directories) {
        PrimerFile file = read(config.primers());
        Map<String, String> preparedPrimers = expandPrimers(file.sequences());
        writePrimerDatabase(directories.primerDatabase(), preparedPrimers);
        return PrimerPanel.of(preparedPrimers, file.expectedSizes());
    }

    /** Validates the IDs and sequences and expands degenerate bases. */
    static Map<String, String> expand(Map<String, String> rawPrimers) {
        return expandPrimers(rawPrimers);
    }

    /**
     * Reads a primer FASTA file. The primer ID is the header up to the first blank, as
     * BLAST names the sequence; what follows is a description, read for product size
     * declarations and otherwise ignored. Every primer ID must follow the naming
     * convention, and every assay must have a forward and a reverse primer: an assay
     * without one could never give a product, and would look negative in every sample.
     */
    public static PrimerFile read(Path file) {
        Objects.requireNonNull(file, "file");
        Map<String, String> fasta = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();

        // Gzipped or not, as the window's file chooser offers both.
        try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(file)) {
            String currentId = null;
            StringBuilder sequence = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(">")) {
                    putFastaEntry(fasta, currentId, sequence, file);
                    currentId = SequenceFileUtils.fastaId(line);
                    if (descriptions.containsKey(currentId)) {
                        // Silently keeping one of the two would miss the other's products.
                        throw new PipelineException("Primer ID " + currentId + " appears twice in the primer file "
                                + file + "; give each primer its own ID (" + currentId + "_1, " + currentId + "_2)");
                    }
                    descriptions.put(currentId, SequenceFileUtils.fastaDescription(line));
                    sequence.setLength(0);
                } else {
                    sequence.append(line);
                }
            }
            putFastaEntry(fasta, currentId, sequence, file);
        } catch (IOException e) {
            throw new PipelineException("Could not parse primer FASTA file: " + file, e);
        }
        if (fasta.isEmpty()) {
            throw new PipelineException("The primer file " + file + " has no primer: it must be FASTA, each primer"
                    + " a line starting with > and its ID, then its sequence");
        }
        descriptions.keySet().retainAll(fasta.keySet());
        fasta.keySet().forEach(PrimerService::validatePrimerId);
        checkAssaysComplete(fasta.keySet(), file);
        try {
            return new PrimerFile(fasta, ExpectedSizes.fromHeaders(descriptions));
        } catch (IllegalArgumentException e) {
            // The message says it all; without the cause, which reads as a bug (RunFailure).
            throw new PipelineException(e.getMessage() + " (primer file " + file + ")");
        }
    }

    private static void putFastaEntry(Map<String, String> fasta, String id, StringBuilder sequence, Path file) {
        if (id == null) {
            if (!sequence.isEmpty()) {
                // Without a header, these bases have no ID: they would not be searched.
                throw new PipelineException("The primer file " + file + " has a sequence before its first"
                        + " header line (a line starting with > and the primer ID)");
            }
            return;
        }
        if (sequence.isEmpty()) {
            // Left out, a probe without a sequence would turn its qPCR assay into a plain PCR
            // assay, reported on its primers alone.
            throw new PipelineException("Primer " + id + " has no sequence in the primer file " + file);
        }
        fasta.put(id, sequence.toString());
    }

    private static Map<String, String> expandPrimers(Map<String, String> primers) {
        Map<String, String> expandedPrimers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : primers.entrySet()) {
            String id = entry.getKey();
            validatePrimerId(id);
            String sequence = normalizePrimerSequence(id, entry.getValue());
            List<String> expanded = expandDegenerateBases(id, sequence);

            if (expanded.size() == 1 && expanded.getFirst().equals(sequence)) {
                expandedPrimers.put(id, sequence);
            } else {
                for (int i = 0; i < expanded.size(); i++) {
                    String variant = id + "_" + i;
                    if (primers.containsKey(variant) || expandedPrimers.containsKey(variant)) {
                        // One would silently replace the other, and its products be missed.
                        throw new PipelineException("Primer " + id + " is degenerate: its sequences are named "
                                + id + "_0 to " + id + "_" + (expanded.size() - 1) + ", and " + variant
                                + " is also the ID of a primer; give that primer another ID");
                    }
                    expandedPrimers.put(variant, expanded.get(i));
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
        if (PrimerId.parse(id) == null) {
            throw new PipelineException("Primer ID must follow the <assay>-F / <assay>-R / <assay>-P naming convention: " + id);
        }
    }

    /** Fails on an assay that lacks a forward or a reverse primer, naming the primers it lacks. */
    private static void checkAssaysComplete(Iterable<String> ids, Path file) {
        List<String> missing = new ArrayList<>();
        PrimerId.byAssay(ids).forEach((assay, primers) -> {
            if (primers.forward().isEmpty()) {
                missing.add(assay + "-F");
            }
            if (primers.reverse().isEmpty()) {
                missing.add(assay + "-R");
            }
        });
        if (!missing.isEmpty()) {
            throw new PipelineException("Every assay needs a forward (-F) and a reverse (-R) primer, but "
                    + String.join(", ", missing) + (missing.size() == 1 ? " is" : " are") + " missing from the"
                    + " primer file " + file);
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

    /**
     * Most variants one degenerate oligo may expand to: 4^8, eight N. More would make a BLAST
     * database of millions of oligos (a long run that may run out of memory), not a search.
     */
    static final long MAX_VARIANTS = 65_536;

    private static List<String> expandDegenerateBases(String id, String sequence) {
        long variants = 1;
        for (char base : sequence.toCharArray()) {
            char[] replacements = DEGENERATE_BASES.get(base);
            if (replacements != null) {
                variants *= replacements.length;
                if (variants > MAX_VARIANTS) {
                    throw new PipelineException("Primer " + id + " is too degenerate: it stands for more than "
                            + MAX_VARIANTS + " sequences (inosine written as N counts as four bases)");
                }
            }
        }
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
