package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastQueryTest {

    private static final String FASTA = ">contig_1\nACGTACGTAC\nGGTTAACC\n";

    @TempDir
    Path directory;

    @Test
    void aPlainFastaIsSearchedWhereItIs() throws IOException {
        Path fasta = Files.writeString(directory.resolve("sample.fasta"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));

        try (BlastQuery query = BlastQuery.of(fasta, scratch)) {
            assertEquals(fasta, query.path());
        }

        assertTrue(Files.exists(fasta));
        assertTrue(filesIn(scratch).isEmpty());
    }

    @Test
    void aGzippedFastaIsDecompressedForTheSearchOnly() throws IOException {
        Path fasta = Files.write(directory.resolve("sample.fasta.gz"), gzip(FASTA));
        Path scratch = Files.createDirectory(directory.resolve("scratch"));
        Path plain;

        try (BlastQuery query = BlastQuery.of(fasta, scratch)) {
            plain = query.path();
            assertNotEquals(fasta, plain);
            assertEquals(scratch, plain.getParent());
            assertEquals(FASTA, Files.readString(plain));
        }

        assertFalse(Files.exists(plain));
        assertTrue(Files.exists(fasta));
    }

    @Test
    void theExtensionIsReadWhateverItsCase() throws IOException {
        Path fasta = Files.write(directory.resolve("SAMPLE.FNA.GZ"), gzip(FASTA));

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertEquals(FASTA, Files.readString(query.path()));
        }
    }

    @Test
    void everyMemberOfAConcatenatedGzipIsRead() throws IOException {
        // bgzip and "cat a.gz b.gz" both write several gzip members into one file.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(gzip(">contig_1\nACGT\n"));
        bytes.write(gzip(">contig_2\nTTGG\n"));
        Path fasta = Files.write(directory.resolve("sample.fa.gz"), bytes.toByteArray());

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertEquals(">contig_1\nACGT\n>contig_2\nTTGG\n", Files.readString(query.path()));
        }
    }

    @Test
    void aFileThatIsNotGzipLeavesNothingBehind() throws IOException {
        Path fasta = Files.writeString(directory.resolve("sample.fasta.gz"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));

        IOException error = assertThrows(IOException.class, () -> BlastQuery.of(fasta, scratch));

        assertTrue(error.getMessage().startsWith("Unable to decompress " + fasta), error.getMessage());
        assertTrue(filesIn(scratch).isEmpty());
    }

    private static java.util.List<Path> filesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
