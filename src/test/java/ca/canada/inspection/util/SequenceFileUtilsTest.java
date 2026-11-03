package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceFileUtilsTest {

    @TempDir
    Path dir;

    private Path touch(String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createFile(file);
        return file;
    }

    @Test
    void enaStylePairedFastqFilesShareOneSampleName() throws IOException {
        Path r1 = touch("SRR10729095_1.fastq.gz");
        Path r2 = touch("SRR10729095_2.fastq.gz");
        assertEquals("SRR10729095", SequenceFileUtils.sampleName(r1));
        assertEquals("SRR10729095", SequenceFileUtils.sampleName(r2));
    }

    @Test
    void bareDigitWithoutAMateFileStaysItsOwnSample() throws IOException {
        // A lone single-end run named with a trailing _1 must not lose its suffix.
        Path lonely = touch("isolate_1.fastq.gz");
        assertEquals("isolate_1", SequenceFileUtils.sampleName(lonely));
    }

    @Test
    void sequentiallyNumberedSingleEndSamplesAreNotMerged() throws IOException {
        // isolate_1/_2/_3 is sequential sample numbering, not a read pair.
        Path s1 = touch("isolate_1.fastq.gz");
        Path s2 = touch("isolate_2.fastq.gz");
        touch("isolate_3.fastq.gz");
        assertEquals("isolate_1", SequenceFileUtils.sampleName(s1));
        assertEquals("isolate_2", SequenceFileUtils.sampleName(s2));
    }

    @Test
    void illuminaStylePairedFastqFilesShareOneSampleName() {
        // The explicit _R1/_R2 convention needs no mate-file heuristics.
        assertEquals("sampleA", SequenceFileUtils.sampleName(Path.of("sampleA_R1.fastq.gz")));
        assertEquals("sampleA", SequenceFileUtils.sampleName(Path.of("sampleA_R2_001.fastq")));
    }

    @Test
    void bareDigitSuffixIsKeptForFastaFiles() throws IOException {
        // Two assemblies named strain_1/strain_2 are different samples, never a read pair.
        Path a1 = touch("strain_1.fasta");
        touch("strain_2.fasta");
        assertEquals("strain_1", SequenceFileUtils.sampleName(a1));
        // The Illumina _R1/_R2 convention is still stripped for FASTA.
        assertEquals("strain", SequenceFileUtils.sampleName(Path.of("strain_R1.fasta")));
    }

    @Test
    void plainFilesKeepTheirBaseName() {
        assertEquals("lmono_EGDe", SequenceFileUtils.sampleName(Path.of("lmono_EGDe.fasta")));
        assertEquals("reads", SequenceFileUtils.sampleName(Path.of("reads.fq.gz")));
    }
    @Test
    void createSampleMapGroupsPairsAndSeparatesAssemblies() throws IOException {
        write("genomeA.fasta", ">c1\nACGT\n");
        write("run_1.fastq", "@r1\nACGT\n+\nIIII\n");
        write("run_2.fastq", "@r1\nACGT\n+\nIIII\n");
        write("notes.txt", "not a sequence file\n");

        var samples = SequenceFileUtils.createSampleMap(dir);
        assertEquals(java.util.Set.of("genomeA", "run"), samples.keySet());
        assertEquals(2, samples.get("run").files().size());
        assertEquals("fastq", samples.get("run").getFileType());
        assertEquals("fasta", samples.get("genomeA").getFileType());
    }

    @Test
    void createSampleMapAcceptsASingleFile() throws IOException {
        Path file = write("solo.fasta", ">c1\nACGT\n");
        var samples = SequenceFileUtils.createSampleMap(file);
        assertEquals(java.util.Set.of("solo"), samples.keySet());
    }

    @Test
    void looksLikeSequenceFileChecksContentNotJustExtension() throws IOException {
        assertEquals(true, SequenceFileUtils.looksLikeSequenceFile(write("ok.fasta", ">c1\nACGT\n")));
        assertEquals(true, SequenceFileUtils.looksLikeSequenceFile(write("ok.fastq", "@r1\nACGT\n")));
        assertEquals(false, SequenceFileUtils.looksLikeSequenceFile(write("bad.fasta", "plain text\n")));
        assertEquals(false, SequenceFileUtils.looksLikeSequenceFile(write("empty.fasta", "")));
        assertEquals(false, SequenceFileUtils.looksLikeSequenceFile(write("wrong.txt", ">c1\nACGT\n")));
        assertEquals(false, SequenceFileUtils.looksLikeSequenceFile(dir)); // a directory
    }

    @Test
    void gzippedSequenceFilesAreRecognized() throws IOException {
        Path gz = dir.resolve("reads.fastq.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("@r1\nACGT\n+\nIIII\n".getBytes());
        }
        assertEquals(true, SequenceFileUtils.looksLikeSequenceFile(gz));
        assertEquals("fastq", SequenceFileUtils.fileType(gz));
    }

    @Test
    void fileTypeClassifiesByExtension() {
        assertEquals("fasta", SequenceFileUtils.fileType(Path.of("x.fna")));
        assertEquals("fastq", SequenceFileUtils.fileType(Path.of("x.fq.gz")));
        assertEquals("unknown", SequenceFileUtils.fileType(Path.of("x.txt")));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

}
