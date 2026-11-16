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
    void aFastaTooLongForBlastIsSearchedFromACopy() throws IOException {
        // On Windows, blastn cannot open a path of 260 characters or more ("File is not accessible").
        Path fasta = Files.writeString(directory.resolve("sample.fasta"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));
        Path copy;

        try (BlastQuery query = BlastQuery.of(fasta, scratch, true)) {
            copy = query.path();
            assertEquals(scratch, copy.getParent());
            assertEquals(FASTA, Files.readString(copy));
        }

        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(fasta));
    }

    @Test
    void aFastaWithAByteOrderMarkIsSearchedFromACopyWithoutIt() throws IOException {
        // blastn: "CFastaReader: Near line 1, there's a line that doesn't look like plausible data".
        byte[] text = FASTA.getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[text.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(text, 0, marked, 3, text.length);
        Path fasta = Files.write(directory.resolve("notepad.fasta"), marked);
        Path gzipped = Files.write(directory.resolve("notepad.fasta.gz"), gzipBytes(marked));

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertNotEquals(fasta, query.path());
            assertEquals(FASTA, Files.readString(query.path()));
        }
        try (BlastQuery query = BlastQuery.of(gzipped, directory)) {
            assertEquals(FASTA, Files.readString(query.path()));
        }
        assertTrue(Files.exists(fasta));
    }

    @Test
    void theLongestCopyNameIsTheLongestNameOfACopy() throws IOException {
        // WindowsPathLimit counts it among the files a run writes.
        Path fasta = Files.write(directory.resolve("s.fasta.gz"), gzip(FASTA));
        for (int i = 0; i < 20; i++) {
            try (BlastQuery query = BlastQuery.of(fasta, directory)) {
                String name = query.path().getFileName().toString();
                assertTrue(name.length() <= BlastQuery.LONGEST_NAME.length(), name);
                assertTrue(name.startsWith("blast_query_") && name.endsWith(".fasta"), name);
            }
        }
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
        return gzipBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzipBytes(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    void aSequenceLongerThanAPieceIsCopiedInOverlappingPiecesAndItsHitsPutBack() throws IOException {
        // 2.5 pieces long: pieces at 0, 999,000 and 1,998,000, each sharing 1,000 bases with the next.
        StringBuilder bases = new StringBuilder();
        java.util.Random random = new java.util.Random(3);
        while (bases.length() < 2_500_000) {
            bases.append("ACGT".charAt(random.nextInt(4)));
        }
        Path fasta = Files.writeString(directory.resolve("long.fasta"), ">short one\nACGTACGT\n>chr1 long\n" + bases + "\n");

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            java.util.Map<String, Integer> lengths = ca.canada.inspection.util.SequenceFileUtils.sequenceLengths(query.path());
            assertEquals(java.util.Map.of("short", 8, BlastQuery.PIECE_ID + "0", 1_000_000, BlastQuery.PIECE_ID + "1",
                    1_000_000, BlastQuery.PIECE_ID + "2", 502_000), lengths);
            // A hit at 11-30 of the second piece is at 999,011-999,030 of chr1; a short sequence's line is as it was.
            String line = String.join("\t", BlastQuery.PIECE_ID + "1", "a-F", "20", "0", "0", "1e-5", "40", "20", "20",
                    "11", "30", "ACGT", "1", "20", "ACGT");
            assertEquals(String.join("\t", "chr1", "a-F", "20", "0", "0", "1e-5", "40", "20", "20", "999011", "999030",
                    "ACGT", "1", "20", "ACGT"), query.toContig(line));
            assertEquals("short\ta-F", query.toContig("short\ta-F"));
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(java.util.List.of(), files.filter(file -> file.getFileName().toString().startsWith("blast_query_")).toList());
        }
    }
}
