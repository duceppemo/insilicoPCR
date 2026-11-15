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

    /** The FASTA file searched for {@code sample} ({@link Sample#searchedFasta}): one, always. */
    private static Path query(Sample sample) {
        List<Path> searched = sample.searchedFasta();
        if (sample.isFastq() && searched.isEmpty()) {
            throw new PipelineException("FASTQ sample has no assembly file: " + sample.getName());
        }
        // The input check leaves out a FASTA sample of more than one file.
        if (searched.size() != 1) {
            throw new IllegalStateException("Sample " + sample.getName() + " is not one FASTA file: " + searched);
        }
        return searched.getFirst();
    }

    private static void blast(PipelineContext context, Sample sample) {
        // The report is named after the sample, not the file, so that it is read back for that
        // sample whatever the file is called (BlastReports.attachHits).
        Path query = query(sample);
        context.directories().createSampleDirectory(sample.getName());
        Path blastTsv = BlastReports.reportFile(context.directories().detailed(), sample.getName());
        Path blastOutput = blastTsv.getParent();

        try (BlastQuery blastQuery = BlastQuery.of(query, blastOutput)) {
            BlastTsv.runBlastn(context.monitor(), context.dependencies().blastn(), blastQuery.path(),
                    context.directories().primerDatabase(), context.config().evalue(), 1, blastTsv);
        } catch (IOException e) {
            throw new PipelineException("Unable to prepare the BLAST query for " + query, e);
        }
        BlastTsv.prependHeader(blastTsv);
    }
}
