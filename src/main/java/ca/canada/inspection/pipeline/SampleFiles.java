package ca.canada.inspection.pipeline;

import java.nio.file.Path;

/**
 * The files of one sample in a run's {@code detailed_report} folder: the one place their
 * folder and names are written, for the steps that write them and those that read them.
 *
 * @param directory the sample's folder, {@code detailed_report/<sample>}
 * @param name      the sample's name, which every file name starts with
 */
public record SampleFiles(Path directory, String name) {

    /** The files of sample {@code name} under {@code detailedDir}. */
    static SampleFiles of(Path detailedDir, String name) {
        return new SampleFiles(detailedDir.resolve(name), name);
    }

    /** The BLAST hits of the primers on the sample's sequences (an assembly or the mini-assembly). */
    public Path blastReport() {
        return directory.resolve(name + ".tsv");
    }

    Path targetMatches() {
        return directory.resolve(name + "_targetMatches.fastq.gz");
    }

    Path doubleTargetMatches() {
        return directory.resolve(name + "_doubleTargetMatches.fastq.gz");
    }

    Path assembly() {
        return directory.resolve(name + "_assembly.fasta");
    }

    /** First-pass pairs that BBMerge could overlap into one fragment. */
    Path mergedReads() {
        return directory.resolve(name + "_merged.fastq.gz");
    }

    /** First-pass pairs that BBMerge could not merge, kept as interleaved mates. */
    Path unmergedReads() {
        return directory.resolve(name + "_unmerged.fastq.gz");
    }

    /** First-pass reads (merged where possible) as FASTA with unique IDs, searched for read-level amplicons. */
    Path baitedReadsFasta() {
        return directory.resolve(name + "_baitedReads.fasta");
    }

    /**
     * BLAST hits of the primers on the baited reads. Not {@code .tsv}, so that it is not taken for
     * {@link #blastReport}, the contig hits, when someone looks through the folder.
     */
    Path baitedReadsHits() {
        return directory.resolve(name + "_baitedReads.blast.txt");
    }

    /** Reads carrying a primer of an assay still unreported, used as seeds of the read extension. */
    Path extensionSeeds() {
        return directory.resolve(name + "_extensionSeeds.fasta");
    }

    /**
     * The seeds extended through the baited reads. Each is the best-supported path
     * from one read, not an assembled consensus.
     */
    Path extendedReads() {
        return directory.resolve(name + "_extendedReads.fasta");
    }

    /**
     * The products that extended reads of both primers hold, before the read pairs have
     * confirmed them or not: best-supported paths, not assembled consensus sequences.
     */
    Path extensionCandidates() {
        return directory.resolve(name + "_extensionCandidates.fasta");
    }

    /** The sample's reads (pairs kept together) sharing a k-mer with a candidate product. */
    Path candidateReads() {
        return directory.resolve(name + "_extensionCandidateReads.fastq.gz");
    }

    /** BLAST hits of the primers on the extended reads; not {@code .tsv}, as for {@link #baitedReadsHits}. */
    Path extendedReadsHits() {
        return directory.resolve(name + "_extendedReads.blast.txt");
    }
}
