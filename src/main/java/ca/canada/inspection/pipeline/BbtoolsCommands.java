package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the BBDuk and Tadpole command lines of FASTQ mode. Shared by the
 * command-line ({@link FastqProcessor}) and GUI ({@link BbmapPipeline}) pipelines
 * so both bait and assemble reads identically.
 *
 * @param javaCommand    Java launcher used for the BBTools child processes
 * @param bbtoolsJar     bundled {@code bbtools.jar}
 * @param childMemoryGiB heap given to each BBTools child process
 * @param threads        threads given to each BBTools child process
 */
record BbtoolsCommands(String javaCommand, Path bbtoolsJar, int childMemoryGiB, int threads) {

    BbtoolsCommands {
        Objects.requireNonNull(javaCommand, "javaCommand");
        Objects.requireNonNull(bbtoolsJar, "bbtoolsJar");
        if (childMemoryGiB < 1) {
            throw new IllegalArgumentException("childMemoryGiB must be >= 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
    }

    static Path targetMatches(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_targetMatches.fastq.gz");
    }

    static Path doubleTargetMatches(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_doubleTargetMatches.fastq.gz");
    }

    static Path assembly(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_assembly.fasta");
    }

    /** Reads matched per oligo in the first pass (BBDuk {@code stats=}), used to flag unsupported negatives. */
    static Path baitStats(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_baitStats.txt");
    }

    /**
     * First pass: keep reads sharing a k-mer with any primer, allowing
     * {@code hdist} mismatches so primers with tolerated mismatches still bait.
     * {@code stats} receives the reads matched per oligo.
     */
    List<String> firstBait(Path primerDatabase, List<Path> reads, Path output, Path stats, int kLength, int hdist) {
        List<String> command = bbduk(primerDatabase, reads, output, hdist);
        command.add("k=" + kLength);
        command.add("stats=" + stats);
        return command;
    }

    /**
     * Second pass: recruit reads overlapping the first-pass matches. Exact k-mer
     * matching suffices here; expanding this much larger reference by hdist grows
     * the k-mer table by ~hdist^k-fold (tens of billions of entries at hdist=2 on a
     * large match set) and exhausts the child heap.
     */
    List<String> secondBait(Path firstPassMatches, List<Path> reads, Path output) {
        return bbduk(firstPassMatches, reads, output, 0);
    }

    List<String> assemble(Path reads, Path output, TadpoleOptions tadpole) {
        List<String> command = new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g",
                "-cp", bbtoolsJar.toString(), "assemble.Tadpole",
                "in=" + reads,
                "out=" + output,
                "overwrite=t",
                "threads=" + threads
        ));
        command.addAll(tadpole.arguments());
        return command;
    }

    private List<String> bbduk(Path ref, List<Path> reads, Path output, int hdist) {
        if (reads.isEmpty() || reads.size() > 2) {
            throw new IllegalArgumentException("Expected one or two read files, got " + reads.size());
        }
        List<String> command = new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g",
                "-cp", bbtoolsJar.toString(), "jgi.BBDuk",
                "ref=" + ref,
                "hdist=" + hdist,
                "threads=" + threads,
                "overwrite=t",
                "interleaved=t"
        ));
        if (reads.size() == 2) {
            command.add("in1=" + reads.get(0));
            command.add("in2=" + reads.get(1));
        } else {
            command.add("in=" + reads.getFirst());
        }
        command.add("outm=" + output);
        return command;
    }
}
