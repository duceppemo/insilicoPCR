package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SampleFilesTest {

    @Test
    void sampleFileNamesFollowTheDetailedReportLayout() {
        SampleFiles files = SampleFiles.of(Path.of("detailed_report"), "s");
        Path dir = Path.of("detailed_report", "s");
        assertEquals(dir, files.directory());
        assertEquals(dir.resolve("s.tsv"), files.blastReport());
        assertEquals(dir.resolve("s_targetMatches.fastq.gz"), files.targetMatches());
        assertEquals(dir.resolve("s_doubleTargetMatches.fastq.gz"), files.doubleTargetMatches());
        assertEquals(dir.resolve("s_assembly.fasta"), files.assembly());
        assertEquals(dir.resolve("s_merged.fastq.gz"), files.mergedReads());
        assertEquals(dir.resolve("s_unmerged.fastq.gz"), files.unmergedReads());
        assertEquals(dir.resolve("s_baitedReads.fasta"), files.baitedReadsFasta());
        // Not .tsv, so that it is not taken for the sample's contig hits.
        assertEquals(dir.resolve("s_baitedReads.blast.txt"), files.baitedReadsHits());
        assertEquals(dir.resolve("s_extensionSeeds.fasta"), files.extensionSeeds());
        assertEquals(dir.resolve("s_extendedReads.fasta"), files.extendedReads());
        assertEquals(dir.resolve("s_extendedReads.blast.txt"), files.extendedReadsHits());
        assertEquals(dir.resolve("s_extensionCandidates.fasta"), files.extensionCandidates());
        assertEquals(dir.resolve("s_extensionCandidateReads.fastq.gz"), files.candidateReads());
    }

    @Test
    void aSampleFolderIsMadeWithoutTheQueryCopyAKilledRunLeft(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        // A whole decompressed genome, deleted only by the search that wrote it.
        PipelineDirectories directories = PipelineDirectories.create(dir);
        Path folder = directories.sample("s").directory();
        java.nio.file.Files.createDirectories(folder);
        Path left = java.nio.file.Files.writeString(folder.resolve("blast_query_123.fasta"), ">c\nACGT\n");
        Path kept = java.nio.file.Files.writeString(folder.resolve("s.tsv"), "");

        directories.createSampleDirectory("s");

        assertEquals(false, java.nio.file.Files.exists(left));
        assertEquals(true, java.nio.file.Files.exists(kept));
    }
}
