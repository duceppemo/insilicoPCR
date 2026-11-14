package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.BlastReports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class BlastRunner {
    private BlastRunner() {
    }

    public static void run(PipelineContext context) {
        List<Sample> samples = List.copyOf(context.samples().values());
        ParallelStageRunner.run("BLAST", samples, context.config().threads(), context.monitor(),
                sample -> blast(context, sample), context.monitor()::blastProgress);
    }

    /** The FASTA file searched for {@code sample}: its assembly, or the mini-assembly of its reads. */
    private static Path query(Sample sample) {
        if (sample.isFastq()) {
            if (sample.getAssemblyFile() == null) {
                throw new PipelineException("FASTQ sample has no assembly file: " + sample.getName());
            }
            return sample.getAssemblyFile();
        }
        // The input check leaves out a FASTA sample of more than one file.
        if (sample.files().size() != 1) {
            throw new IllegalStateException("FASTA sample " + sample.getName() + " is not one file: " + sample.files());
        }
        return sample.files().getFirst();
    }

    private static void blast(PipelineContext context, Sample sample) {
        // The report is named after the sample, not the file, so that it is read back for that
        // sample whatever the file is called (BlastReports.attachHits).
        Path query = query(sample);
        context.directories().createSampleDirectory(sample.getName());
        Path blastTsv = BlastReports.reportFile(context.directories().detailed(), sample.getName());
        Path blastOutput = blastTsv.getParent();

        try (BlastQuery blastQuery = BlastQuery.of(query, blastOutput)) {
            ExternalProgram.run(context.monitor(), null, List.of(
                    context.dependencies().blastn().toString(),
                    "-task", "blastn-short",
                    "-query", blastQuery.path().toString(),
                    "-db", context.directories().primerDatabase().toString(),
                    "-evalue", Double.toString(context.config().evalue()),
                    "-num_alignments", "1000000",
                    "-num_threads", "1",
                    "-outfmt", BlastTsv.OUTFMT,
                    "-out", blastTsv.toString()), "blastn");
        } catch (IOException e) {
            throw new PipelineException("Unable to prepare the BLAST query for " + query, e);
        }
        BlastTsv.prependHeader(blastTsv);
    }
}
