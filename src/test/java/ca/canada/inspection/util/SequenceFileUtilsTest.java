package ca.canada.inspection.util;

import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        // So is _R1/_R2: an assembly is one file, and a FASTA sample is never a read pair.
        assertEquals("strain_R1", SequenceFileUtils.sampleName(Path.of("strain_R1.fasta")));
        assertEquals(0, SequenceFileUtils.mate(Path.of("strain_R1.fasta")));
    }

    @Test
    void mateIsTheReadOfAPairAFastqFileHolds() throws IOException {
        assertEquals(1, SequenceFileUtils.mate(Path.of("s_R1.fastq.gz")));
        assertEquals(2, SequenceFileUtils.mate(Path.of("s_r2_001.FQ")));
        assertEquals(0, SequenceFileUtils.mate(Path.of("single.fastq")));
        Path first = touch("run_1.fastq");
        Path second = touch("run_2.fastq");
        assertEquals(1, SequenceFileUtils.mate(first));
        assertEquals(2, SequenceFileUtils.mate(second));
        assertEquals("ISO2", SequenceFileUtils.sampleName(Path.of("ISO2.FASTA")));
    }

    @Test
    void plainFilesKeepTheirBaseName() {
        assertEquals("lmono_EGDe", SequenceFileUtils.sampleName(Path.of("lmono_EGDe.fasta")));
        assertEquals("reads", SequenceFileUtils.sampleName(Path.of("reads.fq.gz")));
    }
    @Test
    void sampleMapGroupsPairsAndSeparatesAssemblies() throws IOException {
        var samples = SequenceFileUtils.sampleMapOf(List.of(write("genomeA.fasta", ">c1\nACGT\n"),
                write("run_1.fastq", "@r1\nACGT\n+\nIIII\n"), write("run_2.fastq", "@r1\nACGT\n+\nIIII\n")));
        assertEquals(java.util.Set.of("genomeA", "run"), samples.keySet());
        assertEquals(2, samples.get("run").files().size());
        assertEquals(Sample.FileType.FASTQ, samples.get("run").getFileType());
        assertEquals(Sample.FileType.FASTA, samples.get("genomeA").getFileType());
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
        assertEquals(Sample.FileType.FASTQ, SequenceFileUtils.fileType(gz));
    }

    @Test
    void fileTypeClassifiesByExtension() {
        assertEquals(Sample.FileType.FASTA, SequenceFileUtils.fileType(Path.of("x.fna")));
        assertEquals(Sample.FileType.FASTQ, SequenceFileUtils.fileType(Path.of("x.fq.gz")));
        assertEquals(Sample.FileType.UNKNOWN, SequenceFileUtils.fileType(Path.of("x.txt")));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }


    @org.junit.jupiter.api.Test
    void fastaIdIsTheHeaderUpToTheFirstWhitespace() {
        org.junit.jupiter.api.Assertions.assertEquals("c1", SequenceFileUtils.fastaId(">c1 some description"));
        org.junit.jupiter.api.Assertions.assertEquals("c3", SequenceFileUtils.fastaId(">c3\tx"));
        org.junit.jupiter.api.Assertions.assertEquals("c4", SequenceFileUtils.fastaId(">c4"));
    }

    @Test
    void everyAcceptedExtensionIsReadByTypeAndLeftOutOfTheSampleName() {
        // One list: an extension added to it is accepted, typed and stripped from the name alike.
        assertEquals(SequenceFileUtils.ACCEPTED_EXTENSIONS.size(),
                SequenceFileUtils.FASTA_EXTENSIONS.size() + SequenceFileUtils.FASTQ_EXTENSIONS.size());
        for (String extension : SequenceFileUtils.ACCEPTED_EXTENSIONS) {
            Path file = Path.of("iso" + extension.toUpperCase(java.util.Locale.ROOT));
            assertEquals("iso", SequenceFileUtils.sampleName(file), extension);
            org.junit.jupiter.api.Assertions.assertTrue(SequenceFileUtils.hasExtension(file,
                    SequenceFileUtils.FASTA_EXTENSIONS) ^ SequenceFileUtils.hasExtension(file,
                    SequenceFileUtils.FASTQ_EXTENSIONS), extension);
        }
    }

    @Test
    void aByteOrderMarkIsNotReadAsText() throws IOException {
        // Notepad's UTF-8: the mark stood before the first > of a FASTA file.
        Path plain = Files.write(dir.resolve("bom.fasta"), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '>', 'a', '\n'});
        try (var reader = SequenceFileUtils.openMaybeGzip(plain)) {
            assertEquals(">a", reader.readLine());
        }
        Path gzipped = dir.resolve("bom.fasta.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gzipped))) {
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '>', 'b', '\n'});
        }
        try (var reader = SequenceFileUtils.openMaybeGzip(gzipped)) {
            assertEquals(">b", reader.readLine());
        }
        Path without = Files.writeString(dir.resolve("plain.fasta"), ">c\n");
        try (var reader = SequenceFileUtils.openMaybeGzip(without)) {
            assertEquals(">c", reader.readLine());
        }
        Path empty = Files.writeString(dir.resolve("empty.fasta"), "");
        try (var reader = SequenceFileUtils.openMaybeGzip(empty)) {
            assertEquals(null, reader.readLine());
        }
    }
}
