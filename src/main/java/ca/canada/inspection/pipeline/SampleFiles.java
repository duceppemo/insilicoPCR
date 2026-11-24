package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * The reads of a further pass of the first bait: the oligos of another length ({@link BbtoolsCommands#baitGroups}),
     * or the substitution bait at {@code -m 3} and more ({@link BbtoolsCommands#substitutionBait}). Those not in
     * {@link #targetMatches} yet are added to it, then this file is removed.
     */
    Path moreMatches() {
        return directory.resolve(name + "_moreMatches.fastq.gz");
    }

    /** The reads of the first baiting that hold an accepted primer site, with their mates: the second baiting's start. */
    Path baitAnchors() {
        return directory.resolve(name + "_baitAnchors.fastq.gz");
    }

    /**
     * The anchors with adapter read-through and poly-G tails cut off: what the second baiting baits from. One
     * anchor ending in a TruSeq adapter baited every adapter dimer of a library, 72% of its reads.
     */
    Path baitAnchorsTrimmed() {
        return directory.resolve(name + "_baitAnchorsTrimmed.fastq.gz");
    }

    /** The first baiting's reads as FASTA, searched for the primers (removed after). */
    Path baitAnchorsQuery() {
        return directory.resolve(name + "_baitAnchors.query.fasta");
    }

    /** The primer hits on {@link #baitAnchorsQuery} (removed after). */
    Path baitAnchorsHits() {
        return directory.resolve(name + "_baitAnchors.blast.txt");
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
     * The seeds extended through the reads baited in rounds. Each is the best-supported path
     * from one read, not an assembled consensus.
     */
    Path extendedReads() {
        return directory.resolve(name + "_extendedReads.fasta");
    }

    /**
     * The products that extended reads hold, before the read pairs have
     * confirmed them or not: best-supported paths, not assembled consensus sequences.
     */
    Path extensionCandidates() {
        return directory.resolve(name + "_extensionCandidates.fasta");
    }

    /** The sample's reads (pairs kept together) sharing a k-mer with a candidate product. */
    Path candidateReads() {
        return directory.resolve(name + "_extensionCandidateReads.fastq.gz");
    }

    /** The ends of extended reads that stopped short of a product, from which a round of the extension baits more. */
    Path extensionEnds() {
        return directory.resolve(name + "_extensionEnds.fasta");
    }

    /**
     * What one pass over the sample's reads baits from in a round of the extension: its candidate products and the
     * ends of its extended reads together. The reads of each are then taken from that pass's reads
     * ({@link #extensionUnionReads}), as a pass of their own would have baited them.
     */
    Path extensionUnionRef() {
        return directory.resolve(name + "_extensionUnionRef.fasta");
    }

    /** The sample's reads (pairs kept together) sharing a k-mer with {@link #extensionUnionRef}. */
    Path extensionUnionReads() {
        return directory.resolve(name + "_extensionUnionReads.fastq.gz");
    }

    /** The seeds a round of the extension extends: those of the assays not closed yet. */
    Path extensionRoundSeeds() {
        return directory.resolve(name + "_extensionRoundSeeds.fasta");
    }

    /** The sample's reads (pairs kept together) sharing a k-mer with the ends of one round's extended reads. */
    Path extensionRoundReads() {
        return directory.resolve(name + "_extensionRoundReads.fastq.gz");
    }

    /** Every read the rounds of the extension baited from the ends of extended reads, the rounds one after another. */
    Path extensionReads() {
        return directory.resolve(name + "_extensionReads.fastq.gz");
    }

    /** BLAST hits of the primers on the extended reads; not {@code .tsv}, as for {@link #baitedReadsHits}. */
    Path extendedReadsHits() {
        return directory.resolve(name + "_extendedReads.blast.txt");
    }

    /**
     * Every file a run writes here: an assembly gets its {@link #blastReport} only, a read set all of
     * them. Windows' path limit is checked on the longest ({@link WindowsPathLimit}).
     */
    List<Path> written(boolean fastq) {
        if (!fastq) {
            return List.of(blastReport());
        }
        return List.of(blastReport(), targetMatches(), moreMatches(), baitAnchors(), baitAnchorsTrimmed(), baitAnchorsQuery(),
                baitAnchorsHits(),
                doubleTargetMatches(), assembly(), mergedReads(),
                unmergedReads(), baitedReadsFasta(), baitedReadsHits(), extensionSeeds(), extendedReads(),
                extensionCandidates(), candidateReads(), extendedReadsHits(), extensionEnds(), extensionRoundReads(),
                extensionReads(), extensionRoundSeeds(), extensionUnionRef(), extensionUnionReads());
    }
}
