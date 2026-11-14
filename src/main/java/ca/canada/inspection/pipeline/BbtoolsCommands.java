package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the BBTools command lines of FASTQ mode: baiting and recruiting (BBDuk),
 * merging (BBMerge), assembly and read extension (Tadpole).
 *
 * @param javaCommand    Java launcher used for the BBTools child processes
 * @param bbtoolsJar     bundled {@code bbtools.jar}
 * @param childMemoryGiB heap given to each BBTools child process
 * @param threads        threads given to each BBTools child process
 */
record BbtoolsCommands(String javaCommand, Path bbtoolsJar, int childMemoryGiB, int threads) {
    /**
     * Makes a child that runs out of heap exit with {@link #OUT_OF_MEMORY_EXIT}. Without it
     * the exit code is that of any other error (BBTools catches the error only to stop),
     * and an error in a worker thread may even leave the program running on.
     */
    private static final String EXIT_ON_OUT_OF_MEMORY = "-XX:+ExitOnOutOfMemoryError";
    /** Exit code of the Java runtime when {@code -XX:+ExitOnOutOfMemoryError} stops it. */
    static final int OUT_OF_MEMORY_EXIT = 3;

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

    /** First-pass pairs that BBMerge could overlap into one fragment. */
    static Path mergedReads(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_merged.fastq.gz");
    }

    /** First-pass pairs that BBMerge could not merge, kept as interleaved mates. */
    static Path unmergedReads(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_unmerged.fastq.gz");
    }

    /** First-pass reads (merged where possible) as FASTA with unique IDs, searched for read-level amplicons. */
    static Path baitedReadsFasta(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_baitedReads.fasta");
    }

    /**
     * BLAST hits of the primers on the baited reads. Deliberately not {@code .tsv}:
     * per-sample {@code .tsv} files are read as contig hits of a sample of that name.
     */
    static Path baitedReadsHits(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_baitedReads.blast.txt");
    }

    /** Reads carrying a primer of an assay still unreported, used as seeds of the read extension. */
    static Path extensionSeeds(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_extensionSeeds.fasta");
    }

    /**
     * The seeds extended through the baited reads. Each is the best-supported path
     * from one read, not an assembled consensus.
     */
    static Path extendedReads(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_extendedReads.fasta");
    }

    /**
     * The products that extended reads of both primers hold, before the read pairs have
     * confirmed them or not: best-supported paths, not assembled consensus sequences.
     */
    static Path extensionCandidates(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_extensionCandidates.fasta");
    }

    /** The sample's reads (pairs kept together) sharing a k-mer with a candidate product. */
    static Path candidateReads(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_extensionCandidateReads.fastq.gz");
    }

    /** BLAST hits of the primers on the extended reads; not {@code .tsv}, as for {@link #baitedReadsHits}. */
    static Path extendedReadsHits(Path sampleDir, String sampleName) {
        return sampleDir.resolve(sampleName + "_extendedReads.blast.txt");
    }

    /**
     * First pass: keep reads sharing a k-mer with any primer, allowing
     * {@code hdist} mismatches so primers with tolerated mismatches still bait.
     */
    List<String> firstBait(Path primerDatabase, List<Path> reads, Path output, int kLength, int hdist) {
        List<String> command = bbduk(primerDatabase, reads, output, hdist);
        command.add("k=" + kLength);
        return command;
    }

    /**
     * Merges overlapping first-pass pairs so a read-level search can find amplicons
     * longer than one read (up to the fragment length).
     */
    List<String> mergePairs(Path interleavedPairs, Path merged, Path unmerged) {
        return new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g", EXIT_ON_OUT_OF_MEMORY,
                "-cp", bbtoolsJar.toString(), "jgi.BBMerge",
                "in=" + interleavedPairs,
                "interleaved=t",
                "out=" + merged,
                "outu=" + unmerged,
                "overwrite=t",
                "threads=" + threads
        ));
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

    /**
     * Third pass, on the sample's own reads: keep every read, with its mate, sharing a
     * k-mer with a candidate product of the read extension. The baited reads do not
     * hold them all: far from the primers they thin out, and the reads of a second copy
     * of a repeat that say where the repeat ends are among those missing.
     */
    List<String> recruit(Path candidates, List<Path> reads, Path output, int k) {
        List<String> command = bbduk(candidates, reads, output, 0);
        command.add("k=" + k);
        return command;
    }

    List<String> assemble(Path reads, Path output, TadpoleOptions tadpole) {
        List<String> command = new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g", EXIT_ON_OUT_OF_MEMORY,
                "-cp", bbtoolsJar.toString(), "assemble.Tadpole",
                "in=" + reads,
                "out=" + output,
                "overwrite=t",
                "threads=" + threads
        ));
        command.addAll(tadpole.arguments());
        return command;
    }

    /**
     * Extends each seed both ways, by up to {@code reach} bases, through the k-mers of
     * {@code reads}. At a branch the better-supported path is always followed
     * ({@code branchmult=1}): Tadpole's defaults stop there, which is what breaks the
     * mini-assembly inside a multi-copy locus, where near-balanced variants are the rule.
     */
    List<String> extend(Path seeds, Path reads, Path output, int k, int reach) {
        return new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g", EXIT_ON_OUT_OF_MEMORY,
                "-cp", bbtoolsJar.toString(), "assemble.Tadpole",
                "in=" + seeds,
                "out=" + output,
                "mode=extend",
                "el=" + reach,
                "er=" + reach,
                "extra=" + reads,
                "k=" + k,
                "branchmult1=1",
                "branchmult2=1",
                "mincountextend=2",
                "overwrite=t",
                "threads=" + threads
        ));
    }

    private List<String> bbduk(Path ref, List<Path> reads, Path output, int hdist) {
        if (reads.isEmpty() || reads.size() > 2) {
            throw new IllegalArgumentException("Expected one or two read files, got " + reads.size());
        }
        List<String> command = new ArrayList<>(List.of(
                javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g", EXIT_ON_OUT_OF_MEMORY,
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
