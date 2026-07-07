package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;

/** Loads contig descriptions from FASTA headers into each sample. */
final class ContigService {
    void load(PipelineContext context) {
        for (Sample sample : context.samples().values()) {
            if (sample.isFastq()) {
                addContigsFromFasta(sample, sample.getAssemblyFile());
            } else {
                for (Path file : sample.files()) {
                    addContigsFromFasta(sample, file);
                }
            }
        }
    }

    private static void addContigsFromFasta(Sample sample, Path fasta) {
        if (fasta == null) {
            throw new PipelineException("Sample has no FASTA/assembly file for contig loading: " + sample.getName());
        }
        try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(fasta)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(">")) {
                    continue;
                }
                ContigHeader header = parseContigHeader(line.substring(1));
                sample.addContig(header.id(), header.description());
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse contigs from: " + fasta, e);
        }
    }

    private static ContigHeader parseContigHeader(String rawHeader) {
        String trimmed = rawHeader.trim();
        int firstWhitespace = findFirstWhitespace(trimmed);
        if (firstWhitespace < 0) {
            return new ContigHeader(trimmed, "");
        }
        return new ContigHeader(trimmed.substring(0, firstWhitespace), trimmed.substring(firstWhitespace + 1).trim());
    }

    private static int findFirstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private record ContigHeader(String id, String description) {
    }
}
