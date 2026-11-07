package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** File I/O around {@link FastqDiagnostics}: contig lengths in, TSV out. */
public final class FastqDiagnosticsReport {

    static final String HEADER = String.join("\t",
            "Sample", "Gene", "Status", "Assembly", "ReadsWithForward", "ReadsWithReverse", "ReadsWithProbe",
            "CheckableLength", "ReadDetail", "AssemblyDetail");

    private FastqDiagnosticsReport() {
    }

    /** Sequence lengths of a FASTA file keyed by ID (header up to the first whitespace, as BLAST reports it). */
    public static Map<String, Integer> contigLengths(Path fasta) {
        Map<String, Integer> lengths = new HashMap<>();
        try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(fasta)) {
            String id = null;
            int length = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(">")) {
                    if (id != null) {
                        lengths.put(id, length);
                    }
                    String header = line.substring(1).trim();
                    id = header.split("\\s+", 2)[0];
                    length = 0;
                } else {
                    length += line.strip().length();
                }
            }
            if (id != null) {
                lengths.put(id, length);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read contigs: " + fasta, e);
        }
        return lengths;
    }

    /** Writes the findings as TSV; the header is written even when there are none. */
    public static void write(Path file, List<Finding> findings) {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (Finding finding : findings) {
                writer.write(String.join("\t",
                        finding.sample(),
                        finding.assay(),
                        finding.status().name(),
                        finding.assembly().name(),
                        String.valueOf(finding.readsWithOligo().forward()),
                        String.valueOf(finding.readsWithOligo().reverse()),
                        String.valueOf(finding.readsWithOligo().probe()),
                        String.valueOf(finding.checkableLength()),
                        finding.readDetail().replace('\t', ' '),
                        finding.assemblyDetail().replace('\t', ' ')));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write FASTQ diagnostics: " + file, e);
        }
    }
}
