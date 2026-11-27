package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.InputCheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BlastRunner {
    private BlastRunner() {
    }

    /**
     * Searches every sample's FASTA. An assembly blastn refuses (a record whose first line holds no base,
     * a file with no base at all: its reader is stricter than the input check) is a faulty input file: its
     * sample is left out, named in a warning and among the run's {@link PipelineContext#inputIssues}, and
     * the others are run. When blastn refuses every assembly, the fault is not the files': the run fails.
     */
    public static void run(PipelineContext context) {
        List<Sample> samples = List.copyOf(context.samples().values());
        Map<String, Refused> refused = new ConcurrentHashMap<>();
        // As many searches at once as the memory holds; the threads left over go to each of them.
        int threads = context.config().threads();
        int atOnce = ChildJavaMemory.blastnAtOnce(context.childJavaMemoryBudgetGiB(), threads, samples.size());
        int each = ChildJavaMemory.blastnThreads(threads, atOnce);
        if (atOnce < Math.min(threads, samples.size())) {
            context.monitor().info("BLAST: " + atOnce + (atOnce == 1 ? " search" : " searches") + " at a time, each with "
                    + each + (each == 1 ? " thread" : " threads") + " (about " + ChildJavaMemory.BLASTN_GIB
                    + " GB each, within " + context.childJavaMemoryBudgetGiB() + " GB)");
        }
        ParallelStageRunner.run("BLAST", samples, atOnce, context.monitor(),
                sample -> blast(context, sample, refused, each), context.monitor()::blastProgress);
        if (refused.isEmpty()) {
            return;
        }
        // Every sample refused, mini-assemblies included: the fault is not the files'.
        if (refused.size() == samples.size()) {
            throw new PipelineException("BLAST failed", refused.values().iterator().next().failure());
        }
        for (Sample sample : samples) {
            Refused one = refused.get(sample.getName());
            if (one != null) {
                context.samples().remove(sample.getName());
                context.inputIssues().add(one.issue());
                context.monitor().warning("Warning: " + one.issue().line());
            }
        }
    }

    /**
     * blastn's exit codes for a query it cannot read: 1, "BLAST query error" (a record whose first line holds
     * no base); 3, "Sequence contains no data" (a file with no base at all).
     */
    static final java.util.Set<Integer> REFUSED_THE_FILE = java.util.Set.of(1, 3);

    /** An assembly blastn could not search, and why. */
    private record Refused(InputCheck.Issue issue, ProgramFailedException failure) {
    }

    /** The FASTA file searched for {@code sample} ({@link PipelineContext#searchedFasta}): one, always. */
    private static Path query(PipelineContext context, Sample sample) {
        List<Path> searched = context.searchedFasta(sample);
        // The input check leaves out a FASTA sample of more than one file.
        if (searched.size() != 1) {
            throw new IllegalStateException("Sample " + sample.getName() + " is not one FASTA file: " + searched);
        }
        return searched.getFirst();
    }

    private static void blast(PipelineContext context, Sample sample, Map<String, Refused> refused, int threads) {
        // The report is named after the sample, not the file, so that it is read back for that
        // sample whatever the file is called (SampleFiles.blastReport).
        Path query = query(context, sample);
        context.directories().createSampleDirectory(sample.getName());
        Path blastTsv = context.directories().sample(sample.getName()).blastReport();
        Path blastOutput = blastTsv.getParent();

        // A long sequence is searched in pieces: its hits are put back on it as the table is written.
        java.util.function.UnaryOperator<String> toContig;
        // Its circles (a plasmid marked circular=true, a contig of the mini-assembly closing on itself) are
        // searched as circles: a product across the origin is one a PCR makes.
        // Its descriptions, lengths and circles, read in one pass: the file was read once for each.
        ca.canada.inspection.util.FastaSummary summary;
        try {
            summary = ca.canada.inspection.util.FastaSummary.of(query, context.config().tadpole().k());
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new PipelineException("Unable to prepare the BLAST query for " + query, e);
        }
        Map<String, Integer> circles = summary.circles();
        circles.forEach(sample.hits()::circular);
        try (BlastQuery blastQuery = BlastQuery.of(query, blastOutput, circles, summary.lengths())) {
            toContig = blastQuery::toContig;
            BlastTsv.runBlastn(context.monitor(), context.dependencies().blastn(), blastQuery.path(),
                    context.directories().primerDatabase(), context.config().evalue(), threads, blastTsv);
        } catch (ProgramFailedException e) {
            // Its mini-assembly is the program's own file, not the user's to fix; and only blastn's reader
            // refusing the file is the file's fault: a blastn killed (out of memory: 137), a database or a
            // disk error fails the run, as before, rather than calling a good file faulty.
            if (sample.isFastq() || !REFUSED_THE_FILE.contains(e.exitCode())) {
                throw e;
            }
            refused.put(sample.getName(), new Refused(new InputCheck.Issue(sample.getName(),
                    List.of(query.getFileName().toString()), "blastn cannot read the file",
                    "its message is in the log above: " + e.getMessage()), e));
            try {
                java.nio.file.Files.deleteIfExists(blastTsv);
            } catch (IOException ignored) {
                // a partial table left beside nothing that reads it
            }
            return;
        } catch (IOException e) {
            throw new PipelineException("Unable to prepare the BLAST query for " + query, e);
        }
        sample.hits().describeAll(summary.descriptions(), summary.lengths());
        // The hits the rules accept, kept in the sample's table and taken for its products, in one pass.
        java.util.Set<String> missing = BlastTsv.keepAccepted(blastTsv, List.of(query), context.primers(),
                context.config().ampliconOptions(), toContig, sample.hits());
        String warning = ca.canada.inspection.pcr.BlastHits.missingWarning(missing, List.of(query));
        if (!warning.isEmpty()) {
            context.monitor().warning(warning);
        }
    }
}
