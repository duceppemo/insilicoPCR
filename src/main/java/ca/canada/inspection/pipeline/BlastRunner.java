package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BlastRunner {
    public void run(PipelineContext context) {
        List<Path> queries = blastQueries(context);
        ParallelStageRunner.run("BLAST", queries, context.config().threads(), query -> blast(context, query));
    }

    private static List<Path> blastQueries(PipelineContext context) {
        ArrayList<Path> queries = new ArrayList<>();
        for (Sample sample : context.samples().values()) {
            if (sample.isFastq()) {
                Path assemblyFile = sample.getAssemblyFile();
                if (assemblyFile == null) {
                    throw new PipelineException("FASTQ sample has no assembly file: " + sample.getName());
                }
                queries.add(assemblyFile);
            } else {
                queries.addAll(sample.files());
            }
        }
        return queries.stream()
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    private static void blast(PipelineContext context, Path query) {
        String name = query.getFileName().toString()
                .replaceFirst("_assembly\\.fasta$", "")
                .replaceFirst("\\.(fasta|fna|ffn|fa|fsa)(\\.gz)?$", "");
        Path blastOutput = context.directories().detailed().resolve(name);
        context.directories().createSampleDirectory(name);
        Path blastTsv = blastOutput.resolve(name + ".tsv");

        ProcessRunner.run(null,
                context.dependencies().blastn().toString(),
                "-task", "blastn-short",
                "-query", query.toString(),
                "-db", context.directories().primerDatabase().toString(),
                "-evalue", Double.toString(context.config().evalue()),
                "-num_alignments", "1000000",
                "-num_threads", "1",
                "-outfmt", BlastTsv.OUTFMT,
                "-out", blastTsv.toString());
        BlastTsv.prependHeader(blastTsv);
    }
}
