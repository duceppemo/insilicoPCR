package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.FastaScanner;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Loads contig descriptions from FASTA headers into each sample. */
final class ContigService {
    private ContigService() {
    }

    static void load(PipelineContext context) {
        for (Sample sample : context.samples().values()) {
            List<Path> searched = sample.searchedFasta();
            if (searched.isEmpty()) {
                throw new PipelineException("Sample has no FASTA/assembly file for contig loading: " + sample.getName());
            }
            for (Path file : searched) {
                addContigsFromFasta(sample, file);
            }
        }
    }

    /** Adds the contigs of {@code fasta} (ID and description) to {@code sample}, reading headers only. */
    private static void addContigsFromFasta(Sample sample, Path fasta) {
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            String header;
            while ((header = scanner.nextHeader(null)) != null) {
                sample.addContig(SequenceFileUtils.fastaId(header), SequenceFileUtils.fastaDescription(header));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse contigs from: " + fasta, e);
        }
    }
}
